package org.rsmod.api.npc.hit.modifier

import jakarta.inject.Inject
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.hit.flatArmour
import org.rsmod.api.npc.hit.isStyleImmuneTo
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.hit.HitType
import org.rsmod.game.hit.Hitmark
import org.rsmod.game.hit.NpcFlatArmourPolicy

public class StandardNpcHitModifier @Inject constructor(private val eventBus: EventBus) :
    NpcHitModifier {
    override fun HitBuilder.modify(target: Npc) {
        target.publishEvent(this)
        if (target.isStyleImmuneTo(type)) {
            damage = 0
            return
        }
        target.applyFlatArmour(this)
    }

    private fun Npc.publishEvent(hit: HitBuilder) {
        val event = NpcHitEvents.Modify(this, hit)
        eventBus.publish(event)
    }

    private fun Npc.applyFlatArmour(hit: HitBuilder) {
        // Flat armour applies to standard player melee/ranged hits, not magic, typeless damage,
        // or NPC-sourced effects such as thralls. It applies per hit, not as an absorption pool.
        // https://oldschool.runescape.wiki/w/Flat_armour
        if (!hit.isFromPlayer || (hit.type != HitType.Melee && hit.type != HitType.Ranged)) {
            return
        }

        // Zero does not establish accuracy. In particular, negative armour must not turn a
        // physical miss or a zero imposed by an earlier immunity hook into damage.
        if (hit.damage <= 0) {
            return
        }

        val armour = flatArmour
        if (armour == 0 || (armour < 0 && hit.npcFlatArmourPolicy == NpcFlatArmourPolicy.IgnoreNegative)) {
            return
        }

        // A successful physical roll starts at one, but later flat armour can reduce it to zero.
        // Widen before subtraction so even Int.MIN_VALUE armour cannot wrap or fail abs().
        hit.damage = (hit.damage.toLong() - armour.toLong()).coerceIn(0L, Hitmark.DAMAGE_BIT_MASK).toInt()
    }
}
