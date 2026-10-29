package org.rsmod.content.other.special.weapons.ranged

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.util.Wearpos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.player.quiver
import org.rsmod.content.other.special.weapons.ranged.RangedWeaponXpTest.Fixture
import org.rsmod.content.other.special.weapons.ranged.RangedWeaponXpTest.Kind
import org.rsmod.game.entity.PathingEntity

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class RangedWeaponAmmunitionTest {
    @Test
    fun `dark bow final arrow retains real ammunition bonus and launch metadata for NPC and PvP`() {
        for (pvp in listOf(false, true)) {
            val last = Fixture(Kind.DarkBow, "npc.earth_nagua", arrows = 1, pvp = pvp, realCombat = true)
            val stocked = Fixture(Kind.DarkBow, "npc.earth_nagua", arrows = 20, pvp = pvp, realCombat = true)
            val equippedBonus = last.bonuses.rangedStrengthBonus(last.source)
            assertTrue(equippedBonus > 0)

            stocked.attack()
            last.attack()

            assertEquals(listOf(stocked.sampledDamageRanges.first()), last.sampledDamageRanges)
            assertEquals(stocked.queued(target(stocked)).first().damage, last.queued(target(last)).single().damage)
            assertTrue(last.bonuses.rangedStrengthBonus(last.source) < equippedBonus)
            assertNull(last.source.quiver)
            val hit = last.queued(target(last)).single()
            assertTrue(hit.isSecondaryObj(last.ammoType))
            assertTrue(hit.isRighthandObj(last.weaponType))
            if (pvp) last.assertPvpXp(hit.damage) else last.assertXp(last.primary to hit.damage)
            last.assertConsumed(ammo = 1, charge = 0)
            stocked.assertConsumed(ammo = 2, charge = 0)
        }
    }

    @Test
    fun `dark bow final two arrows both retain equipped bonus and grouped XP`() {
        for (pvp in listOf(false, true)) {
            val last = Fixture(Kind.DarkBow, "npc.earth_nagua", arrows = 2, pvp = pvp, realCombat = true)
            val stocked = Fixture(Kind.DarkBow, "npc.earth_nagua", arrows = 20, pvp = pvp, realCombat = true)

            stocked.attack()
            last.attack()

            assertEquals(stocked.sampledDamageRanges, last.sampledDamageRanges)
            assertEquals(2, last.sampledDamageRanges.size)
            val hits = last.queued(target(last))
            assertEquals(stocked.queued(target(stocked)).map { it.damage }, hits.map { it.damage })
            assertTrue(hits.all { it.isSecondaryObj(last.ammoType) && it.isRighthandObj(last.weaponType) })
            assertNull(last.source.quiver)
            if (pvp) last.assertPvpXp(hits.sumOf { it.damage }) else last.assertXp(last.primary to hits.sumOf { it.damage })
            last.assertConsumed(ammo = 2, charge = 0)
            stocked.assertConsumed(ammo = 2, charge = 0)
        }
    }

    @Test
    fun `Venator final arrow and final charge preserve whole chain rolls and charged hit identity`() {
        val last = Fixture(Kind.Venator, "npc.earth_nagua", arrows = 1, chargeCount = 1, realCombat = true)
        val stocked = Fixture(Kind.Venator, "npc.earth_nagua", arrows = 20, chargeCount = 10, realCombat = true)

        stocked.attack()
        last.attack()

        assertEquals(stocked.sampledDamageRanges, last.sampledDamageRanges)
        assertEquals(3, last.sampledDamageRanges.size)
        val primaryHits = last.queued(last.primary)
        val secondaryHits = last.queued(last.secondary)
        assertEquals(stocked.queued(stocked.primary).map { it.damage }, primaryHits.map { it.damage })
        assertEquals(stocked.queued(stocked.secondary).map { it.damage }, secondaryHits.map { it.damage })
        assertEquals(2, primaryHits.size)
        assertEquals(1, secondaryHits.size)
        assertTrue((primaryHits + secondaryHits).all { it.isSecondaryObj(last.ammoType) && it.isRighthandObj(last.weaponType) })
        last.assertXp(last.primary to primaryHits[0].damage, last.secondary to secondaryHits[0].damage, last.primary to primaryHits[1].damage)
        assertNull(last.source.quiver)
        assertEquals("obj.venator_bow_uncharged".asRSCM(), last.source.worn[Wearpos.RightHand.slot]?.id)
        assertEquals(0, last.remainingCharges())
        assertEquals(0, last.continuations)
        assertEquals(1, stocked.continuations)
        last.assertConsumed(ammo = 1, charge = 1)
        stocked.assertConsumed(ammo = 1, charge = 1)
    }

    @Test
    fun `Venator final charge preserves charged metadata for a single PvP shot`() {
        val f = Fixture(Kind.Venator, "npc.earth_nagua", arrows = 1, chargeCount = 1, pvp = true, realCombat = true)

        f.attack()

        val hit = f.queued(f.opponent).single()
        assertTrue(hit.isRighthandObj(f.weaponType))
        assertTrue(hit.isSecondaryObj(f.ammoType))
        assertEquals(1, f.sampledDamageRanges.size)
        assertEquals(1, f.projectiles.size)
        f.assertPvpXp(hit.damage)
        assertEquals("obj.venator_bow_uncharged".asRSCM(), f.source.worn[Wearpos.RightHand.slot]?.id)
        assertNull(f.source.quiver)
        assertEquals(0, f.continuations)
        f.assertConsumed(ammo = 1, charge = 1)
    }

    @Test
    fun `zero charge normalizes charged bow and missing weapon cannot consume an arrow or fire`() {
        for (missing in listOf(false, true)) {
            val f = Fixture(Kind.Venator, "npc.earth_nagua", arrows = 1, chargeCount = 0, realCombat = true)
            if (missing) f.source.worn[Wearpos.RightHand.slot] = null

            f.attack()

            assertTrue(f.queued(f.primary).isEmpty())
            assertTrue(f.queued(f.secondary).isEmpty())
            assertTrue(f.projectiles.isEmpty())
            assertTrue(f.sampledDamageRanges.isEmpty())
            assertEquals(if (missing) null else "obj.venator_bow_uncharged".asRSCM(), f.source.worn[Wearpos.RightHand.slot]?.id)
            assertEquals(0, f.continuations)
            f.assertXp(f.primary to 0)
            f.assertConsumed(ammo = 0, charge = 0)
        }
    }

    @Test
    fun `missing ammunition does not spend Venator charge or launch any hit`() {
        val f = Fixture(Kind.Venator, "npc.earth_nagua", arrows = 0, chargeCount = 1, realCombat = true)

        f.attack()

        assertTrue(f.queued(f.primary).isEmpty())
        assertTrue(f.projectiles.isEmpty())
        assertTrue(f.sampledDamageRanges.isEmpty())
        assertEquals(f.weaponType.id, f.source.worn[Wearpos.RightHand.slot]?.id)
        assertEquals(0, f.continuations)
        f.assertXp(f.primary to 0)
        f.assertConsumed(ammo = 0, charge = 0)
    }

    private fun target(f: Fixture): PathingEntity = if (f.pvp) f.opponent else f.primary

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
