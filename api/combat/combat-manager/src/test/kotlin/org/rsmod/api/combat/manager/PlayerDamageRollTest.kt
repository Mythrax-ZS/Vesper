package org.rsmod.api.combat.manager

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.player.cheat.adminMaxHit
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class PlayerDamageRollTest {
    @Test
    fun `maximum three has outcomes one one two three for every player damage path`() {
        for (style in Style.entries) {
            for (pvp in listOf(false, true)) {
                val f = Fixture()
                val target = if (pvp) f.opponent else f.npc
                val outcomes = (0..3).map { raw ->
                    f.random.expect(0..3, raw)
                    f.roll(style, target)
                }

                assertEquals(listOf(1, 1, 2, 3), outcomes, "$style pvp=$pvp")
                assertEquals(mapOf(1 to 2, 2 to 1, 3 to 1), outcomes.groupingBy { it }.eachCount())
                f.random.assertConsumed()
            }
        }
    }

    @Test
    fun `inclusive maxima are retained without resampling a zero`() {
        for (maximum in listOf(1, 2, 25)) {
            for (style in Style.entries) {
                for (pvp in listOf(false, true)) {
                    val f = Fixture(maximum)
                    val target = if (pvp) f.opponent else f.npc
                    val outcomes = (0..maximum).map { raw ->
                        f.random.expect(0..maximum, raw)
                        f.roll(style, target)
                    }

                    assertEquals(listOf(1) + (1..maximum).toList(), outcomes, "$style max=$maximum pvp=$pvp")
                    assertEquals(maximum + 1, f.random.calls)
                    f.random.assertConsumed()
                }
            }
        }
    }

    @Test
    fun `a zero attack maximum stays zero without an invalid random range`() {
        for (style in Style.entries) {
            for (pvp in listOf(false, true)) {
                val f = Fixture(maximum = 0)
                val target = if (pvp) f.opponent else f.npc

                assertEquals(0, f.roll(style, target, baseMaxHit = 0), "$style pvp=$pvp")
                assertEquals(0, f.random.calls)
                f.random.assertConsumed()
            }
        }
    }

    @Test
    fun `a zero-maximum subhit can retain its positive whole-attack eligibility`() {
        val f = Fixture()
        f.random.expect(0..0, 0)

        assertEquals(1, f.manager.rollSuccessfulDamage(f.source, 0..0, damagingAttack = true))
        assertEquals(0, f.manager.rollSuccessfulDamage(f.source, 0..0))
        assertEquals(0, f.manager.rollSuccessfulDamage(f.source, 0..7, damagingAttack = false))
        assertEquals(1, f.random.calls)
        f.random.assertConsumed()
    }

    @Test
    fun `admin mode keeps a zero-maximum subhit at its exact maximum`() {
        val f = Fixture()
        f.source.adminMaxHit = true

        assertEquals(0, f.manager.rollSuccessfulDamage(f.source, 0..0, damagingAttack = true))
        assertEquals(0, f.random.calls)
        f.random.assertConsumed()
    }

    @Test
    fun `positive spell minima keep their original uniform range`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(maximum = 7)
            f.spellRange = 2..7
            val target = if (pvp) f.opponent else f.npc
            val outcomes = (2..7).map { raw ->
                f.random.expect(2..7, raw)
                f.roll(Style.Spell, target, sunfire = true)
            }

            assertEquals((2..7).toList(), outcomes)
            assertEquals(6, f.random.calls)
            f.random.assertConsumed()
        }
    }

    @Test
    fun `sunfire with a rounded zero minimum still converts only the rolled zero`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture()
            val target = if (pvp) f.opponent else f.npc
            f.random.expect(0..3, 0)
            f.random.expect(0..3, 1)

            assertEquals(1, f.roll(Style.Spell, target, sunfire = true))
            assertEquals(1, f.roll(Style.Spell, target, sunfire = true))
            assertEquals(2, f.random.calls)
            f.random.assertConsumed()
        }
    }

    @Test
    fun `failed physical accuracy neither computes a maximum nor draws damage`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture()
            f.accurate = false
            val target = if (pvp) f.opponent else f.npc

            assertEquals(0, f.manager.rollMeleeDamage(f.source, target, f.melee))
            assertEquals(0, f.manager.rollRangedDamage(f.source, target, f.ranged))
            verify { f.maxHits wasNot Called }
            assertEquals(0, f.random.calls)
            f.random.assertConsumed()
        }
    }

    @Test
    fun `successful physical accuracy reaches the shared zero conversion`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture()
            val target = if (pvp) f.opponent else f.npc
            f.random.expect(0..3, 0)
            f.random.expect(0..3, 0)

            assertEquals(1, f.manager.rollMeleeDamage(f.source, target, f.melee))
            assertEquals(1, f.manager.rollRangedDamage(f.source, target, f.ranged))
            f.random.assertConsumed()
        }
    }

    @Test
    fun `admin maximum bypass still skips damage randomness including a zero maximum`() {
        for (maximum in listOf(0, 3)) {
            for (style in Style.entries) {
                val f = Fixture(maximum)
                f.source.adminMaxHit = true
                for (target in listOf(f.npc, f.opponent)) {
                    assertEquals(maximum, f.roll(style, target), "$style max=$maximum")
                }
                assertEquals(0, f.random.calls)
                f.random.assertConsumed()
            }
        }
    }

    @Test
    fun `post-roll flat armour immunity prayer and explicit splashes retain zero damage`() {
        val f = Fixture()
        f.random.expect(0..3, 0)
        val successfulDamage = f.roll(Style.Melee, f.npc)
        assertEquals(1, successfulDamage)
        f.npc.vars["varn.flat_armour"] = 1
        assertEquals(0, f.manager.queueMeleeHit(f.source, f.npc, successfulDamage).damage)

        f.npc.vars["varn.immune_magic"] = 1
        f.random.expect(0..3, 0)
        val spellDamage = f.roll(Style.Spell, f.npc)
        val immune = f.manager.queueMagicHit(f.source, f.npc, f.spell, spellDamage, 60, 3)
        assertEquals(0, immune.damage)
        assertFalse(immune.isMagicSplash)

        VarPlayerIntMapSetter.set(f.opponent, "varbit.prayer_protectfrommelee", 1)
        f.random.expect(0..3, 0)
        val physicalDamage = f.roll(Style.Melee, f.opponent)
        assertEquals(0, f.manager.queueMeleeHit(f.source, f.opponent, physicalDamage).damage)

        val splash = f.manager.queueSplashHit(f.source, f.opponent, f.spell, 60, 3)
        assertEquals(0, splash.damage)
        assertTrue(splash.isMagicSplash)
        assertEquals(3, f.random.calls)
        f.random.assertConsumed()
    }

    private enum class Style { Melee, Ranged, Staff, Spell }

    private class Fixture(val maximum: Int = 3) {
        val random = StrictRandom()
        val events = EventBus()
        val source = player(1)
        val opponent = player(2)
        val npc = Npc("npc.king_dragon", CoordGrid(2271, 4695)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
        }
        val spell = checkNotNull(ServerCacheManager.getItem("obj.01_wind_strike".asRSCM()))
        val maxHits = mockk<MaxHitFormulae>()
        private val accuracy = mockk<AccuracyFormulae>()
        var spellRange = 0..maximum
        var accurate = true
        val melee = CombatAttack.Melee(null, MeleeAttackType.Stab, MeleeAttackStyle.Accurate, CombatStance.Stance1)
        val ranged = CombatAttack.Ranged(
            InvObj(checkNotNull(ServerCacheManager.getItem("obj.shortbow".asRSCM()))),
            RangedAttackType.Standard,
            RangedAttackStyle.Accurate,
        )
        val manager = PlayerAttackManager(
            random, events, mockk(), accuracy, maxHits,
            StandardNpcHitModifier(events), StandardPlayerHitModifier(events),
            mockk(), mockk(), mockk(), mockk(), emptySet(),
        )

        init {
            every { maxHits.getMeleeMaxHit(source, npc, any(), any(), any(), any()) } returns maximum
            every { maxHits.getMeleeMaxHit(source, opponent, any(), any(), any(), any()) } returns maximum
            every { maxHits.getRangedMaxHit(source, npc, any(), any(), any(), any()) } returns maximum
            every { maxHits.getRangedMaxHit(source, opponent, any(), any(), any(), any()) } returns maximum
            every { maxHits.getStaffMaxHit(source, npc, any(), any()) } returns maximum
            every { maxHits.getStaffMaxHit(source, opponent, any(), any()) } returns maximum
            every { maxHits.getSpellMaxHitRange(source, npc, spell, any(), any(), any(), any()) } answers { spellRange }
            every { maxHits.getSpellMaxHitRange(source, opponent, spell, any(), any(), any()) } answers { spellRange }
            every { accuracy.rollMeleeAccuracy(source, npc, any(), any(), any(), any(), random) } answers { accurate }
            every { accuracy.rollMeleeAccuracy(source, opponent, any(), any(), any(), any(), random) } answers { accurate }
            every { accuracy.rollRangedAccuracy(source, npc, any(), any(), any(), any(), random) } answers { accurate }
            every { accuracy.rollRangedAccuracy(source, opponent, any(), any(), any(), random) } answers { accurate }
        }

        fun roll(style: Style, target: PathingEntity, baseMaxHit: Int = 3, sunfire: Boolean = false): Int =
            when (style) {
                Style.Melee -> manager.rollMeleeMaxHit(source, target, MeleeAttackType.Stab, MeleeAttackStyle.Accurate, 1.0)
                Style.Ranged -> manager.rollRangedMaxHit(source, target, RangedAttackType.Standard, RangedAttackStyle.Accurate, 1.0, 0)
                Style.Staff -> manager.rollStaffMaxHit(source, target, baseMaxHit, 1.0)
                Style.Spell -> manager.rollSpellMaxHit(source, target, spell, Spellbook.Standard, baseMaxHit, 5, sunfire)
            }

        private fun player(slot: Int): Player = Player().apply {
            slotId = slot
            uuid = slot.toLong()
            worldType = WorldType.MAIN
            assignUid()
            coords = CoordGrid(2276, 4695)
            currentMapClock = 100
            processedMapClock = 100
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            statMap.setBaseLevel("stat.hitpoints", 99)
            statMap.setCurrentLevel("stat.hitpoints", 99)
        }
    }

    private class StrictRandom : GameRandom {
        private val expected = ArrayDeque<Pair<IntRange, Int>>()
        var calls = 0
            private set

        fun expect(range: IntRange, value: Int) {
            require(value in range)
            expected.addLast(range to value)
        }

        fun assertConsumed() = assertTrue(expected.isEmpty(), "Unused damage rolls: $expected")

        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            calls++
            val (range, value) = checkNotNull(expected.removeFirstOrNull()) { "Unexpected damage draw $minInclusive..$maxInclusive" }
            assertEquals(range, minInclusive..maxInclusive)
            return value
        }

        override fun of(maxExclusive: Int): Int = error("Unexpected exclusive RNG draw: $maxExclusive")
        override fun randomDouble(): Double = error("Unexpected floating-point RNG draw")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
        }
    }
}
