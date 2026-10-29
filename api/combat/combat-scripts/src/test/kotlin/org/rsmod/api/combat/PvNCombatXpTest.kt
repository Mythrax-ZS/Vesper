package org.rsmod.api.combat

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.aconverted.SpotanimType
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.access.StandardNpcAccessContext
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.events.NpcQueueEvents
import org.rsmod.api.npc.hit.flatArmour
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.processor.StandardNpcHitProcessor
import org.rsmod.api.npc.plugin.AiSpawnScript
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.ironman.PlayerGamemode
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionOp
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
class PvNCombatXpTest {
    @Test
    fun `ordinary melee and ranged award native negative armour damage at launch`() {
        for (ranged in listOf(false, true)) {
            val f = Fixture("npc.earth_nagua")
            assertEquals(-4, f.npc.flatArmour)
            val hp = f.npc.hitpoints

            val hit = f.attack(ranged = ranged, roll = 8)

            assertEquals(12, hit.damage)
            assertEquals(hp, f.npc.hitpoints, "XP is awarded before the pending hit lands")
            f.assertXp(if (ranged) "stat.ranged" else "stat.attack", 12 * 4.0)
            f.assertXp("stat.hitpoints", 12 * 1.33)
            assertEquals(12, f.impact().damage)
            assertEquals(hp - 12, f.npc.hitpoints)
            f.assertXp(if (ranged) "stat.ranged" else "stat.attack", 12 * 4.0)
        }
    }

    @Test
    fun `ordinary melee and ranged subtract positive armour before awarding xp`() {
        for (ranged in listOf(false, true)) {
            val f = Fixture("npc.wyrm_light")
            assertEquals(1, f.npc.flatArmour)

            assertEquals(7, f.attack(ranged = ranged, roll = 8).damage)

            f.assertXp(if (ranged) "stat.ranged" else "stat.attack", 7 * 4.0)
            f.assertXp("stat.hitpoints", 7 * 1.33)
        }
    }

    @Test
    fun `accurate zero converted to one can be absorbed without granting combat xp`() {
        for (ranged in listOf(false, true)) {
            val f = Fixture("npc.wyrm_light")

            assertEquals(0, f.attack(ranged = ranged, roll = 0).damage)

            f.assertNoXp()
            assertEquals(listOf(0..8), f.random.ranges)
        }
    }

    @Test
    fun `failed accuracy against negative armour gives no damage xp or damage roll`() {
        for (ranged in listOf(false, true)) {
            val f = Fixture("npc.earth_nagua")
            f.accurate = false

            assertEquals(0, f.attack(ranged = ranged).damage)

            f.assertNoXp()
            assertTrue(f.random.ranges.isEmpty())
        }
    }

    @Test
    fun `style immunity and a modifier zero remain terminal for physical damage xp`() {
        for (ranged in listOf(false, true)) {
            for (immunity in listOf(false, true)) {
                val f = Fixture("npc.earth_nagua")
                if (immunity) {
                    f.npc.vars[if (ranged) "varn.immune_ranged" else "varn.immune_melee"] = 1
                } else {
                    f.events.subscribeKeyed(NpcHitEvents.Modify::class.java, f.npc.id.toLong()) {
                        hit.damage = 0
                    }
                }

                assertEquals(0, f.attack(ranged = ranged, roll = 8).damage)

                f.assertNoXp()
            }
        }
    }

    @Test
    fun `npc current hp caps post-armour xp while queued damage remains uncapped`() {
        for (ranged in listOf(false, true)) {
            val f = Fixture("npc.earth_nagua")
            f.npc.hitpoints = 5

            assertEquals(12, f.attack(ranged = ranged, roll = 8).damage)

            f.assertXp(if (ranged) "stat.ranged" else "stat.attack", 5 * 4.0)
            f.assertXp("stat.hitpoints", 5 * 1.33)
            assertEquals(5, f.impact().damage)
            assertEquals(0, f.npc.hitpoints)
            f.assertXp(if (ranged) "stat.ranged" else "stat.attack", 5 * 4.0)
        }
    }

