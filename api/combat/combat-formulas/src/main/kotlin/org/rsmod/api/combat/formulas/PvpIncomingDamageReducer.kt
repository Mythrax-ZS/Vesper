package org.rsmod.api.combat.formulas

import jakarta.inject.Inject
import org.rsmod.api.combat.formulas.attributes.DamageReductionAttributes
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.player.hit.modifier.PvpIncomingDamageReducer as IncomingDamageReducer
import org.rsmod.api.random.GameRandom
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder

public class PvpIncomingDamageReducer
@Inject
constructor(
    private val random: GameRandom,
    private val reductions: DamageReductionAttributeCollector,
) : IncomingDamageReducer {
    override fun HitBuilder.reduce(target: Player) {
        if (!isFromPlayer || !pvpDamageReduction || isMagicSplash || damage <= 0) return
        val attributes = reductions.collectPvP(target, random)
        if (DamageReductionAttributes.ElysianProc in attributes) {
            damage = (damage.toLong() * 3 / 4).toInt()
        }
    }
}
