package org.rsmod.content.bosses.kbd

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.DragonfireProtection
import org.rsmod.api.random.GameRandom
import org.rsmod.api.table.KbdBreathProtectionRow
import org.rsmod.game.entity.Player

class KbdBreathDamage @Inject constructor(private val random: GameRandom) {
    fun roll(player: Player, special: Boolean): Int {
        if (!special && player.vars["varbit.super_antifire_potion"] > 0) return 0
        val row = when {
            DragonfireProtection.hasAntifireShield(player) -> SHIELD
            player.vars["varbit.prayer_protectfrommagic"] > 0 -> PRAYER
            else -> UNPROTECTED
        }
        val maximum = if (special) row.specialMax else row.normalMax
        val reduction = if (!special && player.vars["varbit.antifire_potion"] > 0) row.potionReduction else 0
        val cap = (maximum - reduction).coerceAtLeast(0)
        return if (cap == 0) 0 else random.of(cap + 1)
    }

    private companion object {
        private val UNPROTECTED by lazy { KbdBreathProtectionRow.getRow("dbrow.kbd_breath_unprotected") }
        private val PRAYER by lazy { KbdBreathProtectionRow.getRow("dbrow.kbd_breath_prayer") }
        private val SHIELD by lazy { KbdBreathProtectionRow.getRow("dbrow.kbd_breath_shield") }
    }
}