    @Test
    fun `melee styles distribute the modified hit rather than the raw roll`() {
        for ((style, stat) in listOf(
            MeleeAttackStyle.Accurate to "stat.attack",
            MeleeAttackStyle.Aggressive to "stat.strength",
            MeleeAttackStyle.Defensive to "stat.defence",
        )) {
            val f = Fixture("npc.earth_nagua")
            assertEquals(12, f.attack(roll = 8, meleeStyle = style).damage)
            f.assertXp(stat, 48.0)
            f.assertXp("stat.hitpoints", 15.96)
            for (other in COMBAT_STATS - setOf(stat, "stat.hitpoints")) f.assertXp(other, 0.0)
        }

        val controlled = Fixture("npc.earth_nagua")
        controlled.attack(roll = 8, meleeStyle = MeleeAttackStyle.Controlled)
        for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.hitpoints")) {
            controlled.assertXp(stat, 15.96)
        }
        controlled.assertXp("stat.ranged", 0.0)
    }

    @Test
    fun `ranged longrange divides modified damage xp between ranged and defence`() {
        val f = Fixture("npc.earth_nagua")

        assertEquals(12, f.attack(ranged = true, roll = 8, rangedStyle = RangedAttackStyle.Longrange).damage)

        f.assertXp("stat.ranged", 24.0)
        f.assertXp("stat.defence", 24.0)
        f.assertXp("stat.hitpoints", 15.96)
        f.assertXp("stat.attack", 0.0)
        f.assertXp("stat.strength", 0.0)
    }

    @Test
    fun `native npc multiplier and realm rates apply once to post-armour xp`() {
        val f = Fixture("npc.amoxliatl")
        assertEquals(-2, f.npc.flatArmour)
        assertTrue(f.npc.combatXpMultiplier > 1000)
        f.player.xpRate = 2.0
        f.player.globalXpRate = 3.0

        assertEquals(10, f.attack(roll = 8).damage)

        f.assertXp("stat.attack", 40.0)
        f.assertXp("stat.hitpoints", 13.3)
    }

    @Test
    fun `later hp loss changes the impact without rewriting already awarded xp`() {
        val f = Fixture("npc.earth_nagua")
        f.attack(ranged = true, roll = 8)
        f.assertXp("stat.ranged", 48.0)
        f.npc.hitpoints = 3

        assertEquals(3, f.impact().damage)

        f.assertXp("stat.ranged", 48.0)
        f.assertXp("stat.hitpoints", 15.96)
    }

    @Test
    fun `solo ironman ownership block still suppresses post-armour combat xp`() {
        val f = Fixture("npc.earth_nagua")
        f.player.gamemode = PlayerGamemode.IRONMAN
        f.npc.damageContributions.record(Player().apply { uuid = 2 }, 1)

        assertEquals(12, f.attack(roll = 8).damage)

        f.assertNoXp()
    }

    private class Fixture(symbol: String) {
        val events = EventBus()
        val random = DamageRandom()
        val player = Player().apply {
            slotId = 1
            uuid = 1
            worldType = WorldType.MAIN
            assignUid()
            coords = CoordGrid(2275, 4695)
            currentMapClock = 100
            processedMapClock = 100
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            for (stat in COMBAT_STATS) {
                statMap.setBaseLevel(stat, 99)
                statMap.setCurrentLevel(stat, 99)
                statMap.setXP(stat, PlayerSkillXPTable.getXPFromLevel(99))
            }
        }
        val npc = Npc(symbol, CoordGrid(2271, 4695)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
        }
        private val beforeXp = COMBAT_STATS.associateWith { player.statMap.getFineXP(it) }
        private val players = PlayerList().also { it[player.slotId] = player }
        private val accuracy = mockk<AccuracyFormulae>()
        private val maxima = mockk<MaxHitFormulae>()
        private val interactions = mockk<NpcInteractions>()
        private val world = mockk<WorldRepository>()
        private val speeds = mockk<WeaponSpeeds>()
        private val ammo = mockk<RangedAmmoManager>(relaxUnitFun = true)
        private val modifier = StandardNpcHitModifier(events)
        private val manager = PlayerAttackManager(
            random, events, world, accuracy, maxima, modifier, StandardPlayerHitModifier(events),
            interactions, mockk(), mockk(), mockk(), emptySet(),
        )
        private val combat = PvNCombat(
            speeds, mockk(), mockk(), WeaponRegistry(), manager, ammo, mockk(), emptySet(),
        )
        private val access = ProtectedAccess(player, GameCoroutine("pvn-xp-attack"), mockk())
        private val impacts = mutableListOf<Hit>()
        var accurate = true

        init {
            val scripts = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
            with(AiSpawnScript()) { scripts.startup() }
            with(NpcHitScript()) { scripts.startup() }
            events.publish(NpcStateEvents.Create(npc))
            assertTrue(npc.combatXpMultiplier > 0, "NPC Create must initialize its real XP multiplier")
            events.subscribeKeyed(NpcHitEvents.Impact::class.java, npc.id.toLong()) { impacts += hit }
            every { speeds.actual(player) } returns 4
            every { interactions.interact(player, npc, InteractionOp.Op2) } just runs
            every { accuracy.rollMeleeAccuracy(player, npc, any(), any(), any(), any(), random) } answers { accurate }
            every { accuracy.rollRangedAccuracy(player, npc, any(), any(), any(), any(), random) } answers { accurate }
            every { maxima.getMeleeMaxHit(player, npc, any(), any(), any(), any()) } returns 8
            every { maxima.getRangedMaxHit(player, npc, any(), any(), any(), any()) } returns 8
            every { ammo.attemptAmmoUsage(player, any(), any()) } returns true
            every { world.projAnim(player, npc, any<SpotanimType>(), any<String>()) } answers {
                ProjAnim.fromBoundsToNpc(player.bounds(), npc, thirdArg<SpotanimType>().id, arg<String>(3))
            }
        }

        fun attack(
            ranged: Boolean = false,
            roll: Int = 8,
            meleeStyle: MeleeAttackStyle = MeleeAttackStyle.Accurate,
            rangedStyle: RangedAttackStyle = RangedAttackStyle.Accurate,
        ): Hit {
            if (accurate) random.enqueue(roll)
            val attack = if (ranged) {
                val bow = checkNotNull(ServerCacheManager.getItem("obj.shortbow".asRSCM()))
                val arrow = checkNotNull(ServerCacheManager.getItem("obj.bronze_arrow".asRSCM()))
                player.worn[bow.wearpos1] = InvObj(bow)
                player.worn[arrow.wearpos1] = InvObj(arrow, 20)
                CombatAttack.Ranged(InvObj(bow), RangedAttackType.Standard, rangedStyle)
            } else {
                CombatAttack.Melee(null, MeleeAttackType.Stab, meleeStyle, CombatStance.Stance1)
            }
            runImmediately { combat.attack(access, npc, attack) }
            random.assertExhausted()
            verify(exactly = 1) { interactions.interact(player, npc, InteractionOp.Op2) }
            val iterator = checkNotNull(npc.queueList.iterator())
            val hits = buildList {
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
                }
            }
            return hits.single().also { assertEquals(if (ranged) HitType.Ranged else HitType.Melee, it.type) }
        }

        fun assertXp(stat: String, baseXp: Double) {
            val multiplier = npc.combatXpMultiplier / 1000.0
            val before = beforeXp.getValue(stat)
            val expected = (before + baseXp * multiplier * (player.xpRate * player.globalXpRate) * 10.0).toInt() - before
            assertEquals(expected, player.statMap.getFineXP(stat) - beforeXp.getValue(stat), stat)
        }

        fun assertNoXp() { for (stat in COMBAT_STATS) assertXp(stat, 0.0) }

        fun impact(): Hit {
            val iterator = checkNotNull(npc.queueList.iterator())
            val hits = buildList {
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
                }
            }
            npc.queueList.removeAll("queue.hit")
            val processor = StandardNpcHitProcessor(players, events, emptySet(), emptySet())
            val access = StandardNpcAccess(
                npc, GameCoroutine("pvn-xp-impact"), StandardNpcAccessContext({ random }, { modifier }, { processor }),
            )
            runImmediately {
                events.publish(access, NpcQueueEvents.Default(npc, hits.single(), "queue.hit".asRSCM()))
            }
            return impacts.single()
        }
    }

    private class DamageRandom : GameRandom {
        val ranges = mutableListOf<IntRange>()
        private val values = ArrayDeque<Int>()
        fun enqueue(value: Int) { values.addLast(value) }
        fun assertExhausted() { assertTrue(values.isEmpty(), "Unused damage draws: $values") }
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            ranges += minInclusive..maxInclusive
            assertEquals(0..8, minInclusive..maxInclusive)
            return values.removeFirst().also { require(it in minInclusive..maxInclusive) }
        }
        override fun of(maxExclusive: Int): Int = error("Unexpected exclusive draw: $maxExclusive")
        override fun randomDouble(): Double = error("Unexpected floating-point draw")
    }

    companion object {
        private val COMBAT_STATS = listOf("stat.attack", "stat.strength", "stat.defence", "stat.ranged", "stat.magic", "stat.hitpoints")

        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }

        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Combat unexpectedly suspended" }.getOrThrow()
        }
    }
}
