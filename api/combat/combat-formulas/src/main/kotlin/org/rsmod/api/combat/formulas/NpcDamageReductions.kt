package org.rsmod.api.combat.formulas

import java.util.EnumSet
import org.rsmod.api.combat.formulas.attributes.DamageReductionAttributes

internal object NpcDamageReductions {
    fun apply(damage: Int, defenceBonus: Int?, attributes: EnumSet<DamageReductionAttributes>): Int {
        if (damage <= 0) return damage
        var reduced = damage
        if (DamageReductionAttributes.ElysianProc in attributes) {
            reduced = (reduced.toLong() * 3 / 4).toInt()
        }
        if (DamageReductionAttributes.DinhsBlock in attributes) {
            reduced = (reduced.toLong() * 4 / 5).toInt()
        }
        if (DamageReductionAttributes.Justiciar in attributes && defenceBonus != null && reduced > 0) {
            val multiplier = 3000 - defenceBonus.coerceIn(0, 3000)
            val residual = (reduced.toLong() * multiplier / 3000).toInt()
            reduced = minOf(reduced - 1, residual).coerceAtLeast(0)
        }
        if (DamageReductionAttributes.WardOfArceuus in attributes) {
            reduced -= reduced / 10
        }
        return reduced
    }
}
