package org.rsmod.api.combat.formulas

import java.util.EnumSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.rsmod.api.combat.formulas.attributes.CombatMeleeAttributes
import org.rsmod.api.combat.formulas.attributes.CombatNpcAttributes
import org.rsmod.api.combat.formulas.maxhit.melee.MeleeMaxHitOperations

class VampyreSlayerMaxHitTest {
    @Test
    fun `garlic raises the quest Count melee roll ceiling by one`() {
        for (base in 0..50) assertEquals(base + 1, maximum(base, garlic = true, count = true))
    }

    @Test
    fun `ordinary vampyres and players without garlic keep their roll ceiling`() {
        for (base in 0..50) {
            assertEquals(base, maximum(base, garlic = false, count = true))
            assertEquals(base, maximum(base, garlic = true, count = false))
            assertEquals(base, maximum(base, garlic = false, count = false))
        }
    }

    private fun maximum(base: Int, garlic: Boolean, count: Boolean): Int {
        val melee = EnumSet.noneOf(CombatMeleeAttributes::class.java)
        if (garlic) melee += CombatMeleeAttributes.Garlic
        val npc = EnumSet.of(CombatNpcAttributes.Vampyre)
        if (count) npc += CombatNpcAttributes.CountDraynor
        return MeleeMaxHitOperations.modifyPostSpec(base, 4, 10, 10, melee, npc)
    }
}
