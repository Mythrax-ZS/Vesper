package org.rsmod.content.other.treasuretrails

import dev.openrune.rscm.RSCM.asRSCM
import kotlin.math.abs
import kotlin.math.max
import org.rsmod.api.invtx.add
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.table.cluehelper.CluehelperClueHotcoldRow
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.map.CoordGrid

internal const val RELDO_ROW = "dbrow.cluehelper_cryptic_beginner_reldo"
internal const val BEGINNER_DEVICE = "obj.beginner_device"
internal var Player.nativeHotColdCoord by intVarp("varp.trail_hotcold_last_check_coord")

internal object BeginnerHotColdTargets {
    val ALL: List<CluehelperClueHotcoldRow> by lazy {
        CluehelperClueHotcoldRow.all().filter { it.difficulty == 0 }.also { rows ->
            check(rows.size == 5) { "Beginner hot/cold location pool changed; review native destinations" }
            check(rows.all { it.requirements == null && it.combatEncounter.isEmpty() }) {
                "Beginner hot/cold requirements or combat are unsupported"
            }
        }
    }

    fun get(rowId: Int): CluehelperClueHotcoldRow? = ALL.firstOrNull { it.rowId == rowId }
}

internal fun Player.assignBeginnerHotColdTarget(expected: BeginnerClueState, choose: () -> Int): ClueFailure? {
    if (!expected.valid || beginnerState() != expected || expected.row != RELDO_ROW.asRSCM()) {
        return ClueFailure.STALE
    }
    if (inv.physicalCount(BEGINNER_SCROLL) != 1) return ClueFailure.INPUT
    if (ClueOwnership.count(this, ClueTier.Beginner, activeOnly = true) != 1L) return ClueFailure.DUPLICATE
    if (beginnerHotColdTarget != 0) {
        return if (BeginnerHotColdTargets.get(beginnerHotColdTarget) != null) null else ClueFailure.STALE
    }
    val target = checkNotNull(BeginnerHotColdTargets.get(choose())) { "Unknown beginner hot/cold location" }
    beginnerHotColdTarget = target.rowId
    beginnerHotColdDistance = 0
    beginnerHotColdCoord = 0
    return null
}

/** Reldo's device is reusable; handing it out neither consumes nor advances the clue. */
internal fun Player.receiveBeginnerDevice(expected: BeginnerClueState): ClueFailure? {
    if (!expected.valid || beginnerState() != expected || expected.row != RELDO_ROW.asRSCM() ||
        BeginnerHotColdTargets.get(beginnerHotColdTarget) == null) return ClueFailure.STALE
    if (inv.physicalCount(BEGINNER_SCROLL) != 1) return ClueFailure.INPUT
    if (ClueOwnership.count(this, ClueTier.Beginner, activeOnly = true) != 1L) return ClueFailure.DUPLICATE
    if (ownsBeginnerDevice()) return null
    val transaction = invTransaction(inv) { add(select(inv), BEGINNER_DEVICE.asRSCM(), 1) }
    return if (transaction.failure) ClueFailure.SPACE else null
}

internal fun Player.ownsBeginnerDevice(): Boolean =
    inv.physicalCount(BEGINNER_DEVICE) > 0 ||
        (invMap["inv.bank"]?.physicalCount(BEGINNER_DEVICE) ?: 0) > 0

/** Measurements ignore the plane; digging must match the native destination's actual plane. */
internal fun hotColdDistance(from: CoordGrid, target: CoordGrid): Int =
    max(abs(from.x - target.x), abs(from.z - target.z))

internal fun hotColdTemperature(distance: Int): String {
    require(distance >= 0)
    return when {
        distance >= 500 -> "ice cold"
        distance >= 200 -> "very cold"
        distance >= 150 -> "cold"
        distance >= 100 -> "warm"
        distance >= 70 -> "hot"
        distance >= 30 -> "very hot"
        distance >= 4 -> "incredibly hot"
        else -> "visibly shaking"
    }
}

internal fun Player.atBeginnerHotColdTarget(targetRow: Int = beginnerHotColdTarget): Boolean {
    val target = BeginnerHotColdTargets.get(targetRow)?.target?.coord ?: return false
    return coords.level == target.level && hotColdDistance(coords, target) <= 3
}

/** No damage is applied by the beginner device. A stale held-item event changes no reading. */
internal fun Player.feelBeginnerDevice(slot: Int, expectedItem: InvObj): String? {
    if (inv[slot] !== expectedItem || expectedItem.id != BEGINNER_DEVICE.asRSCM()) return null
    val state = beginnerState()
    if (!state.valid || state.row != RELDO_ROW.asRSCM() || inv.physicalCount(BEGINNER_SCROLL) != 1) {
        return "The strange device is inactive."
    }
    val target = BeginnerHotColdTargets.get(beginnerHotColdTarget) ?: return "The strange device is inactive."
    val distance = hotColdDistance(coords, target.target.coord)
    val previous = beginnerHotColdDistance - 1
    beginnerHotColdDistance = distance + 1
    beginnerHotColdCoord = coords.packed
    nativeHotColdCoord = coords.packed
    val change = when {
        previous < 0 -> ""
        distance < previous -> ", and warmer than last time"
        distance > previous -> ", but colder than last time"
        else -> ", and the same temperature as last time"
    }
    return "The device is ${hotColdTemperature(distance)}$change."
}
