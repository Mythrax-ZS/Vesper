package org.rsmod.api.combat

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import dev.openrune.types.aconverted.SpotanimType
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.ranged.RangedAmmunition
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.formulas.accuracy.ranged.PvNRangedAccuracy
import org.rsmod.api.combat.formulas.accuracy.ranged.PvPRangedAccuracy
import org.rsmod.api.combat.formulas.attributes.collector.CombatNpcAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatRangedAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.ranged.PvNRangedMaxHit
import org.rsmod.api.combat.formulas.maxhit.ranged.PvPRangedMaxHit
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.interact.PlayerInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class DefaultRangedLaunchStateTest {
    @Test
    fun `last arrow has the same native accuracy damage and hit metadata as stacked arrows`() {
        for (pvp in listOf(false, true)) {
            val final = Fixture(pvp, thrown = false, count = 1)
            val stacked = Fixture(pvp, thrown = false, count = 20)
            assertEquals(stacked.hitChance(), final.hitChance())
            assertEquals(stacked.maxHit(), final.maxHit())

            val finalHit = final.fireAtLastSuccessfulRoll()
            val stackedHit = stacked.fireAtLastSuccessfulRoll()

            assertEquals(stackedHit.damage, finalHit.damage)
            assertTrue(finalHit.damage > 0)
            assertEquals(stacked.random.damageRanges, final.random.damageRanges)
            final.assertSnapshot(finalHit)
            stacked.assertSnapshot(stackedHit)
            assertEquals(0, final.ammoCount())
            assertEquals(19, stacked.ammoCount())
            assertEquals(1, final.player.worn[final.weapon.wearpos1]?.count)
            assertEquals(1, final.ammoRandom.draws)
            assertEquals(stacked.rangedXpGained(), final.rangedXpGained())
        }
    }

    @Test
    fun `last thrown weapon keeps its accuracy strength and weapon snapshot before removal`() {
        for (pvp in listOf(false, true)) {
            val final = Fixture(pvp, thrown = true, count = 1)
            val stacked = Fixture(pvp, thrown = true, count = 20)
            assertEquals(stacked.hitChance(), final.hitChance())
            assertEquals(stacked.maxHit(), final.maxHit())

            val finalHit = final.fireAtLastSuccessfulRoll()
            val stackedHit = stacked.fireAtLastSuccessfulRoll()

            assertEquals(stackedHit.damage, finalHit.damage)
            assertTrue(finalHit.damage > 0)
            assertEquals(stacked.random.damageRanges, final.random.damageRanges)
            final.assertSnapshot(finalHit)
            stacked.assertSnapshot(stackedHit)
            assertEquals(0, final.ammoCount())
            assertEquals(19, stacked.ammoCount())
            assertNull(final.player.worn[final.weapon.wearpos1])
            assertEquals(1, final.ammoRandom.draws)
            assertEquals(stacked.rangedXpGained(), final.rangedXpGained())
        }
    }

    @Test
    fun `final thrown weapon never starts using unrelated quiver bonuses`() {
        for (pvp in listOf(false, true)) {
            val emptyQuiver = Fixture(pvp, thrown = true, count = 1)
            val strongQuiver = Fixture(pvp, thrown = true, count = 1)
            val arrow = item("obj.dragon_arrow")
            strongQuiver.player.worn[arrow.wearpos1] = InvObj(arrow, 50)
            assertEquals(emptyQuiver.hitChance(), strongQuiver.hitChance())
            assertEquals(emptyQuiver.maxHit(), strongQuiver.maxHit())

            val expected = emptyQuiver.fireAtLastSuccessfulRoll()
            val actual = strongQuiver.fireAtLastSuccessfulRoll()

            assertEquals(expected.damage, actual.damage)
            assertEquals(emptyQuiver.random.damageRanges, strongQuiver.random.damageRanges)
            strongQuiver.assertSnapshot(actual)
            assertEquals(50, strongQuiver.player.worn[arrow.wearpos1]?.count)
            assertEquals(0, strongQuiver.ammoCount())
            assertEquals(1, strongQuiver.ammoRandom.draws)
        }
    }

    @Test
    fun `first failing native accuracy roll consumes final ammunition without rolling damage`() {
        for (pvp in listOf(false, true)) {
            for (thrown in listOf(false, true)) {
                val f = Fixture(pvp, thrown, count = 1)
                val chance = f.hitChance()
                assertTrue(chance in 1 until 10000)
                f.random.accuracyRoll = chance

                val hit = f.fire().single()

                assertEquals(0, hit.damage)
                f.assertSnapshot(hit)
                assertEquals(0, f.ammoCount())
                assertEquals(1, f.random.accuracyDraws)
                assertTrue(f.random.damageRanges.isEmpty())
                assertEquals(1, f.ammoRandom.draws)
                assertEquals(0, f.rangedXpGained())
            }
        }
    }

    @Test
    fun `bow without ammunition queues no hit and draws no combat or ammunition randomness`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(pvp, thrown = false, count = 0)

            assertTrue(f.fire().isEmpty())

            assertEquals(0, f.random.accuracyDraws)
            assertTrue(f.random.damageRanges.isEmpty())
            assertEquals(0, f.ammoRandom.draws)
            assertEquals(0, f.ammoCount())
            assertEquals(1, f.player.worn[f.weapon.wearpos1]?.count)
            assertEquals(0, f.rangedXpGained())
        }
    }

    @Test
    fun `two arrow shots consume exactly two arrows and the empty followup cannot fire`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(pvp, thrown = false, count = 2)
            val expectedMax = f.maxHit()
            f.random.accuracyRoll = f.hitChance() - 1
            assertEquals(expectedMax, f.fire().single().damage)
            assertEquals(1, f.ammoCount())
            f.advancePastAttackDelay()

            assertEquals(listOf(expectedMax, expectedMax), f.fire().map(Hit::damage))
            assertEquals(0, f.ammoCount())
            f.advancePastAttackDelay()

            assertEquals(2, f.fire().size)
            assertEquals(2, f.random.accuracyDraws)
            assertEquals(listOf(0..expectedMax, 0..expectedMax), f.random.damageRanges)
            assertEquals(2, f.ammoRandom.draws)
        }
    }

    private class Fixture(val pvp: Boolean, val thrown: Boolean, count: Int) {
        val events = EventBus()
        val random = ShotRandom()
        val ammoRandom = NoRecoveryRandom()
        val player = newPlayer(1, CoordGrid(2275, 4695))
        private val opponent = newPlayer(2, CoordGrid(2271, 4695))
        private val npc = Npc("npc.king_dragon", CoordGrid(2271, 4695)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
            // Isolate launch-state behaviour from the independently tested Create/XP multiplier.
            combatXpMultiplier = 1000
        }
        val weapon = item(if (thrown) "obj.rune_dart" else "obj.magic_shortbow")
        private val ammunition = if (thrown) weapon else item("obj.rune_arrow")
        private val attackType = if (thrown) RangedAttackType.Light else RangedAttackType.Standard
        private val attack = CombatAttack.Ranged(InvObj(weapon), attackType, RangedAttackStyle.Accurate)
        private val beforeXp = player.statMap.getFineXP("stat.ranged")
        private val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        private val bonuses = WornBonuses()
        private val speeds = WeaponSpeeds(styles)
        private val npcAttributes = CombatNpcAttributeCollector()
        private val rangedAttributes = CombatRangedAttributeCollector()
        private val accuracy = AccuracyFormulae(
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            mockk(), mockk(), PvNRangedAccuracy(bonuses, npcAttributes, rangedAttributes),
            PvPRangedAccuracy(bonuses, styles, rangedAttributes), mockk(), mockk(), mockk(), mockk(),
        )
        private val maxima = MaxHitFormulae(
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            PvNRangedMaxHit(bonuses, speeds, npcAttributes, rangedAttributes),
            PvPRangedMaxHit(random, bonuses, speeds, rangedAttributes, DamageReductionAttributeCollector(styles)),
            mockk(), mockk(),
        )
        private val world = mockk<WorldRepository>()
        private val manager = PlayerAttackManager(
            random, events, world, accuracy, maxima, StandardNpcHitModifier(events),
            StandardPlayerHitModifier(events), mockk<NpcInteractions>(relaxUnitFun = true), mockk(),
            mockk<PlayerInteractions>(relaxUnitFun = true), mockk(), emptySet(),
        )
        private val ammo = RangedAmmoManager(ammoRandom, events, mockk(), mockk(), mockk())
        private val npcCombat = PvNCombat(speeds, mockk(), mockk(), WeaponRegistry(), manager, ammo, mockk(), emptySet())
        private val playerCombat = PvPCombat(speeds, mockk(), mockk(), WeaponRegistry(), manager, ammo, mockk(), emptySet(), emptySet())
        private val access = ProtectedAccess(player, GameCoroutine("default-ranged-launch"), mockk())

        init {
            player.worn[weapon.wearpos1] = InvObj(weapon, if (thrown) count else 1)
            player.worn[ammunition.wearpos1] = if (count > 0) InvObj(ammunition, count) else null
            every { world.projAnim(player, npc, any<SpotanimType>(), any<String>()) } answers {
                ProjAnim.fromBoundsToNpc(player.bounds(), npc, thirdArg<SpotanimType>().id, arg<String>(3))
            }
            every { world.projAnim(player, opponent, any<SpotanimType>(), any<String>()) } answers {
                ProjAnim.fromBoundsToPlayer(player.bounds(), opponent, thirdArg<SpotanimType>().id, arg<String>(3))
            }
        }

        fun maxHit(): Int = if (pvp) {
            maxima.getRangedMaxHit(player, opponent, attackType, attack.style, 1.0, 0)
        } else {
            maxima.getRangedMaxHit(player, npc, attackType, attack.style, 1.0, 0)
        }

        fun hitChance(): Int = if (pvp) {
            accuracy.getRangedHitChance(player, opponent, attackType, attack.style, 1.0)
        } else {
            accuracy.getRangedHitChance(player, npc, attackType, attack.style, attackType, 1.0)
        }

        fun fireAtLastSuccessfulRoll(): Hit {
            val expectedMax = maxHit()
            random.accuracyRoll = hitChance() - 1
            assertTrue(random.accuracyRoll in 0 until 10000)
            return fire().single().also {
                assertEquals(expectedMax, it.damage)
                assertEquals(listOf(0..expectedMax), random.damageRanges)
                assertEquals(1, random.accuracyDraws)
            }
        }

        fun fire(): List<Hit> {
            runImmediately {
                if (pvp) playerCombat.attack(access, opponent, attack) else npcCombat.attack(access, npc, attack)
            }
            return buildList {
                if (pvp) {
                    val iterator = opponent.queueList.iterator() ?: return@buildList
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
                    }
                } else {
                    val iterator = npc.queueList.iterator() ?: return@buildList
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
                    }
                }
            }
        }

        fun assertSnapshot(hit: Hit) {
            assertEquals(HitType.Ranged, hit.type)
            assertTrue(hit.isRighthandObj(weapon), "Queued hit must retain the launched weapon")
            if (thrown) assertNull(hit.secondaryType()) else assertEquals(ammunition.id, hit.secondaryType()?.id)
        }

        fun ammoCount(): Int = player.worn[ammunition.wearpos1]?.count ?: 0
        fun rangedXpGained(): Int = player.statMap.getFineXP("stat.ranged") - beforeXp
        fun advancePastAttackDelay() { player.currentMapClock += 20; player.processedMapClock += 20 }
    }

    /** Only the random boundaries are controlled; hit chance and maximum are real native formulas. */
    private class ShotRandom : GameRandom {
        var accuracyRoll = 0
        var accuracyDraws = 0
        val damageRanges = mutableListOf<IntRange>()
        override fun of(maxExclusive: Int): Int {
            assertEquals(10000, maxExclusive, "Only the accuracy draw is allowed here")
            accuracyDraws++
            return accuracyRoll.also { require(it in 0 until maxExclusive) }
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            assertEquals(0, minInclusive)
            damageRanges += minInclusive..maxInclusive
            return maxInclusive
        }
        override fun randomDouble(): Double = error("Unexpected floating-point draw")
    }

    /** No recovery equipment is worn; deterministically destroy the spent unit instead of dropping it. */
    private class NoRecoveryRandom : GameRandom {
        var draws = 0
        override fun of(maxExclusive: Int): Int {
            assertEquals(RangedAmmunition.DEFAULT_AMMO_DROP_RATE, maxExclusive)
            draws++
            return maxExclusive - 1
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unexpected ammo range draw")
        override fun randomDouble(): Double = error("Unexpected ammo floating-point draw")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }

        private fun item(symbol: String): ItemServerType = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))

        private fun newPlayer(slot: Int, coord: CoordGrid): Player = Player().apply {
            slotId = slot
            uuid = slot.toLong()
            worldType = WorldType.MAIN
            assignUid()
            coords = coord
            currentMapClock = 100
            processedMapClock = 100
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.ranged", "stat.magic", "stat.hitpoints")) {
                statMap.setBaseLevel(stat, 99)
                statMap.setCurrentLevel(stat, 99)
                statMap.setXP(stat, PlayerSkillXPTable.getXPFromLevel(99))
            }
        }

        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Default ranged attack unexpectedly suspended" }.getOrThrow()
        }
    }
}
