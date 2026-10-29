package org.rsmod.content.bosses.kbd

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.bosses.runtime.BossDeps
import org.rsmod.api.bosses.runtime.BossEncounter
import org.rsmod.api.bosses.runtime.EffectInterpreter
import org.rsmod.api.bosses.spec.Effect
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.queue.PlayerQueueList
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid

@OptIn(InternalApi::class)
@ResourceLock("ServerCacheManager")
class KbdBreathDamageTest {
    @TestFactory
    fun `normal and special caps cover every protection combination`(): List<DynamicTest> =
        PROTECTIONS.map { protection ->
            DynamicTest.dynamicTest(protection.toString()) {
                val player = player(protection)
                for ((special, maximum) in listOf(false to protection.normalMax, true to protection.specialMax)) {
                    val maximumRandom = BoundaryRandom(maximum = true)
                    assertEquals(maximum, KbdBreathDamage(maximumRandom).roll(player, special))
                    assertEquals(if (maximum == 0) emptyList() else listOf(maximum + 1), maximumRandom.bounds)

                    val minimumRandom = BoundaryRandom(maximum = false)
                    assertEquals(0, KbdBreathDamage(minimumRandom).roll(player, special))
                }
            }
        }

    @TestFactory
    fun `charged and uncharged antifire shields protect when actually worn`(): List<DynamicTest> =
        ANTIFIRE_SHIELDS.map { shield ->
            DynamicTest.dynamicTest(shield) {
                val player = player()
                equip(player, shield)
                val damage = KbdBreathDamage(BoundaryRandom())
                assertEquals(15, damage.roll(player, special = false))
                assertEquals(10, damage.roll(player, special = true))
                VarPlayerIntMapSetter.set(player, "varbit.antifire_potion", 1)
                assertEquals(0, damage.roll(player, special = false))
                assertEquals(10, damage.roll(player, special = true))
                VarPlayerIntMapSetter.set(player, "varbit.super_antifire_potion", 1)
                assertEquals(0, damage.roll(player, special = false))
                assertEquals(10, damage.roll(player, special = true))
            }
        }

    @TestFactory
    fun `elemental shields do not grant dragonfire protection`(): List<DynamicTest> =
        listOf("obj.elemental_shield", "obj.elemental_mind_shield").map { shield ->
            DynamicTest.dynamicTest(shield) {
                val player = player()
                equip(player, shield)
                val damage = KbdBreathDamage(BoundaryRandom())
                assertEquals(65, damage.roll(player, special = false))
                assertEquals(50, damage.roll(player, special = true))
            }
        }

    @Test
    fun `a carried shield does not protect and equipment changes are read for every breath`() {
        val player = player()
        val shield = checkNotNull(ServerCacheManager.getItem("obj.antidragonbreathshield".asRSCM()))
        player.inv[0] = InvObj(shield)
        val damage = KbdBreathDamage(BoundaryRandom())
        assertEquals(65, damage.roll(player, special = false))
        assertEquals(50, damage.roll(player, special = true))
        player.inv[0] = null
        player.worn[shield.wearpos1] = InvObj(shield)
        assertEquals(15, damage.roll(player, special = false))
        assertEquals(10, damage.roll(player, special = true))
        player.worn[shield.wearpos1] = null
        player.inv[0] = InvObj(shield)
        assertEquals(65, damage.roll(player, special = false))
        assertEquals(50, damage.roll(player, special = true))
    }

    @TestFactory
    fun `production projectiles preserve caps through the standard hit modifier`(): List<DynamicTest> =
        BREATHS.flatMap { (ability, special) ->
            PROTECTIONS.map { protection ->
                DynamicTest.dynamicTest("$ability $protection") {
                    val fixture = EncounterFixture(protection)
                    val projectile = fixture.projectile(ability)
                    fixture.interpreter.run(null, projectile)

                    val maximum = if (special) protection.specialMax else protection.normalMax
                    fixture.assertQueuedDamage(maximum)
                    verify(exactly = 1) { fixture.worldRepo.projAnim(any<ProjAnim>()) }
                    val emitted = fixture.projectiles.single()
                    assertEquals(projectile.spotanim.asRSCM(), emitted.spotanim)
                    assertEquals(-fixture.player.slotId - 1, emitted.targetIndex)
                    assertEquals(emitted.serverCycles, fixture.hitQueue().remainingCycles)
                    assertTrue(emitted.serverCycles > 0)
                }
            }
        }

    @TestFactory
    fun `production special hit expressions bypass a second prayer reduction outside projectiles`(): List<DynamicTest> =
        BREATHS.filter { it.second }.map { (ability, _) ->
            DynamicTest.dynamicTest(ability) {
                val fixture = EncounterFixture(PROTECTIONS[10])
                fixture.interpreter.run(null, checkNotNull(fixture.projectile(ability).hit))
                fixture.assertQueuedDamage(15)
                assertEquals(1, fixture.hitQueue().remainingCycles)
                verify(exactly = 0) { fixture.worldRepo.projAnim(any<ProjAnim>()) }
            }
        }

    private class EncounterFixture(protection: Protection) {
        val player = player(protection)
        val random = BoundaryRandom()
        val projectiles = mutableListOf<ProjAnim>()
        val worldRepo = mockk<WorldRepository>()
        private val clock = MapClock(100)
        private val worldQueues = WorldQueueList()
        private val modifier = StandardPlayerHitModifier(EventBus())
        private val deps = mockk<BossDeps>()
        private val npc = Npc(
            checkNotNull(ServerCacheManager.getNpc("npc.king_dragon".asRSCM())),
            CoordGrid(2271, 4695, 0),
        ).apply {
            slotId = 1
            assignUid()
        }

