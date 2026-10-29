package org.rsmod.content.skills.hunter

import dev.openrune.types.NpcMode
import dev.openrune.types.ObjectServerType
import dev.openrune.types.hunt.HuntVis
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.IdentityHashMap
import org.rsmod.annotations.InternalApi
import org.rsmod.api.hunt.Hunt
import org.rsmod.api.invtx.add
import org.rsmod.api.invtx.delete
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.api.npc.isValidTarget
import org.rsmod.api.player.interact.PlayerDropTimers
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.player.stat.statRandom
import org.rsmod.api.random.CoreRandom
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcUid
import org.rsmod.game.inv.InvObj
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.map.collision.isWalkBlocked
import org.rsmod.game.obj.Obj
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.loc.LocLayerConstants

internal enum class SnarePhase { OPEN, APPROACHING, LANDING, CATCHING, ESCAPING, FULL, BROKEN, COLLAPSING }

internal enum class SnareAction { CHECK, RESET, DISMANTLE }

internal enum class SnareFailure { LEVEL, LIMIT, TILE, INPUT, OWNER, STALE, BUSY, SPACE }

internal class BirdSnare(val owner: Player, var loc: LocInfo, now: Int) {
    val worldType = owner.worldType
    var phase = SnarePhase.OPEN
    var expiresAt = now + BirdSnares.TRAP_LIFETIME
    var nextSearch = now + BirdSnares.SEARCH_INTERVAL
    var transitionAt = 0
    var bird: Npc? = null
    var birdUid = NpcUid.NULL
    var birdMode: NpcMode? = null
    var catch: BirdSnareData? = null
    var feathers = 0
    var groundIssued = false
}

