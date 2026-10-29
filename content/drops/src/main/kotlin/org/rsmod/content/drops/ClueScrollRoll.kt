package org.rsmod.content.drops

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dtx.core.ArgMap
import dtx.core.RollResult
import dtx.core.RollableHooks
import dtx.impl.chance.ChanceRollable
import dtx.impl.chance.RateBoosts
import dtx.rs.RSPrerollTableBuilder
import kotlin.math.floor
import org.rsmod.api.area.checker.isInWilderness
import org.rsmod.api.droptable.DropRateBoosts
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.KillRollContext
import org.rsmod.api.droptable.dropRollableWithPlayerCondition
import org.rsmod.api.droptable.wearingImbuedRingOfWealth
import org.rsmod.game.entity.Player

public fun RSPrerollTableBuilder<Player, DropRollItem>.clueScrollRoll(
    obj: String,
    denominator: Int,
    numerator: Int = 1,
    item: DropRollItem = DropRollItem(obj, 1, transformObj = { it.clueScrollTransformObj(obj) }),
    npcs: List<String> = emptyList(),
) {
    val tier = requireNotNull(clueScrollTierForObj(obj)) { "Unknown clue tier: $obj" }
    require(!obj.contains("casket") && item.obj == obj && numerator in 1..denominator)
    addEntry(ClueScrollChanceRollable(tier, numerator, denominator, item, boostScope, npcs.map { it.asRSCM(RSCMType.NPC) }.toSet()))
}

internal class ClueScrollChanceRollable(
    private val tier: ClueScrollTier,
    private val numerator: Int,
    private val denominator: Int,
    item: DropRollItem,
    private val boosted: Boolean,
    private val npcs: Set<Int>,
) : ChanceRollable<Player, DropRollItem>,
    RollableHooks<Player, DropRollItem> by RollableHooks.Default() {
    override val rollable = dropRollableWithPlayerCondition(item)
    override val chance: Double = 100.0 * numerator / denominator

    override fun chanceFor(target: Player, otherArgs: ArgMap): Double {
        val effective = target.clueScrollDenominator(tier, denominator, otherArgs)
        val clueBoost = 1.0 + DropRateBoosts.clueScrollBoostPercent(target, otherArgs).coerceAtLeast(0) / 100.0
        val worldBoost = if (boosted) RateBoosts.multiplierFor(target, otherArgs) else 1.0
        val adjusted = floor(effective / (clueBoost * worldBoost)).toInt().coerceAtLeast(1)
        return (100.0 * numerator / adjusted).coerceAtMost(100.0)
    }

    override fun includeInRoll(onTarget: Player, otherArgs: ArgMap): Boolean =
        (npcs.isEmpty() || otherArgs[KillRollContext.npc]?.type?.id in npcs) && rollable.includeInRoll(onTarget, otherArgs)

    override fun selectResult(target: Player, otherArgs: ArgMap): RollResult<DropRollItem> =
        rollable.roll(target, otherArgs)
}

public fun Player.clueScrollDenominator(tier: ClueScrollTier, base: Int, args: ArgMap): Int {
    require(base > 0)
    val claimed = when (tier) {
        ClueScrollTier.Beginner -> false
        ClueScrollTier.Easy -> hasUnlockedEasyCombat()
        ClueScrollTier.Medium -> hasUnlockedMediumCombat()
        ClueScrollTier.Hard -> hasUnlockedHardCombat()
        ClueScrollTier.Elite -> hasUnlockedEliteCombat()
        ClueScrollTier.Master -> hasUnlockedMasterCombat()
    }
    var denominator = if (claimed) (base.toLong() * 95 / 100).toInt().coerceAtLeast(1) else base
    val coords = args[KillRollContext.dropCoords]
    val areas = args[KillRollContext.areaChecker]
    if (wearingImbuedRingOfWealth() && coords != null && areas != null && coords.isInWilderness(areas)) {
        denominator = (denominator / 2).coerceAtLeast(1)
    }
    return denominator
}
