package org.rsmod.api.game.process.player

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.types.InvScope
import dev.or2.central.account.Rights
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.rsmod.annotations.InternalApi
import org.rsmod.api.account.AccountManager
import org.rsmod.api.account.loader.request.AccountLoadResponse
import org.rsmod.api.account.saver.CharacterSaveSnapshot
import org.rsmod.api.account.saver.request.AccountSaveResponse
import org.rsmod.api.player.isLogoutBlocked
import org.rsmod.api.player.logoutBlockedMessage
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.realm.Realm
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.entity.player.WorldTypeChangedEvent
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.map.collision.isZoneValid
import org.rsmod.game.queue.QueueCategory
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

@Singleton
@OptIn(InternalApi::class)
public class WorldTypeSwitchService
@Inject
constructor(
    private val accountManager: AccountManager,
    private val players: PlayerList,
    private val eventBus: EventBus,
    private val collision: CollisionFlagMap,
    private val realm: Realm,
    private val engineQueues: PlayerEngineQueueProcessor,
) {
    private val logger = InlineLogger()
    private val inFlight = IdentityHashMap<Player, Attempt>()
    private val completions = ConcurrentLinkedQueue<Completion>()
    private var Player.newAccount: Boolean by boolVarBit("varbit.new_player_account")

    private enum class Stage { PREPARING, SAVING, LOADING }

    private class Attempt(
        val player: Player,
        val target: WorldType,
        val firstVisitSpawn: CoordGrid?,
        val startedAt: Long,
    ) {
        val source = player.worldType
        val characterId = player.characterId
        val accountId = player.accountId
        val username = player.username
        val uid = player.uid
        val slot = player.slotId
        val client = player.client
        var stage = Stage.PREPARING
        var checkpoint: CharacterSaveSnapshot? = null
    }

    private sealed interface Completion {
        val attempt: Attempt
        data class Saved(override val attempt: Attempt, val response: AccountSaveResponse) : Completion
        data class Loaded(override val attempt: Attempt, val response: AccountLoadResponse) : Completion
    }

    public sealed class Result {
        public data object Started : Result()
        public data object AlreadyOnWorldType : Result()
        public data object NotServed : Result()
        public data object AlreadySwitching : Result()
        public data class Busy(val message: String) : Result()
    }

    public fun isSwitching(player: Player): Boolean = inFlight.containsKey(player)

    public fun switch(
        player: Player,
        target: WorldType,
        served: Collection<WorldType>,
        firstVisitSpawn: CoordGrid? = null,
    ): Result {
        if (isSwitching(player) || player.worldTypeSwitching) return Result.AlreadySwitching
        if (player.worldType == target) return Result.AlreadyOnWorldType
        if (target !in served) return Result.NotServed
        if (!isRegistered(player) || player.accountId <= 0 || player.characterId <= 0 ||
            player.persistenceSuspended || player.persistenceInvalidated || unavailable(player) || player.isDelayed || hasRequiredWork(player)
        ) return Result.Busy("You can't switch world type until your current action has finished.")
        if (player.isLogoutBlocked()) return Result.Busy(player.logoutBlockedMessage())
        val attempt = Attempt(player, target, firstVisitSpawn, System.nanoTime())
        inFlight[player] = attempt
        player.persistenceSuspended = true
        player.worldTypeSwitching = true
        return Result.Started
    }

    /** Called on the game thread after player actions and before post-tick persistence. */
    public fun processPendingSwitches(now: Long = System.nanoTime()) {
        for (attempt in inFlight.values.toList()) {
            if (!validSession(attempt) || unavailable(attempt.player)) {
                abort(attempt, "session ended", notify = false)
            } else if (now - attempt.startedAt >= TIMEOUT_NANOS) {
                abort(attempt, "timed out")
            } else if (attempt.stage == Stage.PREPARING) {
                prepare(attempt)
            }
        }
        repeat(MAX_COMPLETIONS_PER_CYCLE) {
            val completion = completions.poll() ?: return
            val attempt = completion.attempt
            if (inFlight[attempt.player] !== attempt) return@repeat
            if (!validSession(attempt) || unavailable(attempt.player)) {
                abort(attempt, "session ended", notify = false)
                return@repeat
            }
            when (completion) {
                is Completion.Saved -> saved(attempt, completion.response)
                is Completion.Loaded -> loaded(attempt, completion.response)
            }
        }
    }

    private fun prepare(attempt: Attempt) {
        val player = attempt.player
        try {
            // Interface close hooks settle outgoing inventory state before the snapshot is taken.
            player.worldTypeSwitching = false
            player.clearPendingAction(eventBus)
            player.abortRoute()
            player.resetAnim()
            // Exit the outgoing save's areas while it is still installed. If a cleanup suspends
            // or queues required work, abort preparation and let that work finish in its mode.
            for (area in player.activeAreas) player.engineQueueAreaExit(area)
            player.activeAreas.clear()
            player.pendingAreas.clear()
            player.lastProcessedAreaCoord = CoordGrid.NULL
            engineQueues.process(player)
            player.worldTypeSwitching = true
            if (!validSession(attempt) || unavailable(player) || hasRequiredWork(player) || player.isDelayed || player.activeCoroutine?.isSuspended == true || player.isLogoutBlocked()) {
                abort(attempt, "outgoing action still pending")
                return
            }
            val checkpoint = accountManager.capture(player)
            attempt.checkpoint = checkpoint
            attempt.stage = Stage.SAVING
            accountManager.save(player, checkpoint) { completions += Completion.Saved(attempt, it) }
        } catch (error: Exception) {
            logger.error(error) { "Could not prepare world-type switch for character=${attempt.characterId}" }
            abort(attempt, "preparation failed")
        }
    }

    private fun saved(attempt: Attempt, response: AccountSaveResponse) {
        if (attempt.stage != Stage.SAVING) return
        if (response !is AccountSaveResponse.Success || response.player !== attempt.player) {
            abort(attempt, "save failed")
            return
        }
        attempt.stage = Stage.LOADING
        try {
            val accepted = accountManager.loadWorldTypeSwitch(attempt.username, attempt.target) {
                completions += Completion.Loaded(attempt, it)
            }
            if (!accepted) abort(attempt, "loader rejected the request")
        } catch (error: Exception) {
            logger.error(error) { "Could not queue world-type load for character=${attempt.characterId}" }
            abort(attempt, "load failed")
        }
    }

    private fun loaded(attempt: Attempt, response: AccountLoadResponse) {
        if (attempt.stage != Stage.LOADING) return
        if (response !is AccountLoadResponse.Ok || response.worldType != attempt.target ||
            response.account.accountId != attempt.accountId ||
            response.account.characterData.characterId != attempt.characterId
        ) {
            abort(attempt, "load failed or returned a different character")
            return
        }
        val player = attempt.player
        val checkpoint = checkNotNull(attempt.checkpoint)
        val origin = player.coords
        val destination: CoordGrid
        try {
            if (hasRequiredWork(player) || accountManager.capture(player) != checkpoint) {
                abort(attempt, "outgoing state changed while switching")
                return
            }
            clearWorldTypeScopedState(player)
            for (transform in response.transforms) transform.apply(player)
            player.worldType = attempt.target
            if (response.firstVisitToWorldType) player.newAccount = true
            if (realm.config.devMode) player.modLevel = Rights.ADMINISTRATOR
            val loadedCoords = player.coords
            player.coords = origin
            destination = if (response.firstVisitToWorldType) attempt.firstVisitSpawn ?: loadedCoords else loadedCoords
        } catch (error: Exception) {
            logger.error(error) { "Could not apply world-type load for character=${attempt.characterId}" }
            try {
                accountManager.restore(player, checkpoint)
            } catch (restoreError: Exception) {
                logger.error(restoreError) { "Could not restore world-type checkpoint for character=${attempt.characterId}" }
                // Do not persist a partially transformed save during forced logout.
                player.forceDisconnect = true
                player.persistenceInvalidated = true
                // No teleport has completed when a data transform fails. Keep registry teardown
                // at the registered outgoing tile even if a partial applier changed coordinates.
                player.coords = origin
                inFlight.remove(player)
                player.worldTypeSwitching = false
                return
            }
            abort(attempt, "target state could not be applied", notify = false)
            player.forceDisconnect = true
            return
        }
        player.queueList.clear()
        player.weakQueueList.clear()
        player.timerMap.clear()
        player.softTimerMap.clear()
        player.postInteractionSoftTimerMap?.clear()
        player.worldTypeSwitching = false
        try {
            telejumpTo(player, destination)
            player.clientCycle.forceSceneRebuild()
            player.regionRebuildPending = true
            eventBus.publish(SessionStateEvent.PrepareLogin(player))
            eventBus.publish(SessionStateEvent.Initialize(player))
            eventBus.publish(SessionStateEvent.Login(player))
            eventBus.publish(SessionStateEvent.EngineLogin(player))
        } catch (error: Exception) {
            // The target save is installed. Rolling it back after login effects could duplicate rewards.
            player.forceDisconnect = true
            logger.error(error) { "World-type login hook failed for character=${attempt.characterId}" }
        } finally {
            // The save has changed even if target login initialization failed. Outgoing global
            // activities must detach from this Player before another cycle can see target state.
            val changed = WorldTypeChangedEvent(player, from = attempt.source, to = attempt.target)
            // Cleanup listeners are independent owners. One failing owner cannot suppress the
            // remaining owners' notification of an already committed state change.
            for (listener in eventBus.unbound[WorldTypeChangedEvent::class.java].orEmpty()) {
                try {
                    listener(changed)
                } catch (error: Exception) {
                    player.forceDisconnect = true
                    logger.error(error) { "World-type change hook failed for character=${attempt.characterId}" }
                }
            }
            if (!player.forceDisconnect) player.mes("You are now playing ${attempt.target.label}.")
            player.persistenceSuspended = false
            inFlight.remove(player)
        }
    }

    private fun isRegistered(player: Player): Boolean =
        player.isSlotAssigned && players[player.slotId] === player

    private fun validSession(attempt: Attempt): Boolean = with(attempt.player) {
        isRegistered(this) && uid == attempt.uid && slotId == attempt.slot && client === attempt.client &&
            characterId == attempt.characterId && accountId == attempt.accountId && worldType == attempt.source
    }

    private fun unavailable(player: Player): Boolean =
        player.loggingOut || player.pendingLogout || player.manualLogout || player.forceDisconnect ||
            player.pendingShutdown || player.clientDisconnected.get()

    private fun hasRequiredWork(player: Player): Boolean {
        if (player.engineQueueList.isNotEmpty) return true
        val iterator = player.queueList.iterator() ?: return false
        try {
            while (iterator.hasNext()) {
                if (iterator.next().category != QueueCategory.LongDiscard.id) return true
            }
        } finally {
            iterator.cleanUp()
        }
        return false
    }

    private fun abort(attempt: Attempt, reason: String, notify: Boolean = true) {
        if (inFlight[attempt.player] !== attempt) return
        inFlight.remove(attempt.player)
        attempt.player.worldTypeSwitching = false
        attempt.player.persistenceSuspended = false
        logger.warn { "World-type switch cancelled: character=${attempt.characterId} reason=$reason" }
        if (notify && isRegistered(attempt.player)) attempt.player.mes("Could not switch world type right now. Please try again.")
    }

    private fun telejumpTo(player: Player, destination: CoordGrid) {
        if (!collision.isZoneValid(destination)) {
            logger.warn {
                "World-type switch destination is not a valid zone, keeping current coords: " +
                    "player=${player.username} dest=$destination"
            }
            return
        }
        PathingEntityCommon.telejump(player, collision, destination)
    }

    /**
     * The appliers merge rather than replace, so anything the incoming save has no row for has to be
     * dropped first - for inventories that would otherwise mean items crossing between modes.
     *
     * Only `Perm` scope inventories are touched, matching what the save pipeline persists.
     */
    private fun clearWorldTypeScopedState(player: Player) {
        for (inventory in player.invMap.values) {
            if (inventory.type.scope != InvScope.Perm) {
                continue
            }
            for (slot in inventory.indices) {
                inventory[slot] = null
            }
        }
        player.statMap.clear()
        player.vars.backing.clear()
        player.attr.clear()
    }

    private companion object {
        const val TIMEOUT_NANOS = 60_000_000_000L
        const val MAX_COMPLETIONS_PER_CYCLE = 100
    }
}