@Singleton
@OptIn(InternalApi::class)
class BirdSnareManager @Inject constructor(
    private val clock: MapClock,
    private val hunt: Hunt,
    private val locs: LocRepository,
    private val npcs: NpcRepository,
    private val objs: ObjRepository,
    private val world: WorldRepository,
    private val collision: CollisionFlagMap,
    private val walkValidator: RayCastValidator,
    @CoreRandom private val random: GameRandom,
    private val invisibleLevels: InvisibleLevels,
    private val xpModifiers: XpModifiers,
) {
    private val traps = linkedMapOf<CoordGrid, BirdSnare>()
    private val rollbackLocs = linkedMapOf<CoordGrid, LocInfo>()
    private val reservations = IdentityHashMap<Npc, BirdSnare>()

    internal fun count(player: Player): Int = traps.values.count { it.owner === player }

    internal fun trapAt(coords: CoordGrid): BirdSnare? = traps[coords]

    internal fun limit(player: Player): Int = BirdSnares.TRAP_LIMITS
        .filter { (level, _) -> player.stat("stat.hunter") >= level }
        .maxOfOrNull { it.second } ?: 0

    private fun placementFailure(player: Player, coords: CoordGrid): SnareFailure? = when {
        player.stat("stat.hunter") < BirdSnares.BIRDS.minOf { it.level } -> SnareFailure.LEVEL
        count(player) >= limit(player) -> SnareFailure.LIMIT
        player.coords != coords || player.loggingOut -> SnareFailure.STALE
        traps.containsKey(coords) || rollbackLocs.containsKey(coords) || collision.isWalkBlocked(coords) ||
            locs.findAll(coords).any { it.layer == LocLayerConstants.GROUND } -> SnareFailure.TILE
        else -> null
    }

    internal fun canLay(player: Player, slot: Int, expected: InvObj, coords: CoordGrid): SnareFailure? {
        if (player.inv[slot] != expected || expected.id != BirdSnares.item(BirdSnares.SNARE).id || expected.count <= 0) return SnareFailure.INPUT
        return placementFailure(player, coords)
    }

    internal fun canLayGround(player: Player, source: Obj): SnareFailure? {
        if (source.type != BirdSnares.item(BirdSnares.SNARE).id || source.count <= 0) return SnareFailure.INPUT
        if (!source.isVisibleTo(player) ||
            source.despawnCycle != Obj.NO_TIMER && source.despawnCycle <= clock.cycle ||
            objs.findAll(source.coords).none { it === source }
        ) return SnareFailure.STALE
        return placementFailure(player, source.coords)
    }

    internal fun layGround(player: Player, source: Obj): SnareFailure? {
        canLayGround(player, source)?.let { return it }
        val loc = loc(source.coords, BirdSnares.loc(BirdSnares.OPEN))
        if (!locs.add(loc, Int.MAX_VALUE)) return SnareFailure.TILE
        if (!objs.take(source, 1)) {
            if (!locs.del(loc, Int.MAX_VALUE)) rollbackLocs[loc.coords] = loc
            return SnareFailure.STALE
        }
        traps[source.coords] = BirdSnare(player, loc, clock.cycle)
        world.locAnim(loc, BirdSnares.SET_ANIM)
        return null
    }

    internal fun lay(player: Player, slot: Int, expected: InvObj, coords: CoordGrid): SnareFailure? {
        canLay(player, slot, expected, coords)?.let { return it }
        val transfer = player.invTransaction(player.inv, autoCommit = false) {
            delete(select(player.inv), expected.id, 1, slot = slot)
        }
        if (transfer.failure) return SnareFailure.INPUT
        val loc = loc(coords, BirdSnares.loc(BirdSnares.OPEN))
        if (!locs.add(loc, Int.MAX_VALUE)) return SnareFailure.TILE
        traps[coords] = BirdSnare(player, loc, clock.cycle)
        transfer.commitAll()
        world.locAnim(loc, BirdSnares.SET_ANIM)
        return null
    }

    internal fun validate(player: Player, expected: LocInfo, action: SnareAction): SnareFailure? {
        val trap = traps[expected.coords] ?: return SnareFailure.STALE
        if (trap.owner !== player) return SnareFailure.OWNER
        if (trap.loc != expected || !exists(trap) || player.loggingOut || clock.cycle >= trap.expiresAt ||
            player.coords.level != expected.coords.level || player.coords.chebyshevDistance(expected.coords) > 1
        ) return SnareFailure.STALE
        val allowed = when (action) {
            SnareAction.CHECK -> trap.phase == SnarePhase.FULL
            SnareAction.RESET -> trap.phase == SnarePhase.FULL || trap.phase == SnarePhase.BROKEN
            SnareAction.DISMANTLE -> trap.phase == SnarePhase.OPEN || trap.phase == SnarePhase.BROKEN
        }
        return if (allowed) null else SnareFailure.BUSY
    }

    internal fun interact(player: Player, expected: LocInfo, action: SnareAction): SnareFailure? {
        validate(player, expected, action)?.let { return it }
        val trap = traps.getValue(expected.coords)
        val caught = trap.catch
        val transfer = if (action == SnareAction.RESET && caught == null) null else player.invTransaction(player.inv, autoCommit = false) {
            val inventory = select(player.inv)
            if (action != SnareAction.RESET) add(inventory, BirdSnares.item(BirdSnares.SNARE).id, 1)
            if (caught != null) {
                add(inventory, BirdSnares.item(BirdSnares.BONES).id, 1)
                add(inventory, BirdSnares.item(BirdSnares.MEAT).id, 1)
                add(inventory, caught.feather.id, trap.feathers)
            }
        }
        if (transfer?.failure == true) return SnareFailure.SPACE
        if (action == SnareAction.RESET) {
            if (!replace(trap, BirdSnares.loc(BirdSnares.OPEN))) return SnareFailure.STALE
            trap.phase = SnarePhase.OPEN
            trap.catch = null
            trap.feathers = 0
            trap.expiresAt = clock.cycle + BirdSnares.TRAP_LIFETIME
            trap.nextSearch = clock.cycle + BirdSnares.SEARCH_INTERVAL
        } else {
            if (!locs.del(trap.loc, Int.MAX_VALUE)) return SnareFailure.STALE
            traps.remove(trap.loc.coords)
        }
        transfer?.commitAll()
        if (caught != null) {
            player.statAdvance("stat.hunter", caught.row.xpTenths / 10.0 * xpModifiers.get(player, "stat.hunter"))
        }
        if (action == SnareAction.RESET) world.locAnim(trap.loc, BirdSnares.SET_ANIM)
        return null
    }

    internal fun tick() {
        for ((coords, loc) in rollbackLocs.toMap()) {
            if (locs.findExact(coords, loc.shape) != loc || locs.del(loc, Int.MAX_VALUE)) {
                rollbackLocs.remove(coords, loc)
            }
        }
        for (trap in traps.values.toList()) {
            if (trap.phase == SnarePhase.COLLAPSING || trap.owner.loggingOut || clock.cycle >= trap.expiresAt) {
                collapse(trap)
                continue
            }
            if (!exists(trap)) {
                release(trap)
                traps.remove(trap.loc.coords)
                continue
            }
            when (trap.phase) {
                SnarePhase.OPEN -> if (clock.cycle >= trap.nextSearch) search(trap)
                SnarePhase.APPROACHING -> approach(trap)
                SnarePhase.LANDING -> if (clock.cycle >= trap.transitionAt) attempt(trap)
                SnarePhase.CATCHING -> if (clock.cycle >= trap.transitionAt) finishCatch(trap)
                SnarePhase.ESCAPING -> if (clock.cycle >= trap.transitionAt) finishFailure(trap)
                SnarePhase.FULL, SnarePhase.BROKEN, SnarePhase.COLLAPSING -> Unit
            }
        }
    }

    internal fun logout(player: Player) {
        traps.values.filter { it.owner === player }.toList().forEach(::collapse)
    }

    internal fun invalidateBird(bird: Npc) {
        val trap = reservations.remove(bird) ?: return
        trap.bird = null
        trap.birdUid = NpcUid.NULL
        trap.birdMode = null
        if (trap.phase == SnarePhase.APPROACHING || trap.phase == SnarePhase.LANDING) reopen(trap)
    }

    private fun search(trap: BirdSnare) {
        trap.nextSearch = clock.cycle + BirdSnares.SEARCH_INTERVAL
        if (trap.owner.coords == trap.loc.coords) return
        val level = trap.owner.stat("stat.hunter")
        var chosen: Npc? = null
        var eligible = 0
        for (npc in hunt.findNpcs(trap.loc.coords, BirdSnares.SEARCH_RANGE, HuntVis.LineOfWalk)) {
            val bird = BirdSnares.BY_NPC[npc.id] ?: continue
            if (!npc.isValidTarget() || npc.hasInteraction() || npc.movementLocked ||
                reservations.containsKey(npc) || level < bird.level ||
                !walkValidator.hasLineOfWalk(npc.coords, trap.loc.coords)
            ) continue
            eligible++
            if (random.of(eligible) == 0) chosen = npc
        }
        val bird = chosen ?: return
        reservations[bird] = trap
        trap.bird = bird
        trap.birdUid = bird.uid
        trap.birdMode = bird.mode
        trap.phase = SnarePhase.APPROACHING
        bird.mode = NpcMode.None
        bird.walk(trap.loc.coords)
    }

    private fun approach(trap: BirdSnare) {
        val bird = trap.bird ?: return reopen(trap)
        if (!bird.isValidTarget() || bird.uid != trap.birdUid || trap.owner.coords == trap.loc.coords ||
            bird.mode != NpcMode.None || bird.hasInteraction() ||
            bird.coords.level != trap.loc.coords.level ||
            bird.coords.chebyshevDistance(trap.loc.coords) > BirdSnares.SEARCH_RANGE
        ) return reopen(trap)
        if (bird.coords != trap.loc.coords) {
            val exhausted = bird.routeRequest == null && bird.routeDestination.isEmpty()
            val reachable = walkValidator.hasLineOfWalk(bird.coords, trap.loc.coords)
            if (exhausted || !reachable) reopen(trap)
            return
        }
        bird.resetMovement()
        bird.anim(BirdSnares.LAND_ANIM)
        trap.phase = SnarePhase.LANDING
        trap.transitionAt = clock.cycle + BirdSnares.animationTicks(BirdSnares.LAND_ANIM)
    }

    private fun attempt(trap: BirdSnare) {
        val bird = trap.bird ?: return reopen(trap)
        if (!bird.isValidTarget() || bird.uid != trap.birdUid || bird.coords != trap.loc.coords ||
            trap.owner.coords == trap.loc.coords || bird.mode != NpcMode.None || bird.hasInteraction()
        ) return reopen(trap)
        val data = BirdSnares.BY_NPC[bird.id] ?: return reopen(trap)
        if (trap.owner.stat("stat.hunter") < data.level) return reopen(trap)
        val caught = trap.owner.statRandom(random, "stat.hunter", data.row.successLow, data.row.successHigh, invisibleLevels)
        if (caught) {
            if (!replace(trap, data.trapping)) return reopen(trap)
            trap.catch = data
            trap.feathers = random.of(data.row.feathersMin, data.row.feathersMax)
            trap.phase = SnarePhase.CATCHING
            trap.transitionAt = clock.cycle + BirdSnares.animationTicks(BirdSnares.CATCH_ANIM)
            release(trap)
            npcs.despawn(bird, bird.type.respawnRate)
        } else {
            if (!replace(trap, BirdSnares.loc(BirdSnares.FAILING))) return reopen(trap)
            bird.anim(BirdSnares.LEAVE_ANIM)
            trap.phase = SnarePhase.ESCAPING
            trap.transitionAt = clock.cycle + BirdSnares.animationTicks(BirdSnares.LEAVE_ANIM)
        }
    }

    private fun finishCatch(trap: BirdSnare) {
        val caught = trap.catch ?: return
        if (!replace(trap, caught.caught)) return
        trap.phase = SnarePhase.FULL
        trap.expiresAt = clock.cycle + BirdSnares.TRAP_LIFETIME
    }

    private fun finishFailure(trap: BirdSnare) {
        if (!replace(trap, BirdSnares.loc(BirdSnares.BROKEN))) return
        release(trap)
        trap.phase = SnarePhase.BROKEN
        trap.expiresAt = clock.cycle + BirdSnares.TRAP_LIFETIME
    }

    private fun reopen(trap: BirdSnare) {
        release(trap)
        trap.phase = SnarePhase.OPEN
        trap.nextSearch = clock.cycle + BirdSnares.SEARCH_INTERVAL
    }

    private fun release(trap: BirdSnare) {
        val bird = trap.bird ?: return
        if (reservations[bird] === trap) {
            reservations.remove(bird)
            if (bird.isSlotAssigned && bird.uid == trap.birdUid && !bird.hidden &&
                bird.mode == NpcMode.None && !bird.hasInteraction()
            ) {
                bird.resetMovement()
                bird.resetAnim()
                bird.mode = trap.birdMode
            }
        }
        trap.bird = null
        trap.birdUid = NpcUid.NULL
        trap.birdMode = null
    }

    private fun collapse(trap: BirdSnare) {
        release(trap)
        trap.phase = SnarePhase.COLLAPSING
        if (!trap.groundIssued) {
            val type = BirdSnares.item(BirdSnares.SNARE)
            val timers = PlayerDropTimers.resolve(type)
            val snare = Obj.fromOwner(trap.owner, trap.loc.coords, BirdSnares.SNARE, 1, worldType = trap.worldType)
            if (!objs.add(snare, timers.duration, timers.reveal)) return
            trap.groundIssued = true
            trap.catch = null
        }
        if (exists(trap) && !locs.del(trap.loc, Int.MAX_VALUE)) return
        traps.remove(trap.loc.coords)
    }

    private fun exists(trap: BirdSnare): Boolean =
        locs.findExact(trap.loc.coords, trap.loc.shape) == trap.loc

    private fun replace(trap: BirdSnare, type: ObjectServerType): Boolean {
        if (!exists(trap)) return false
        val next = loc(trap.loc.coords, type)
        if (!locs.add(next, Int.MAX_VALUE)) return false
        trap.loc = next
        return true
    }

    private fun loc(coords: CoordGrid, type: ObjectServerType): LocInfo = LocInfo(
        LocLayerConstants.GROUND, coords, LocEntity(type.id, LocShape.CentrepieceStraight.id, LocAngle.West.id),
    )
}
