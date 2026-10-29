package org.rsmod.api.account.autosave

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.types.InvScope
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.rsmod.api.account.AccountManager
import org.rsmod.api.account.saver.request.AccountSaveResponse
import org.rsmod.api.attr.AttributeKey
import org.rsmod.api.attr.AttributeMap
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerPersistenceHints

/**
 * Queues background character saves (same pipeline as logout) on a timer and when important state
 * changes. Coalesces overlapping requests per player while a save is already in flight.
 */
@Singleton
public class PlayerAutosaveOrchestrator
@Inject
constructor(
    private val accountManager: AccountManager,
) {
    private val logger = InlineLogger()

    private val queuedSave = IdentityHashMap<Player, Any>()
    private val dirty: MutableSet<Player> = Collections.newSetFromMap(IdentityHashMap())
    private val completions = ConcurrentLinkedQueue<Completion>()
    private val retryAfter = IdentityHashMap<Player, Int>()

    private data class Completion(val player: Player, val token: Any, val response: AccountSaveResponse)

    private var periodicCounter: Int = PERIODIC_INTERVAL_CYCLES

    init {
        PlayerPersistenceHints.bind(this::onPersistenceRelevantChange)
        AttributeMap.persistenceMutationSink = { key ->
            onPersistentAttrMutated(key)
        }
    }

    /**
     * Run once per game tick after inventory updates (while [InvScope.Perm] inventories may still
     * report [org.rsmod.game.inv.Inventory.hasModifiedSlots]).
     */
    public fun processEndOfTick(players: Iterable<Player>) {
        processCompletions()
        for (player in players) {
            if (player.canProcess && player.invMap.values.any { it.type.scope == InvScope.Perm && it.hasModifiedSlots() }) {
                onPersistenceRelevantChange(player)
            }
        }
        if (--periodicCounter <= 0) {
            periodicCounter = PERIODIC_INTERVAL_CYCLES
            for (player in players) {
                if (player.canProcess && player.persistenceSaveEligible()) dirty += player
            }
        }
        for (player in dirty.toList()) {
            if (player.loggingOut || player.pendingLogout || !player.isSlotAssigned) {
                dirty.remove(player)
                retryAfter.remove(player)
                continue
            }
            if (player.canProcess && player.persistenceSaveEligible() && player !in queuedSave &&
                player.currentMapClock >= retryAfter.getOrDefault(player, 0)) {
                requestBackgroundSave(player)
            }
        }
    }

    private fun processCompletions() {
        while (true) {
            val completion = completions.poll() ?: break
            val (player, token, response) = completion
            if (queuedSave[player] !== token) continue
            queuedSave.remove(player)
            when (response) {
                is AccountSaveResponse.Success -> {}
                is AccountSaveResponse.InvalidState -> dirty.remove(player)
                is AccountSaveResponse.ExcessiveRetries,
                is AccountSaveResponse.InternalShutdownError -> {
                    dirty += player
                    retryAfter[player] = player.currentMapClock + PERIODIC_INTERVAL_CYCLES
                    logger.error { "Background autosave failed for ${player.displayName}: ${response::class.simpleName}" }
                }
            }
        }
    }

    private fun onPersistentAttrMutated(@Suppress("UNUSED_PARAMETER") key: AttributeKey<*>) {
        val player = PlayerPersistenceHints.activeOrNull() ?: return
        onPersistenceRelevantChange(player)
    }

    private fun onPersistenceRelevantChange(player: Player) {
        if (player.persistenceSaveEligible()) dirty += player
    }

    private fun requestBackgroundSave(player: Player) {
        val token = Any()
        queuedSave[player] = token
        dirty.remove(player)
        retryAfter.remove(player)
        try {
            accountManager.save(player) { response -> completions += Completion(player, token, response) }
        } catch (e: Exception) {
            queuedSave.remove(player)
            dirty += player
            retryAfter[player] = player.currentMapClock + PERIODIC_INTERVAL_CYCLES
            logger.error(e) { "Could not capture background save for ${player.displayName}" }
        }
    }

    private companion object {
        private const val PERIODIC_INTERVAL_CYCLES: Int = 500
    }
}

private fun Player.persistenceSaveEligible(): Boolean =
    accountId > 0 && characterId > 0 && processedMapClock > 0 && !persistenceSuspended && !persistenceInvalidated