        init {
            every { deps.random } returns random
            every { deps.worldRepo } returns worldRepo
            every { deps.mapClock } returns clock
            every { deps.worldQueues } returns worldQueues
            every { deps.playerHitModifier } returns modifier
            every { worldRepo.projAnim(any<ProjAnim>()) } answers {
                projectiles += firstArg<ProjAnim>()
                Unit
            }
        }

        private val spec = KingBlackDragon(deps, KbdBreathDamage(random)).spec
        private val encounter = BossEncounter(npc, spec, clock)
        val interpreter = EffectInterpreter(npc, player, spec, encounter, deps)

        fun projectile(ability: String): Effect.Projectile =
            effects(spec.abilities.getValue(ability)).filterIsInstance<Effect.Projectile>().single()

        fun assertQueuedDamage(expected: Int) {
            val hit = hitQueue().args as Hit
            assertEquals(expected, hit.damage)
            assertEquals(HitType.Typeless, hit.type)
            assertTrue(hit.isFromNpc)
            assertEquals(if (expected == 0) emptyList() else listOf(expected + 1), random.bounds)
            assertEquals(1, player.queueList.count("queue.com_retaliate_npc"))
        }

        fun hitQueue(): PlayerQueueList.Queue {
            val queues = mutableListOf<PlayerQueueList.Queue>()
            val iterator = checkNotNull(player.queueList.iterator())
            while (iterator.hasNext()) {
                val queue = iterator.next()
                if (queue.id == "queue.hit".asRSCM()) queues += queue
            }
            return queues.single()
        }

        private fun effects(effect: Effect): List<Effect> = when (effect) {
            is Effect.Sequence -> effect.effects.flatMap(::effects)
            is Effect.Parallel -> effect.effects.flatMap(::effects)
            else -> listOf(effect)
        }
    }

    private class BoundaryRandom(private val maximum: Boolean = true) : GameRandom {
        val bounds = mutableListOf<Int>()

        override fun of(maxExclusive: Int): Int {
            require(maxExclusive > 0)
            bounds += maxExclusive
            return if (maximum) maxExclusive - 1 else 0
        }

        override fun of(minInclusive: Int, maxInclusive: Int): Int =
            error("Unexpected additional range roll: $minInclusive..$maxInclusive")

        override fun randomDouble(): Double = error("Unexpected additional floating-point roll")
    }

    private data class Protection(val flags: Int, val normalMax: Int, val specialMax: Int) {
        val shield: Boolean get() = flags and 1 != 0
        val prayer: Boolean get() = flags and 2 != 0
        val potion: Boolean get() = flags and 4 != 0
        val superPotion: Boolean get() = flags and 8 != 0

        override fun toString(): String =
            "shield=$shield prayer=$prayer antifire=$potion superAntifire=$superPotion"
    }

    companion object {
        private val PROTECTIONS = listOf(
            Protection(0, 65, 50), Protection(1, 15, 10),
            Protection(2, 20, 15), Protection(3, 15, 10),
            Protection(4, 50, 50), Protection(5, 0, 10),
            Protection(6, 5, 15), Protection(7, 0, 10),
            Protection(8, 0, 50), Protection(9, 0, 10),
            Protection(10, 0, 15), Protection(11, 0, 10),
            Protection(12, 0, 50), Protection(13, 0, 10),
            Protection(14, 0, 15), Protection(15, 0, 10),
        )
        private val BREATHS = listOf(
            "dragonfire" to false,
            "poison_breath" to true,
            "freeze_breath" to true,
            "shock_breath" to true,
        )
        private val ANTIFIRE_SHIELDS = listOf(
            "obj.antidragonbreathshield",
            "obj.dragonfire_shield",
            "obj.dragonfire_shield_uncharged",
            "obj.dragonfire_ward",
            "obj.dragonfire_ward_uncharged",
            "obj.wyvern_shield",
            "obj.wyvern_shield_uncharged",
        )

        private fun player(protection: Protection = PROTECTIONS[0]): Player = Player().apply {
            worldType = WorldType.MAIN
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            coords = CoordGrid(2278, 4695, 0)
            slotId = 2
            uuid = 100
            assignUid()
            statMap.setBaseLevel("stat.hitpoints", 99)
            statMap.setCurrentLevel("stat.hitpoints", 99)
            if (protection.shield) equip(this, "obj.antidragonbreathshield")
            VarPlayerIntMapSetter.set(this, "varbit.prayer_protectfrommagic", if (protection.prayer) 1 else 0)
            VarPlayerIntMapSetter.set(this, "varbit.antifire_potion", if (protection.potion) 1 else 0)
            VarPlayerIntMapSetter.set(this, "varbit.super_antifire_potion", if (protection.superPotion) 1 else 0)
        }

        private fun equip(player: Player, shield: String) {
            val type = checkNotNull(ServerCacheManager.getItem(shield.asRSCM()))
            require(type.wearpos1 >= 0) { "$shield must have a native equipment slot" }
            player.worn[type.wearpos1] = InvObj(type)
        }

        @JvmStatic
        @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
        }
    }
}
