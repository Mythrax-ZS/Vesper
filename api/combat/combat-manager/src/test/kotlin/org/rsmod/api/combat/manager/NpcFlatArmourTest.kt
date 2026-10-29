package org.rsmod.api.combat.manager

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.access.StandardNpcAccessContext
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.events.NpcQueueEvents
import org.rsmod.api.npc.hit.clearFlatArmourOverride
import org.rsmod.api.npc.hit.flatArmour
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.processor.StandardNpcHitProcessor
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.hit.NpcFlatArmourPolicy
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class NpcFlatArmourTest {
    @Test
    fun `packed NPC baselines affect real melee and ranged queues without a varn initializer`() {
        // These expectations exercise the packed enum's signed values and absent-key default
        // through production damage. No test fixture injects the corresponding armour values.
        for ((symbol, expectedDamage) in listOf(
            "npc.wyrm_light" to 7,
            "npc.earth_nagua" to 12,
            "npc.amoxliatl" to 10,
            "npc.king_dragon" to 8,
        )) {
            for (style in listOf(HitType.Melee, HitType.Ranged)) {
                val f = Fixture(symbol)

                assertEquals(expectedDamage, f.queue(style, 8).damage, "$symbol $style")
                assertEquals(listOf(expectedDamage), f.impact().map { it.damage })
                assertEquals(f.startHp - expectedDamage, f.npc.hitpoints)
                assertFalse(f.hasOverride(), "Reading a cache baseline must not create an override")
            }
        }
    }

    @Test
    fun `Dusk phase four does not inherit first-phase armour after a native type change`() {
        val f = Fixture("npc.gargboss_dusk_phase2_attacking")
        assertEquals(9, f.queue(HitType.Melee, 8).damage)
        f.impact()

        f.morph("npc.gargboss_dusk_phase4")
        assertEquals(8, f.queue(HitType.Melee, 8).damage)
        f.impact()

        f.npc.resetTransmog()
        assertEquals(9, f.queue(HitType.Melee, 8).damage)
        f.impact()
        assertFalse(f.hasOverride())
    }

    @Test
    fun `an explicitly present zero overrides a negative cache baseline`() {
        val f = Fixture("npc.amoxliatl")
        assertFalse(f.hasOverride())
        assertEquals(-2, f.npc.flatArmour)

        f.npc.flatArmour = 0

        assertTrue(f.hasOverride())
        assertEquals(0, f.npc.flatArmour)
        repeat(2) {
            assertEquals(8, f.queue(HitType.Melee, 8).damage)
            f.impact()
            assertTrue(f.hasOverride())
            assertEquals(0, f.npc.flatArmour)
        }
        assertEquals(f.startHp - 16, f.npc.hitpoints)
    }

    @Test
    fun `positive and negative legacy varn overrides persist across physical hits`() {
        for ((symbol, override, expectedDamage) in listOf(
            Triple("npc.wyrm_light", -3, 11),
            Triple("npc.earth_nagua", 5, 3),
        )) {
            val f = Fixture(symbol)
            f.npc.vars["varn.flat_armour"] = override

            repeat(3) {
                assertEquals(expectedDamage, f.queue(HitType.Ranged, 8).damage)
                f.impact()
                assertEquals(override, f.npc.flatArmour)
                assertTrue(f.hasOverride())
            }
            assertEquals(f.startHp - 3 * expectedDamage, f.npc.hitpoints)
        }
    }

    @Test
    fun `clear remove and null restore the cache baseline after an override`() {
        val f = Fixture("npc.amoxliatl")
        f.npc.flatArmour = 7
        assertEquals(1, f.queue(HitType.Melee, 8).damage)
        f.impact()

        f.npc.clearFlatArmourOverride()
        assertFalse(f.hasOverride())
        assertEquals(10, f.queue(HitType.Melee, 8).damage)
        f.impact()

        f.npc.vars["varn.flat_armour"] = 0
        assertEquals(8, f.queue(HitType.Melee, 8).damage)
        f.impact()
        f.npc.vars.remove("varn.flat_armour")
        assertFalse(f.hasOverride())
        assertEquals(10, f.queue(HitType.Melee, 8).damage)
        f.impact()

        f.npc.flatArmour = -7
        f.npc.vars["varn.flat_armour"] = null
        assertFalse(f.hasOverride())
        assertEquals(10, f.queue(HitType.Melee, 8).damage)
        f.impact()
    }

    @Test
    fun `explicit override persists through morph until clear reveals current type baseline`() {
        val f = Fixture("npc.wyrm_light")
        f.npc.flatArmour = 0
        f.morph("npc.earth_nagua")

        assertEquals(8, f.queue(HitType.Melee, 8).damage)
        f.impact()
        assertTrue(f.hasOverride())

        f.npc.clearFlatArmourOverride()
        assertEquals(12, f.queue(HitType.Melee, 8).damage)
        f.impact()

        f.npc.resetTransmog()
        assertEquals(7, f.queue(HitType.Melee, 8).damage)
        f.impact()
        assertFalse(f.hasOverride())
    }

    @Test
    fun `respawn clears override and transformed baseline using the production reset method`() {
        val f = Fixture("npc.wyrm_light")
        f.morph("npc.earth_nagua")
        f.npc.flatArmour = -9
        assertEquals(17, f.queue(HitType.Melee, 8).damage)
        f.impact()

        // NpcRegistry.respawn invokes this same reset before publishing its Respawn event.
        f.npc.setRespawnValues()

        assertNull(f.npc.transmog)
        assertFalse(f.hasOverride())
        assertEquals(f.startHp, f.npc.hitpoints)
        assertEquals(7, f.queue(HitType.Melee, 8).damage)
        assertEquals(listOf(7), f.impact().map { it.damage })
        assertEquals(f.startHp - 7, f.npc.hitpoints)
    }

    @Test
    fun `a queued hit retains launch armour while the next hit uses the new visible type`() {
        val f = Fixture("npc.wyrm_light")
        val beforeMorph = f.queue(HitType.Melee, 8)
        f.morph("npc.earth_nagua")
        val afterMorph = f.queue(HitType.Melee, 8)

        assertEquals(7, beforeMorph.damage)
        assertEquals(12, afterMorph.damage)
        assertEquals(listOf(7, 12), f.impact().map { it.damage })
        assertEquals(f.startHp - 19, f.npc.hitpoints)
        assertFalse(f.hasOverride())
    }

    @Test
    fun `native armour stays active on subsequent hits without copying into the override`() {
        for ((symbol, expectedDamage) in listOf("npc.wyrm_light" to 1, "npc.earth_nagua" to 6)) {
            val f = Fixture(symbol)
            repeat(3) {
                assertEquals(expectedDamage, f.queue(HitType.Melee, 2).damage)
                f.impact()
                assertFalse(f.hasOverride())
            }
            assertEquals(f.startHp - 3 * expectedDamage, f.npc.hitpoints)
        }
    }

    @Test
    fun `cache baseline does not create damage from unannotated zero or explicit splash`() {
        for (symbol in listOf("npc.wyrm_light", "npc.earth_nagua")) {
            val f = Fixture(symbol)
            for (style in listOf(HitType.Melee, HitType.Ranged, HitType.Magic)) {
                val hit = f.queue(style, 0)
                assertEquals(0, hit.damage)
                assertFalse(hit.isMagicSplash)
            }
            val splash = f.queue(HitType.Magic, 0, splash = true)
            assertEquals(0, splash.damage)
            assertTrue(splash.isMagicSplash)
            assertEquals(listOf(0, 0, 0, 0), f.impact().map { it.damage })
            assertEquals(f.startHp, f.npc.hitpoints)
            assertFalse(f.hasOverride())
        }
    }

    @Test
    fun `magic and typeless damage ignore loaded armour while style immunity remains terminal`() {
        val f = Fixture("npc.earth_nagua")
        assertEquals(8, f.queue(HitType.Magic, 8).damage)
        assertEquals(8, f.queue(HitType.Typeless, 8).damage)
        f.npc.vars["varn.immune_melee"] = 1
        assertEquals(0, f.queue(HitType.Melee, 8).damage)

        assertEquals(listOf(8, 8, 0), f.impact().map { it.damage })
        assertEquals(f.startHp - 16, f.npc.hitpoints)
        assertEquals(-4, f.npc.flatArmour)
        assertFalse(f.hasOverride())
    }

    @Test
    fun `ranged bounce policy excludes only negative armour without changing subsequent hits`() {
        val f = Fixture("npc.earth_nagua")
        val ordinary = f.ranged(8)
        val bounce = f.ranged(8, NpcFlatArmourPolicy.IgnoreNegative)
        val following = f.ranged(8)

        assertEquals(listOf(12, 8, 12), listOf(ordinary.damage, bounce.damage, following.damage))
        assertEquals(listOf(12, 8, 12), f.impact().map { it.damage })
        assertEquals(f.startHp - 32, f.npc.hitpoints)
        assertEquals(-4, f.npc.flatArmour)
        assertFalse(f.hasOverride(), "Per-hit eligibility must not create transient NPC state")
    }

    @Test
    fun `ranged bounce retains positive armour and can still be reduced to zero`() {
        val f = Fixture("npc.wyrm_light")
        assertEquals(7, f.ranged(8, NpcFlatArmourPolicy.IgnoreNegative).damage)
        assertEquals(0, f.ranged(1, NpcFlatArmourPolicy.IgnoreNegative).damage)
        assertEquals(7, f.ranged(8).damage)
        assertEquals(listOf(7, 0, 7), f.impact().map { it.damage })
        assertFalse(f.hasOverride())
    }

    @Test
    fun `bounce eligibility respects zero positive and negative varn overrides without consuming them`() {
        val f = Fixture("npc.earth_nagua")
        for ((override, bounceDamage, ordinaryDamage) in listOf(
            Triple(0, 8, 8), Triple(3, 5, 5), Triple(-6, 8, 14),
        )) {
            f.npc.flatArmour = override
            assertEquals(bounceDamage, f.ranged(8, NpcFlatArmourPolicy.IgnoreNegative).damage)
            assertEquals(ordinaryDamage, f.ranged(8).damage)
            assertEquals(override, f.npc.flatArmour)
            assertTrue(f.hasOverride())
            f.npc.queueList.removeAll("queue.hit")
        }
        f.npc.clearFlatArmourOverride()
        assertEquals(8, f.ranged(8, NpcFlatArmourPolicy.IgnoreNegative).damage)
        assertEquals(12, f.ranged(8).damage)
        assertFalse(f.hasOverride())
    }

    @Test
    fun `bounce policy keeps damage hooks immunity and zero preservation in the normal pipeline`() {
        val f = Fixture("npc.earth_nagua")
        var hooks = 0
        f.events.subscribeKeyed(NpcHitEvents.Modify::class.java, f.npc.id.toLong()) {
            hooks++
            hit.damage /= 2
        }
        assertEquals(4, f.ranged(8, NpcFlatArmourPolicy.IgnoreNegative).damage)
        assertEquals(8, f.ranged(8).damage)
        assertEquals(0, f.ranged(1, NpcFlatArmourPolicy.IgnoreNegative).damage)
        assertEquals(0, f.ranged(0, NpcFlatArmourPolicy.IgnoreNegative).damage)
        f.npc.vars["varn.immune_ranged"] = 1
        assertEquals(0, f.ranged(8, NpcFlatArmourPolicy.IgnoreNegative).damage)
        assertEquals(5, hooks, "Each queue must invoke the ordinary modifier hook once")
        assertEquals(listOf(4, 8, 0, 0, 0), f.impact().map { it.damage })
        assertEquals(5, hooks, "Impact must not rerun the launch modifier")
        assertEquals(-4, f.npc.flatArmour)
        assertFalse(f.hasOverride())
    }

    private class Fixture(symbol: String) {
        val events = EventBus()
        val source = Player().apply {
            slotId = 1
            uuid = 1
            worldType = WorldType.MAIN
            assignUid()
            currentMapClock = 100
            processedMapClock = 100
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
        }
        private val players = PlayerList().also { it[source.slotId] = source }
        val npc = Npc(type(symbol), CoordGrid(2271, 4695)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
        }
        val startHp = npc.hitpoints
        private val modifier = StandardNpcHitModifier(events)
        private val manager = PlayerAttackManager(
            mockk(), events, mockk(), mockk(), mockk(), modifier, StandardPlayerHitModifier(events),
            mockk(), mockk(), mockk(), mockk(), emptySet(),
        )
        private val processor = StandardNpcHitProcessor(players, events, emptySet(), emptySet())
        private val access = StandardNpcAccess(
            npc, GameCoroutine("npc-flat-armour"),
            StandardNpcAccessContext({ mockk() }, { modifier }, { processor }),
        )
        private val impacts = mutableListOf<Hit>()
        private val observedTypes = mutableSetOf<Int>()

        init {
            val context = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
            with(NpcHitScript()) { context.startup() }
        }

        fun morph(symbol: String) { npc.transmog(type(symbol), Int.MAX_VALUE) }

        fun hasOverride(): Boolean = checkNotNull(ServerCacheManager.getVarn("varn.flat_armour".asRSCM())) in npc.vars

        fun queue(style: HitType, damage: Int, splash: Boolean = false): Hit =
            npc.queueHit(source, 1, style, damage, modifier, isMagicSplash = splash)

        fun ranged(damage: Int, policy: NpcFlatArmourPolicy? = null): Hit =
            if (policy == null) {
                manager.queueRangedHit(source, npc, null, damage, 30, 1)
            } else {
                manager.queueRangedHit(source, npc, null, damage, 30, 1, policy)
            }

        fun impact(): List<Hit> {
            if (observedTypes.add(npc.visType.id)) {
                events.subscribeKeyed(NpcHitEvents.Impact::class.java, npc.visType.id.toLong()) { impacts += hit }
            }
            val iterator = checkNotNull(npc.queueList.iterator())
            val hits = buildList {
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
                }
            }
            npc.queueList.removeAll("queue.hit")
            impacts.clear()
            for (hit in hits) {
                runImmediately {
                    assertTrue(events.publish(access, NpcQueueEvents.Default(npc, hit, "queue.hit".asRSCM())))
                }
            }
            assertEquals(hits.size, impacts.size)
            return impacts.toList()
        }

        private fun type(symbol: String) = checkNotNull(ServerCacheManager.getNpc(symbol.asRSCM()))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }

        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Hit processing unexpectedly suspended" }.getOrThrow()
        }
    }
}
