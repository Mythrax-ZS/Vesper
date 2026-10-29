package org.rsmod.api.player.hit.modifier

import jakarta.inject.Inject
import org.rsmod.api.player.cheat.adminGodMode
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.hit.PlayerAbsorption
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.hit.HitType

public class StandardPlayerHitModifier @Inject constructor(
    private val eventBus: EventBus,
    private val npcReductions: NpcIncomingDamageReducer = NoopNpcIncomingDamageReducer(),
    private val pvpReductions: PvpIncomingDamageReducer,
) :
    PlayerHitModifier {
    public constructor(
        eventBus: EventBus,
        npcReductions: NpcIncomingDamageReducer = NoopNpcIncomingDamageReducer(),
    ) : this(eventBus, npcReductions, NoopPvpIncomingDamageReducer())

    override fun HitBuilder.modify(target: Player) {
        val beforeEvent = damage
        val beforeReflection = reflectionDamage
        target.publishEvent(this)
        if (damage != beforeEvent && reflectionDamage == beforeReflection) {
            reflectionDamage = damage
        }
        var reflectedBasis = reflectionDamage ?: damage

        if (target.adminGodMode) {
            damage = 0
            reflectionDamage = 0
            return
        }

        if (isFromNpc) {
            with(npcReductions) { reduce(target) }
            if (npcDamageContext != null) reflectedBasis = damage
            npcDamageCap?.let { damage = minOf(damage, it) }
        }

        if (isFromPlayer && pvpDamageReduction) {
            with(pvpReductions) { reduce(target) }
            reflectedBasis = damage
        }

        val protectionPrayer =
            when (type) {
                HitType.Typeless -> false
                HitType.Melee -> target.vars["varbit.prayer_protectfrommelee"] == 1
                HitType.Ranged -> target.vars["varbit.prayer_protectfrommissiles"] == 1
                HitType.Magic -> target.vars["varbit.prayer_protectfrommagic"] == 1
            }

        if (protectionPrayer) {
            val baseReduction = if (isFromPlayer) 40 else 100
            val reduction = (baseReduction - penetration).coerceIn(0, 100)
            damage = (damage * (100 - reduction)) / 100
            reflectedBasis = (reflectedBasis.toLong() * (100 - reduction) / 100).toInt()
        }

        if (isFromNpc) {
            val absorptionBefore = PlayerAbsorption.points(target)
            damage =
                PlayerAbsorption.absorb(
                    player = target,
                    incomingDamage = damage,
                )
            val absorbed = absorptionBefore - PlayerAbsorption.points(target)
            reflectedBasis = (reflectedBasis - absorbed).coerceAtLeast(0)
        }
        reflectionDamage = reflectedBasis.coerceAtLeast(0)
    }

    private fun Player.publishEvent(hit: HitBuilder) {
        val event = PlayerHitEvents.Modify(this, hit)
        eventBus.publish(event)
    }
}
