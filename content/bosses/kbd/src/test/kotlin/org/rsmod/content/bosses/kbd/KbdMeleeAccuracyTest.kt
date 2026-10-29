package org.rsmod.content.bosses.kbd

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.bosses.runtime.BossDeps
import org.rsmod.api.bosses.runtime.BossEncounter
import org.rsmod.api.bosses.runtime.EffectInterpreter
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.formulas.NpcIncomingDamageReducer
import org.rsmod.api.combat.formulas.accuracy.melee.NvPMeleeAccuracy
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.melee.NvPMeleeMaxHit
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@ResourceLock("ServerCacheManager")
class KbdMeleeAccuracyTest {
    @Test
    fun `a failed accuracy roll queues a miss without rolling damage`() {
        val fixture = Fixture()
        val chance = fixture.hitChance()
        assertTrue(chance in 1 until HIT_CHANCE_SCALE)

        assertEquals(0, fixture.attack(chance).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE), fixture.random.bounds)
        assertEquals(0, fixture.attack(HIT_CHANCE_SCALE - 1).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE), fixture.random.bounds)
    }

    @Test
    fun `the last successful accuracy roll still rolls damage exactly once`() {
        val fixture = Fixture()
        assertEquals(25, fixture.attack(fixture.hitChance() - 1, 25).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE, 26), fixture.random.bounds)
    }

    @Test
    fun `successful melee rolls include zero and twenty five`() {
        val fixture = Fixture()
        for (damage in 0..25) {
            assertEquals(damage, fixture.attack(0, damage).damage)
            assertEquals(listOf(HIT_CHANCE_SCALE, 26), fixture.random.bounds)
        }
    }

    @Test
    fun `native stab armour changes whether the same melee roll lands`() {
        val fixture = Fixture()
        val unarmouredChance = fixture.hitChance()
        val armour = checkNotNull(ServerCacheManager.getItem("obj.rune_chainbody".asRSCM()))
        fixture.player.worn[armour.wearpos1] = InvObj(armour)
        val armouredChance = fixture.hitChance()
        assertTrue(armouredChance < unarmouredChance)
        assertEquals(0, fixture.attack(armouredChance).damage)

        fixture.player.worn[armour.wearpos1] = null
        assertEquals(25, fixture.attack(armouredChance, 25).damage)
    }

    @Test
    fun `melee checks stab defence rather than slash defence`() {
        val fixture = Fixture()
        fixture.equip("obj.rune_chainbody")
        val stabChance = fixture.hitChance(MeleeAttackType.Stab)
        val slashChance = fixture.hitChance(MeleeAttackType.Slash)
        assertTrue(stabChance > slashChance, "the native chainbody must distinguish stab and slash defence")

        assertEquals(25, fixture.attack(slashChance, 25).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE, 26), fixture.random.bounds)
    }

    @Test
    fun `visible Defence level changes accuracy while base Defence remains fixed`() {
        val fixture = Fixture()
        val highDefenceChance = fixture.hitChance()
        fixture.player.statMap.setCurrentLevel("stat.defence", 1)
        val lowDefenceChance = fixture.hitChance()
        assertTrue(highDefenceChance < lowDefenceChance)
        assertEquals(25, fixture.attack(highDefenceChance, 25).damage)

        fixture.player.statMap.setCurrentLevel("stat.defence", 99)
        assertEquals(0, fixture.attack(highDefenceChance).damage)
    }

    @Test
    fun `draining the live NPC attack level affects its next melee roll`() {
        val fixture = Fixture()
        val undrainedChance = fixture.hitChance()
        val initialAttack = fixture.npc.attackLvl
        fixture.npc.attackLvl = 1
        val drainedChance = fixture.hitChance()
        assertTrue(drainedChance < undrainedChance)
        assertEquals(0, fixture.attack(drainedChance).damage)

        fixture.npc.attackLvl = initialAttack
        assertEquals(25, fixture.attack(drainedChance, 25).damage)
    }

    @Test
    fun `defensive prayers influence the real accuracy calculation`() {
        val fixture = Fixture()
        val unprotectedChance = fixture.hitChance()
        VarPlayerIntMapSetter.set(fixture.player, "varbit.prayer_steelskin", 1)
        val protectedChance = fixture.hitChance()
        assertTrue(protectedChance < unprotectedChance)
        assertEquals(0, fixture.attack(protectedChance).damage)

        VarPlayerIntMapSetter.set(fixture.player, "varbit.prayer_steelskin", 0)
        assertEquals(25, fixture.attack(protectedChance, 25).damage)
    }

    @Test
    fun `Protect from Melee blocks a successful hit and Protect from Magic does not`() {
        val fixture = Fixture()
        VarPlayerIntMapSetter.set(fixture.player, "varbit.prayer_protectfrommelee", 1)
        assertEquals(0, fixture.attack(0, 25).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE, 26), fixture.random.bounds)

        VarPlayerIntMapSetter.set(fixture.player, "varbit.prayer_protectfrommelee", 0)
        VarPlayerIntMapSetter.set(fixture.player, "varbit.prayer_protectfrommagic", 1)
        assertEquals(25, fixture.attack(0, 25).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE, 26), fixture.random.bounds)
    }

    @Test
    fun `current NPC Strength determines the raw melee maximum`() {
        val fixture = Fixture()
        assertEquals(25, fixture.attack(0, 25).damage)
        fixture.npc.strengthLvl = 216
        assertEquals(23, fixture.attack(0, 23).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE, 24), fixture.random.bounds)
    }

    @Test
    fun `Elysian mitigation transforms rolled melee damage exactly once`() {
        val fixture = Fixture()
        fixture.equip("obj.elysian")
        assertEquals(18, fixture.attack(0, 25, 0).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE, 26, 10), fixture.random.bounds)
        assertEquals(25, fixture.attack(0, 25, 7).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE, 26, 10), fixture.random.bounds)
        assertEquals(0, fixture.attack(0, 0).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE, 26), fixture.random.bounds)
    }

    @Test
    fun `Elysian mitigates the sample before the launch time hitpoints cap`() {
        val fixture = Fixture()
        fixture.equip("obj.elysian")
        fixture.player.statMap.setCurrentLevel("stat.hitpoints", 10)
        assertEquals(10, fixture.attack(0, 25, 0).damage)
        assertEquals(listOf(HIT_CHANCE_SCALE, 26, 10), fixture.random.bounds)
    }

    private class Fixture {
        val random = ScriptedRandom()
        val player = Player().apply {
            worldType = WorldType.MAIN
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            coords = CoordGrid(2276, 4695, 0)
            slotId = 2
            uuid = 100
            assignUid()
            statMap.setBaseLevel("stat.hitpoints", 99)
            statMap.setCurrentLevel("stat.hitpoints", 99)
            statMap.setBaseLevel("stat.defence", 99)
            statMap.setCurrentLevel("stat.defence", 99)
        }
        val npc = Npc(
            checkNotNull(ServerCacheManager.getNpc("npc.king_dragon".asRSCM())),
            CoordGrid(2271, 4695, 0),
        ).apply {
            slotId = 1
            assignUid()
        }
        private val events = EventBus()
        private val clock = MapClock(100)
        private val worldQueues = WorldQueueList()
        private val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        private val accuracy = AccuracyFormulae(
            nvpMagicAccuracy = mockk(),
            nvnMagicAccuracy = mockk(),
            pvnMagicAccuracy = mockk(),
            pvpMagicAccuracy = mockk(),
            nvpMeleeAccuracy = NvPMeleeAccuracy(WornBonuses(), styles),
            nvnMeleeAccuracy = mockk(),
            pvnMeleeAccuracy = mockk(),
            pvpMeleeAccuracy = mockk(),
            nvpRangedAccuracy = mockk(),
            nvnRangedAccuracy = mockk(),
            pvnRangedAccuracy = mockk(),
            pvpRangedAccuracy = mockk(),
            nvpMultiAccuracy = mockk(),
            nvnMultiAccuracy = mockk(),
            pvnMultiAccuracy = mockk(),
            pvpMultiAccuracy = mockk(),
        )
        private val bonuses = WornBonuses()
        private val reductions = DamageReductionAttributeCollector(styles)
        private val maxima = MaxHitFormulae(
            pvnMagicMaxHit = mockk(), pvpMagicMaxHit = mockk(),
            nvpMagicMaxHit = mockk(), nvnMagicMaxHit = mockk(),
            pvnMeleeMaxHit = mockk(), pvpMeleeMaxHit = mockk(),
            nvnMeleeMaxHit = mockk(),
            nvpMeleeMaxHit = NvPMeleeMaxHit(random, bonuses, reductions),
            pvnRangedMaxHit = mockk(), pvpRangedMaxHit = mockk(),
            nvpRangedMaxHit = mockk(), nvnRangedMaxHit = mockk(),
        )
        private val modifier = StandardPlayerHitModifier(events, NpcIncomingDamageReducer(random, bonuses, reductions))
        private val deps = mockk<BossDeps>().also {
            every { it.random } returns random
            every { it.accuracy } returns accuracy
            every { it.maxHit } returns maxima
            every { it.mapClock } returns clock
            every { it.worldQueues } returns worldQueues
            every { it.playerHitModifier } returns modifier
        }
        private val spec = KingBlackDragon(deps, KbdBreathDamage(random)).spec
        private val encounter = BossEncounter(npc, spec, clock)
        private val interpreter = EffectInterpreter(npc, player, spec, encounter, deps)

        fun hitChance(type: MeleeAttackType = MeleeAttackType.Stab): Int =
            accuracy.getMeleeHitChance(npc, player, type)

        fun equip(symbol: String) {
            val type = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))
            player.worn[type.wearpos1] = InvObj(type)
        }

        fun attack(vararg rolls: Int): Hit {
            player.queueList.clear()
            random.reset(rolls)
            interpreter.run(null, spec.abilities.getValue("melee"))
            val hits = mutableListOf<Hit>()
            val iterator = checkNotNull(player.queueList.iterator())
            while (iterator.hasNext()) {
                val queue = iterator.next()
                if (queue.id == "queue.hit".asRSCM()) {
                    assertEquals(1, queue.remainingCycles)
                    hits += queue.args as Hit
                }
            }
            random.assertExhausted()
            assertEquals(1, player.queueList.count("queue.com_retaliate_npc"))
            return hits.single().also {
                assertEquals(HitType.Melee, it.type)
                assertTrue(it.isFromNpc)
            }
        }
    }

    private class ScriptedRandom : GameRandom {
        val bounds = mutableListOf<Int>()
        private val rolls = ArrayDeque<Int>()

        fun reset(values: IntArray) {
            bounds.clear()
            rolls.clear()
            rolls.addAll(values.toList())
        }

        override fun of(maxExclusive: Int): Int {
            bounds += maxExclusive
            check(rolls.isNotEmpty()) { "Unexpected extra random roll with bound $maxExclusive" }
            return rolls.removeFirst().also {
                require(it in 0 until maxExclusive) { "Roll $it outside 0 until $maxExclusive" }
            }
        }

        override fun of(minInclusive: Int, maxInclusive: Int): Int =
            error("Unexpected range roll: $minInclusive..$maxInclusive")

        override fun randomDouble(): Double = error("Unexpected floating-point roll")

        fun assertExhausted() {
            assertTrue(rolls.isEmpty(), "Not all scripted rolls were consumed: $rolls")
        }
    }

    companion object {
        private const val HIT_CHANCE_SCALE = 10_000

        @JvmStatic
        @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
        }
    }
}
