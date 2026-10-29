package org.rsmod.content.other.special.weapons.ranged

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.death.NpcAttackValidateResult
import org.rsmod.content.other.special.weapons.ranged.RangedWeaponXpTest.Fixture
import org.rsmod.content.other.special.weapons.ranged.RangedWeaponXpTest.Kind
import org.rsmod.game.entity.Npc
import org.rsmod.game.interact.InteractionOp
import org.rsmod.map.zone.ZoneKey

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class VenatorTargetingTest {
    @Test
    fun `invalid return target is rejected and a valid third target is used when present`() {
        for (condition in InvalidReturn.entries) for (fallback in listOf(false, true)) {
            val f = fixture()
            val third = if (fallback) f.addNpc(Npc("npc.earth_nagua", f.primary.coords.translate(3, 0))) else null
            f.beforeQueue = { victim ->
                if (victim === f.secondary) invalidate(f, condition)
            }
            f.roll(f.primary, 1)
            f.roll(f.secondary, 2, multiplier = 2.0 / 3.0)
            if (third != null) f.roll(third, 3, multiplier = 2.0 / 3.0)

            f.attack()

            assertEquals(if (fallback) 3 else 2, f.projectiles.size, "$condition fallback=$fallback")
            assertEquals(listOf(2), f.queued(f.secondary).map { it.damage })
            if (third != null) assertEquals(listOf(3), f.queued(third).map { it.damage })
            f.assertConsumed(ammo = 1, charge = 1)
        }
    }

    @Test
    fun `live return target remains eligible on exact current radius and rejected one tile beyond`() {
        for (distance in listOf(2, 3)) {
            val f = fixture()
            f.beforeQueue = { victim ->
                if (victim === f.secondary) f.move(f.primary, f.secondary.coords.translate(-distance, 0))
            }
            f.roll(f.primary, 1)
            f.roll(f.secondary, 2, multiplier = 2.0 / 3.0)
            if (distance == 2) f.roll(f.primary, 3, multiplier = 2.0 / 3.0)

            f.attack()

            assertEquals(if (distance == 2) listOf(5, 3) else listOf(5), f.queued(f.primary).map { it.damage })
            assertEquals(if (distance == 2) 3 else 2, f.projectiles.size)
            f.assertConsumed(ammo = 1, charge = 1)
        }
    }

    @Test
    fun `real zone iterator finds adjacent-zone victim and excludes denied ordinary candidates`() {
        val f = fixture()
        assertNotEquals(ZoneKey.from(f.primary.coords), ZoneKey.from(f.secondary.coords))
        val denied = f.addNpc(Npc("npc.earth_nagua", f.primary.coords.translate(0, 1)))
        val checked = mutableListOf<Npc>()
        f.validateTarget = { candidate ->
            checked += candidate
            if (candidate === denied) NpcAttackValidateResult.Deny("Not your target.") else NpcAttackValidateResult.Pass
        }
        f.roll(f.primary, 1)
        f.roll(f.secondary, 2, multiplier = 2.0 / 3.0)
        f.roll(f.primary, 3, multiplier = 2.0 / 3.0)

        f.attack()

        assertEquals(listOf(5, 3), f.queued(f.primary).map { it.damage })
        assertEquals(listOf(2), f.queued(f.secondary).map { it.damage })
        assertTrue(f.queued(denied).isEmpty())
        assertTrue(denied in checked)
        assertTrue(f.primary in checked, "Returning primary must pass the same validation hooks")
        f.assertConsumed(ammo = 1, charge = 1)
    }

    @Test
    fun `live primary preserves nearest ordering when farther third target is also eligible`() {
        val f = fixture()
        val third = f.addNpc(Npc("npc.earth_nagua", f.primary.coords.translate(3, 0)))
        f.roll(f.primary, 1)
        f.roll(f.secondary, 2, multiplier = 2.0 / 3.0)
        f.roll(f.primary, 3, multiplier = 2.0 / 3.0)

        f.attack()

        assertEquals(listOf(5, 3), f.queued(f.primary).map { it.damage })
        assertTrue(f.queued(third).isEmpty())
        f.assertConsumed(ammo = 1, charge = 1)
    }

    @Test
    fun `a missed shot still bounces and a dead origin does not block an eligible next victim`() {
        for (deadOrigin in listOf(false, true)) {
            val f = fixture()
            if (deadOrigin) f.beforeQueue = { victim -> if (victim === f.secondary) victim.hitpoints = 0 }
            f.roll(f.primary, damage = null)
            f.roll(f.secondary, 2, multiplier = 2.0 / 3.0)
            f.roll(f.primary, 3, multiplier = 2.0 / 3.0)

            f.attack()

            assertEquals(listOf(0, 3), f.queued(f.primary).map { it.damage })
            assertEquals(listOf(2), f.queued(f.secondary).map { it.damage })
            assertEquals(3, f.projectiles.size)
            f.assertConsumed(ammo = 1, charge = 1)
        }
    }

    private fun fixture(): Fixture = Fixture(Kind.Venator, "npc.earth_nagua", secondarySymbol = "npc.earth_nagua")

    private fun invalidate(f: Fixture, condition: InvalidReturn) {
        when (condition) {
            InvalidReturn.Hook -> f.validateTarget = { candidate ->
                if (candidate === f.primary) NpcAttackValidateResult.Deny("Target is protected.") else NpcAttackValidateResult.Pass
            }
            InvalidReturn.Hidden -> f.npcRegistry.hide(f.primary)
            InvalidReturn.Dead -> f.primary.hitpoints = 0
            InvalidReturn.Delayed -> f.primary.delay(2)
            InvalidReturn.Deleted -> f.npcRegistry.del(f.primary)
            InvalidReturn.StaleIdentity -> {
                val replacement = Npc("npc.earth_nagua", f.primary.coords).apply {
                    slotId = f.primary.slotId
                    assignUid()
                }
                assertEquals(f.primary.uid, replacement.uid)
                f.npcs[replacement.slotId] = replacement
                assertTrue(f.primary.uid.resolve(f.npcs) === replacement)
            }
            InvalidReturn.OtherPlane -> f.move(f.primary, f.primary.coords.translate(0, 0, 1))
            InvalidReturn.OutOfRange -> f.move(f.primary, f.secondary.coords.translate(-3, 0))
            InvalidReturn.NoAttackOp -> {
                val type = checkNotNull(ServerCacheManager.getNpc("npc.hans".asRSCM()))
                assertFalse(type.hasOp(InteractionOp.Op2.slot))
                f.primary.transmog(type, Int.MAX_VALUE)
            }
        }
    }

    private enum class InvalidReturn { Hook, Hidden, Dead, Delayed, Deleted, StaleIdentity, OtherPlane, OutOfRange, NoAttackOp }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
