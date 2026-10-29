package org.rsmod.content.other.treasuretrails

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.enums.TreasureTrailsBeginnerEnums
import org.rsmod.api.invtx.add
import org.rsmod.api.invtx.delete
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.intVarp
import org.rsmod.content.interfaces.collectionlog.CollectionLog
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.map.CoordGrid
import org.rsmod.objtx.TransactionResult

internal const val BEGINNER_PENDING = "inv.vesper_beginner_reward_pending"
internal const val BEGINNER_DISPLAY = "inv.vesper_beginner_reward_display"
internal const val NATIVE_REWARD_INV = "inv.trail_rewardinv"
internal var Player.beginnerCasketsOpened by intVarp("varp.completed_clues5")
internal var Player.totalCasketsOpened by intVarp("varp.completed_clues_total")

internal enum class CasketFailure { INPUT, PENDING, ACCOUNT_AGE_UNVERIFIED, ENTRANA, COUNTER_LIMIT, SPACE }
internal data class CasketOpened(val failure: CasketFailure?, val rewards: List<InvObj> = emptyList())

/**
 * RuneLite's Entrana surface region pair. The dungeon is expressly exempt in live OSRS.
 * This geographic boundary is sourced; Jagex's exact server-side boundary/instance rules remain
 * unverified. Coordinates are not entity IDs. See README evidence and limitations.
 */
internal fun onEntranaSurface(coords: CoordGrid): Boolean =
    coords.x in 2752..2879 && coords.z in 3328..3391

/** No suspension: inventory, permanent counters and collection log update within one game action. */
internal fun Player.openBeginnerCasket(
    slot: Int,
    expected: InvObj,
    roll: () -> List<InvObj>,
): CasketOpened {
    if (inv[slot] !== expected || expected.id != BEGINNER_CASKET.asRSCM()) {
        return CasketOpened(CasketFailure.INPUT)
    }
    val pending = invMap.getOrPut(BEGINNER_PENDING)
    if (pending.isNotEmpty()) return CasketOpened(CasketFailure.PENDING)
    if (onEntranaSurface(coords)) return CasketOpened(CasketFailure.ENTRANA)
    // The published anti-bot rule excludes uniques below ten hours, but its replacement roll is
    // undocumented. Preserve the casket instead of fabricating that distribution. Ten played
    // hours guarantees both minimum playtime and minimum account age; this is a temporary gap.
    if (vars["varp.playtime"] < 60_000) return CasketOpened(CasketFailure.ACCOUNT_AGE_UNVERIFIED)
    if (beginnerCasketsOpened == Int.MAX_VALUE || totalCasketsOpened == Int.MAX_VALUE) {
        return CasketOpened(CasketFailure.COUNTER_LIMIT)
    }
    val nextCount = beginnerCasketsOpened + 1
    val rewards = roll() + beginnerCaseAwards(nextCount)
    require(rewards.isNotEmpty() && rewards.size <= pending.size)
    require(rewards.all { it.count > 0 })
    val transaction = invTransaction(inv, pending) {
        delete(select(inv), BEGINNER_CASKET.asRSCM(), 1, slot = slot)
        val target = select(pending)
        for (reward in rewards) add(target, reward.id, reward.count, vars = reward.vars)
    }
    if (transaction.failure) return CasketOpened(CasketFailure.SPACE)
    beginnerCasketsOpened = nextCount
    totalCasketsOpened += 1
    if (nextCount >= 600 && vars["varbit.emote_explore"] == 0) {
        VarPlayerIntMapSetter.set(this, "varbit.emote_explore", 1)
    }
    // Log at opening, including loot which falls to the ground; never again during recovery.
    for (reward in rewards) CollectionLog.grant(this, reward.id, reward.count)
    return CasketOpened(null, rewards)
}

private fun Player.beginnerCaseAwards(completed: Int): List<InvObj> =
    TreasureTrailsBeginnerEnums.treasure_trails_beginner_cases.mapNotNull { entry ->
        val item = entry.key
        val threshold = checkNotNull(entry.value)
        val symbol = RSCM.getReverseMapping(RSCMType.OBJ, item.id)
        val bit = "varbit.${symbol.removePrefix("obj.")}"
        if (completed >= threshold && vars[bit] == 0 && !ownsCase(item.id)) InvObj(item) else null
    }

private fun Player.ownsCase(id: Int): Boolean =
    inv.objs.any { it?.id == id } || worn.objs.any { it?.id == id } ||
        sequenceOf("inv.bank", "inv.gravestone", "inv.death_permanent", BEGINNER_PENDING)
            .mapNotNull { invMap[it] }.any { storage -> storage.objs.any { it?.id == id } }

/**
 * Transfer one saved reward at a time. [drop] must synchronously return true iff all its input was
 * registered. No external call/suspension occurs between accepted ground placement and commit.
 * Rejected placement keeps the full entry in the journal, including the portion which could fit.
 */
internal fun Player.deliverBeginnerRewards(drop: (InvObj) -> Boolean): Boolean {
    val pending = invMap[BEGINNER_PENDING] ?: return true
    for (slot in pending.indices) {
        val reward = pending[slot] ?: continue
        val probe = invAdd(inv, reward.id, reward.count, vars = reward.vars,
            strict = false, autoCommit = false)
        if (probe.failure && probe.err != TransactionResult.NotEnoughSpace) return false
        val fits = if (probe.success) probe.completed() else 0
        val overflow = reward.count - fits
        val transfer = invTransaction(pending, inv, autoCommit = false) {
            delete(select(pending), reward.id, reward.count, slot = slot)
            if (fits > 0) add(select(inv), reward.id, fits, vars = reward.vars)
        }
        if (transfer.failure) return false
        if (overflow > 0 && !drop(reward.copy(count = overflow))) return false
        transfer.commitAll()
    }
    return true
}
