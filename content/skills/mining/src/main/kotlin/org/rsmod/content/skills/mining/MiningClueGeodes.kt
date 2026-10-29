package org.rsmod.content.skills.mining

/**
 * floor(floor(floor(base * 0.7) * CA) / (100 + visible Mining) * tier).
 *
 * Keep the intermediate floors before the level division, then one final floor after the tier
 * multiplier. In particular, rounding the level division first changes the live drop rates.
 */
internal fun miningGeodeDenominator(
    baseChance: Int,
    visibleLevel: Int,
    multiplierTenths: Int,
    combatReward: Boolean,
    wildernessBonus: Boolean,
): Int {
    require(baseChance > 0)
    require(multiplierTenths > 0)
    var base = baseChance.toLong() * 7 / 10
    if (combatReward) base = base * 95 / 100
    var denominator = base * multiplierTenths / ((100L + visibleLevel.coerceAtLeast(0)) * 10)
    if (wildernessBonus) denominator /= 2
    return denominator.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
}
