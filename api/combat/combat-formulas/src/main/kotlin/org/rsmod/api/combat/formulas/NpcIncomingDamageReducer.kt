package org.rsmod.api.combat.formulas

import dev.openrune.ServerCacheManager
import jakarta.inject.Inject
import org.rsmod.api.combat.formulas.attributes.DamageReductionAttributes
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.hit.modifier.NpcIncomingDamageReducer as IncomingDamageReducer
import org.rsmod.api.random.GameRandom
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.hit.HitDefenceType

public class NpcIncomingDamageReducer
@Inject
constructor(
    private val random: GameRandom,
    private val bonuses: WornBonuses,
    private val reductions: DamageReductionAttributeCollector,
) : IncomingDamageReducer {
    override fun HitBuilder.reduce(target: Player) {
        val context = npcDamageContext ?: return
        if (!isFromNpc || damage <= 0) return
        val sourceType = if (context.demonWard) ServerCacheManager.getNpc(context.sourceType) else null
        val attributes = reductions.collectIncoming(
            target,
            sourceType,
            random,
            equipment = context.equipment,
            demonWard = context.demonWard,
        )
        val defenceBonus = if (DamageReductionAttributes.Justiciar in attributes) {
            when (context.defenceType) {
                HitDefenceType.Stab -> bonuses.defensiveStabBonus(target)
                HitDefenceType.Slash -> bonuses.defensiveSlashBonus(target)
                HitDefenceType.Crush -> bonuses.defensiveCrushBonus(target)
                HitDefenceType.Ranged -> bonuses.defensiveRangedBonus(target)
                HitDefenceType.Magic -> bonuses.defensiveMagicBonus(target)
                null -> null
            }
        } else null
        damage = NpcDamageReductions.apply(damage, defenceBonus, attributes)
    }
}
