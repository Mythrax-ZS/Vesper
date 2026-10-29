package org.rsmod.content.skills.agility

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.varp.VarpServerType
import jakarta.inject.Inject
import java.time.Instant
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.table.agility.AgilityCoursesRow
import org.rsmod.api.table.agility.AgilityMarkSpawnsRow
import org.rsmod.content.interfaces.collectionlog.CollectionLog
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.game.obj.Obj

internal var Player.agilityMarkNextMinute by intVarp("varp.agility_mark_next_minute")
internal var Player.agilityMarkLastTick by intVarp("varp.agility_mark_last_tick")

internal val AgilityMarkSpawnsRow.countType: VarpServerType
    get() = checkNotNull(ServerCacheManager.getVarp(countVar))

internal val AgilityMarkSpawnsRow.lifetimeType: VarpServerType
    get() = checkNotNull(ServerCacheManager.getVarp(lifetimeVar))

class AgilityMarks @Inject constructor(
    private val objects: ObjRepository,
    private val random: GameRandom,
    private val clock: MapClock,
) {
    private val spawns by lazy { AgilityMarkSpawnsRow.all() }

    internal fun completeLap(
        player: Player,
        course: AgilityCoursesRow,
        minute: Int = (Instant.now().epochSecond / SECONDS_PER_MINUTE).toInt(),
    ): Boolean {
        updateLifetimes(player)
        val numerator = course.markNumerator ?: return false
        val denominator = course.markDenominator ?: return false
        if (minute < player.agilityMarkNextMinute) return false
        val locations = spawns.filter { it.course.rowId == course.rowId }
        if (locations.isEmpty()) return false
        val baseLevel = player.statBase("stat.agility")
        val rollSize = markRollSize(baseLevel, course.level, denominator)
        if (random.of(rollSize) >= numerator) return false
        val location = random.pick(locations)
        if (!spawn(player, location)) return false
        player.agilityMarkNextMinute = minute + COOLDOWN_MINUTES
        CollectionLog.grant(player, MARK)
        return true
    }

    internal fun spawn(player: Player, location: AgilityMarkSpawnsRow): Boolean {
        updateLifetimes(player)
        val oldCount = player.vars[location.countType]
        if (oldCount == Int.MAX_VALUE) return false
        val obj = Obj.fromPersonal(player, location.coord, MARK, 1)
        if (!objects.add(obj, LIFETIME_TICKS, reveal = Int.MAX_VALUE)) return false
        VarPlayerIntMapSetter.set(player, location.countType, oldCount + 1)
        VarPlayerIntMapSetter.set(player, location.lifetimeType, LIFETIME_TICKS)
        player.agilityMarkLastTick = clock.cycle + 1
        player.softTimerMap.schedule(TIMER, clock.cycle, 1)
        return true
    }

    internal fun login(player: Player) {
        player.agilityMarkLastTick = clock.cycle + 1
        for (location in spawns) {
            ownStack(player, location)?.let { objects.del(it) }
            val count = player.vars[location.countType]
            val remaining = player.vars[location.lifetimeType].coerceIn(0, LIFETIME_TICKS)
            if (count <= 0 || remaining == 0) {
                clear(player, location)
                continue
            }
            VarPlayerIntMapSetter.set(player, location.lifetimeType, remaining)
            val obj = Obj.fromPersonal(player, location.coord, MARK, count)
            if (!objects.add(obj, remaining, reveal = Int.MAX_VALUE)) clear(player, location)
        }
        if (hasMarks(player)) player.softTimerMap.schedule(TIMER, clock.cycle, 1)
    }

    internal fun logout(player: Player) {
        updateLifetimes(player)
        for (location in spawns) ownStack(player, location)?.let { objects.del(it) }
        player.agilityMarkLastTick = 0
        player.clearSoftTimer(TIMER)
    }

    internal fun updateLifetimes(player: Player) {
        val previous = player.agilityMarkLastTick
        val elapsed = if (previous == 0) 0 else (clock.cycle - (previous - 1)).coerceAtLeast(0)
        player.agilityMarkLastTick = clock.cycle + 1
        for (location in spawns) {
            if (player.vars[location.countType] <= 0) continue
            val remaining = (player.vars[location.lifetimeType] - elapsed).coerceAtLeast(0)
            val obj = ownStack(player, location)
            if (remaining == 0 || obj == null || obj.count != player.vars[location.countType]) {
                obj?.let { objects.del(it) }
                clear(player, location)
            } else {
                VarPlayerIntMapSetter.set(player, location.lifetimeType, remaining)
            }
        }
        if (!hasMarks(player)) player.clearSoftTimer(TIMER)
    }

    internal fun validateTake(player: Player, obj: Obj): String? {
        if (obj.personalReceiverId == null) return null
        updateLifetimes(player)
        val location = spawns.singleOrNull { it.coord == obj.coords } ?: return UNAVAILABLE
        if (obj.personalReceiverId != player.uuid || ownStack(player, location) !== obj) return UNAVAILABLE
        if (player.vars[location.countType] != obj.count || player.vars[location.lifetimeType] <= 0) {
            return UNAVAILABLE
        }
        return null
    }

    internal fun taken(player: Player, obj: Obj) {
        if (obj.personalReceiverId != player.uuid) return
        val location = spawns.singleOrNull { it.coord == obj.coords } ?: return
        clear(player, location)
        if (!hasMarks(player)) player.clearSoftTimer(TIMER)
    }

    internal fun ownStack(player: Player, location: AgilityMarkSpawnsRow): Obj? =
        objects.findAll(location.coord).singleOrNull {
            it.type == MARK.asRSCM() && it.personalReceiverId == player.uuid && it.belongsTo(player.worldType)
        }

    private fun hasMarks(player: Player): Boolean = spawns.any { player.vars[it.countType] > 0 }

    private fun clear(player: Player, location: AgilityMarkSpawnsRow) {
        VarPlayerIntMapSetter.set(player, location.countType, 0)
        VarPlayerIntMapSetter.set(player, location.lifetimeType, 0)
    }

    companion object {
        internal const val MARK = "obj.grace"
        internal const val TIMER = "timer.agility_marks"
        internal const val LIFETIME_TICKS = 1000
        private const val SECONDS_PER_MINUTE = 60
        private const val COOLDOWN_MINUTES = 3
        private const val UNAVAILABLE = "That mark of grace is no longer available."

        internal fun markRollSize(baseLevel: Int, courseLevel: Int, denominator: Int): Int {
            require(denominator > 0 && denominator <= Int.MAX_VALUE / 5)
            return denominator * if (baseLevel >= courseLevel + 20) 5 else 1
        }
    }
}
