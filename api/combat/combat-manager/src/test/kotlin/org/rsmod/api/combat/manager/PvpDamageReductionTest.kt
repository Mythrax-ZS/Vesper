package org.rsmod.api.combat.manager

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.formulas.PvpIncomingDamageReducer
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.cheat.adminGodMode
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.events.PlayerQueueEvents
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContext
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class PvpDamageReductionTest {
    @Test
    fun `every ordinary typed player queue applies Elysian to sampled damage`() {
        for (kind in QueueKind.entries) {
            val f = Fixture()
            f.equip("obj.elysian")
            f.random.enqueue(0)

            val hit = f.queue(kind, 40)

            assertEquals(30, hit.damage, kind.toString())
            assertEquals(listOf(true), f.policies)
            assertEquals(listOf(10), f.random.bounds)
            f.random.assertConsumed()
        }
    }

    @Test
    fun `two queued hits proc independently and impact never rerolls either hit`() {
        val f = Fixture()
        f.equip("obj.elysian")
        f.random.enqueue(0)
        f.random.enqueue(9)
        val first = f.queue(QueueKind.Melee, 40)
        val second = f.queue(QueueKind.Melee, 40)
        assertEquals(listOf(30, 40), listOf(first.damage, second.damage))
        assertEquals(99, f.target.hitpoints)

        val impacts = f.impactAll()

        assertEquals(29, f.target.hitpoints)
        assertEquals(2, impacts.size)
        assertSame(first, impacts[0])
        assertSame(second, impacts[1])
        assertEquals(listOf(10, 10), f.random.bounds)
        f.random.assertConsumed()
    }

    @Test
    fun `Elysian has seven proc outcomes for each individual queued hit`() {
        val f = Fixture()
        f.equip("obj.elysian")
        val outcomes = (0..9).map { roll ->
            f.random.enqueue(roll)
            f.queue(QueueKind.Ranged, 40).damage
        }

        assertEquals(List(7) { 30 } + List(3) { 40 }, outcomes)
        assertEquals(List(10) { 10 }, f.random.bounds)
        f.random.assertConsumed()
    }

    @Test
    fun `post-roll Elysian preserves the sampled distribution instead of shrinking its bound`() {
        val f = Fixture()
        f.equip("obj.elysian")
        val outcomes = (0..3).map { raw ->
            f.random.enqueueDamage(0..3, raw)
            f.random.enqueue(0)
            val sampled = f.manager.rollSuccessfulDamage(f.source, 0..3)
            f.queue(QueueKind.Melee, sampled).damage
        }

        assertEquals(listOf(0, 0, 1, 2), outcomes)
        assertEquals(List(4) { 10 }, f.random.bounds)
        f.random.assertConsumed()
    }

    @Test
    fun `Elysian can reduce a successful one to zero without re-promoting it`() {
        val f = Fixture()
        f.equip("obj.elysian")
        val outcomes = (0..9).map { roll ->
            f.random.enqueue(roll)
            f.queue(QueueKind.Staff, 1).damage
        }

        assertEquals(List(7) { 0 } + List(3) { 1 }, outcomes)
        f.random.assertConsumed()
    }

    @Test
    fun `existing before-prayer order runs exactly once at queue construction`() {
        val f = Fixture()
        f.equip("obj.elysian")
        VarPlayerIntMapSetter.set(f.target, "varbit.prayer_protectfrommelee", 1)
        f.random.enqueue(0)

        val hit = f.queue(QueueKind.Melee, 3)
        assertEquals(1, hit.damage)
        f.impactAll()

        assertEquals(98, f.target.hitpoints)
        assertEquals(listOf(10), f.random.bounds)
        f.random.assertConsumed()
    }

    @Test
    fun `legacy direct typed and typeless player effects retain the default opt-out`() {
        val f = Fixture()
        f.equip("obj.elysian")
        val melee = f.target.queueHit(f.source, 1, HitType.Melee, 40, f.modifier)
        val typeless = f.target.queueHit(f.source, 1, HitType.Typeless, 40, f.modifier)

        assertEquals(40, melee.damage)
        assertEquals(40, typeless.damage)
        assertEquals(listOf(false, false), f.policies)
        assertTrue(f.random.bounds.isEmpty())
    }

    @Test
    fun `misses splashes and god mode consume no shield randomness`() {
        val f = Fixture()
        f.equip("obj.elysian")
        assertEquals(0, f.queue(QueueKind.Melee, 0).damage)
        val splash = f.manager.queueSplashHit(f.source, f.target, f.spell, 60, 3)
        assertEquals(0, splash.damage)
        assertTrue(splash.isMagicSplash)
        f.target.adminGodMode = true
        val immune = f.queue(QueueKind.Spell, 40)
        assertEquals(0, immune.damage)
        assertFalse(immune.isMagicSplash)
        assertTrue(f.random.bounds.isEmpty())
    }

    @Test
    fun `PvP does not acquire Dinh Justiciar or Ward reductions`() {
        val f = Fixture()
        f.equip("obj.dinhs_bulwark")
        f.equip("obj.justiciar_faceguard")
        f.equip("obj.justiciar_chestguard")
        f.equip("obj.justiciar_leg_guards")
        VarPlayerIntMapSetter.set(f.target, "varbit.ward_of_arceuus_active", 1)
        for (kind in QueueKind.entries) {
            assertEquals(40, f.queue(kind, 40).damage)
        }
        assertTrue(f.random.bounds.isEmpty())
    }

    private enum class QueueKind { Melee, Ranged, SecondaryRanged, Spell, Staff }

    private class Fixture {
        val events = EventBus()
        val source = player(1)
        val target = player(2)
        val random = ProcRandom()
        val players = PlayerList().also {
            it[source.slotId] = source
            it[target.slotId] = target
        }
        val policies = mutableListOf<Boolean>()
        val impacts = mutableListOf<Hit>()
        val spell = checkNotNull(ServerCacheManager.getItem("obj.01_wind_strike".asRSCM()))
        val modifier = StandardPlayerHitModifier(
            events,
            pvpReductions = PvpIncomingDamageReducer(random, DamageReductionAttributeCollector(mockk())),
        )
        val manager = PlayerAttackManager(
            random, events, mockk(), mockk(), mockk(), mockk(), modifier,
            mockk(), mockk(), mockk(), mockk(), emptySet(),
        )
        private val context = mockk<ProtectedAccessContext>().also {
            every { it.eventBus } returns events
            every { it.playerList } returns players
            every { it.npcList } returns NpcList()
            every { it.random } returns ImpactRandom
        }
        private val access = ProtectedAccess(target, GameCoroutine("pvp-reduction-test"), context)

        init {
            with(PlayerHitScript()) { ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup() }
            events.subscribeUnbound(PlayerHitEvents.Modify::class.java) { policies += hit.pvpDamageReduction }
            events.subscribeUnbound(PlayerHitEvents.Impact::class.java) { impacts += hit }
        }

        fun equip(symbol: String) {
            val type = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))
            target.worn[type.wearpos1] = InvObj(type)
        }

        fun queue(kind: QueueKind, damage: Int): Hit = when (kind) {
            QueueKind.Melee -> manager.queueMeleeHit(source, target, damage)
            QueueKind.Ranged -> manager.queueRangedHit(source, target, null, damage, 60, 3)
            QueueKind.SecondaryRanged -> manager.queueRangedDamage(source, target, null, damage, 3)
            QueueKind.Spell -> manager.queueMagicHit(source, target, spell, damage, 60, 3)
            QueueKind.Staff -> manager.queueMagicHit(source, target, null, damage, 60, 3)
        }

        fun impactAll(): List<Hit> {
            val iterator = checkNotNull(target.queueList.iterator())
            val queued = buildList {
                while (iterator.hasNext()) {
                    val queue = iterator.next()
                    if (queue.id == "queue.hit".asRSCM()) add(queue.args as Hit)
                }
            }
            target.queueList.removeAll("queue.hit")
            for (hit in queued) {
                runImmediately {
                    assertTrue(events.publish(access, PlayerQueueEvents.Protected(hit, "queue.hit".asRSCM())))
                }
            }
            return impacts.toList()
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

    private class ProcRandom : GameRandom {
        val bounds = mutableListOf<Int>()
        private val rolls = ArrayDeque<Int>()
        private val damageRolls = ArrayDeque<Pair<IntRange, Int>>()
        fun enqueue(roll: Int) { rolls.addLast(roll) }
        fun enqueueDamage(range: IntRange, value: Int) { damageRolls.addLast(range to value) }
        fun assertConsumed() {
            assertTrue(rolls.isEmpty(), "Unused proc rolls: $rolls")
            assertTrue(damageRolls.isEmpty(), "Unused damage rolls: $damageRolls")
        }
        override fun of(maxExclusive: Int): Int {
            bounds += maxExclusive
            assertEquals(10, maxExclusive)
            return checkNotNull(rolls.removeFirstOrNull()) { "Unexpected shield roll" }.also {
                require(it in 0 until maxExclusive)
            }
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            val (range, value) = checkNotNull(damageRolls.removeFirstOrNull()) { "Unexpected damage roll" }
            assertEquals(range, minInclusive..maxInclusive)
            require(value in range)
            return value
        }
        override fun randomDouble(): Double = error("Unexpected floating-point roll")
    }

    private object ImpactRandom : GameRandom {
        override fun of(maxExclusive: Int): Int {
            assertEquals(4, maxExclusive, "Impact may select a defend sound, not reroll a shield proc")
            return 0
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Impact must not reroll damage")
        override fun randomDouble(): Double = error("Unexpected impact roll")
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
