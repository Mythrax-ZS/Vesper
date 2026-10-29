package org.rsmod.api.combat.formulas

import java.util.EnumSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.api.combat.formulas.attributes.DamageReductionAttributes

class NpcDamageReductionsTest {
    @Test
    fun `published Justiciar stacking example truncates the remaining damage`() {
        assertEquals(30, reduced(40, 450, DamageReductionAttributes.ElysianProc))
        assertEquals(25, reduced(40, 450, DamageReductionAttributes.ElysianProc, DamageReductionAttributes.Justiciar))
        assertEquals(25, reduced(30, 450, DamageReductionAttributes.Justiciar))
    }

    @Test
    fun `inherited Elysian rounding transforms sampled hits rather than rerolling a smaller maximum`() {
        val transformed = (0..4).map { reduced(it, null, DamageReductionAttributes.ElysianProc) }
        assertEquals(listOf(0, 0, 1, 2, 3), transformed)
        assertEquals(mapOf(0 to 2, 1 to 1, 2 to 1, 3 to 1), transformed.groupingBy { it }.eachCount())
    }

    @Test
    fun `inherited Dinh rounding transforms every sampled hit`() {
        assertEquals(listOf(0, 0, 1, 2, 3, 4), (0..5).map { reduced(it, null, DamageReductionAttributes.DinhsBlock) })
    }

    @Test
    fun `Ward follows the equipment reductions and subtracts a floored tenth`() {
        assertEquals(68, reduced(100, null, DamageReductionAttributes.ElysianProc, DamageReductionAttributes.WardOfArceuus))
        assertEquals(9, reduced(9, null, DamageReductionAttributes.WardOfArceuus))
        assertEquals(9, reduced(10, null, DamageReductionAttributes.WardOfArceuus))
        assertEquals(18, reduced(40, 450, *DamageReductionAttributes.entries.toTypedArray()))
    }

    @Test
    fun `Justiciar keeps a minimum one reduction without increasing damage on negative defence`() {
        for (bonus in listOf(-100, 0, 1)) {
            assertEquals(0, reduced(0, bonus, DamageReductionAttributes.Justiciar))
            assertEquals(0, reduced(1, bonus, DamageReductionAttributes.Justiciar))
            assertEquals(9, reduced(10, bonus, DamageReductionAttributes.Justiciar))
        }
        assertEquals(10, reduced(10, null, DamageReductionAttributes.Justiciar))
        assertEquals(0, reduced(10, 3000, DamageReductionAttributes.Justiciar))
        assertEquals(0, reduced(10, Int.MAX_VALUE, DamageReductionAttributes.Justiciar))
    }

    @Test
    fun `large sampled hits do not overflow during multiplication`() {
        assertEquals(1_610_612_735, reduced(Int.MAX_VALUE, null, DamageReductionAttributes.ElysianProc))
        assertEquals(1_717_986_917, reduced(Int.MAX_VALUE, null, DamageReductionAttributes.DinhsBlock))
        for (bonus in listOf(Int.MIN_VALUE, 0, 450, 3000, Int.MAX_VALUE)) {
            assertTrue(reduced(Int.MAX_VALUE, bonus, *DamageReductionAttributes.entries.toTypedArray()) in 0..Int.MAX_VALUE)
        }
    }

    private fun reduced(damage: Int, defence: Int?, vararg flags: DamageReductionAttributes): Int =
        NpcDamageReductions.apply(
            damage,
            defence,
            EnumSet.noneOf(DamageReductionAttributes::class.java).apply { addAll(flags) },
        )
}
