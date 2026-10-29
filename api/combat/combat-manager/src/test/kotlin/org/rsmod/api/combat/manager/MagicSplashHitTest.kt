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
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.config.refs.done.hitmark_groups
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.access.StandardNpcAccessContext
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.events.NpcQueueEvents
import org.rsmod.api.npc.hit.NpcHitpointsFloorHook
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.processor.StandardNpcHitProcessor
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.events.PlayerQueueEvents
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.ironman.PlayerGamemode
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContext
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.hit.Hitmark
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.queue.NpcQueueList
import org.rsmod.game.queue.PlayerQueueList
import org.rsmod.game.queue.QueueCategory
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class MagicSplashHitTest {
    @Test
    fun `NPC splash and ordinary zero remain distinct through the real queue handler`() {
        for (splash in listOf(false, true)) {
            val f = Fixture()
            val weapon = checkNotNull(ServerCacheManager.getItem("obj.bronze_sword".asRSCM()))
            f.source.worn[weapon.wearpos1] = InvObj(weapon)
            val returned = f.magicNpc(splash = splash)
            val queued = f.npcQueues()

            assertEquals(listOf("queue.com_retaliate_player".asRSCM(), "queue.hit".asRSCM()), queued.map { it.id })
            assertEquals(if (splash) 1 else HIT_DELAY, queued.first().remainingCycles)
            assertEquals(HIT_DELAY, queued.last().remainingCycles)
            assertSame(returned, queued.last().args)
            assertEquals(listOf(0 to splash), f.npcModified)
            assertEquals(splash, returned.isMagicSplash)
            assertEquals(0, returned.damage)

            // Equipment changes after launch must not replace the original hit's source metadata.
            f.source.worn[weapon.wearpos1] = null
            val impact = f.impactNpc()
            assertSame(returned, impact)
            assertEquals(splash, impact.isMagicSplash)
            assertSame(f.source, impact.resolvePlayerSource(f.players))
            assertTrue(impact.isRighthandObj(weapon))
            assertTrue(impact.isSecondaryObj(f.spell))
            assertEquals(f.startNpcHp, f.npc.hitpoints)
        }
    }

    @Test
    fun `flat armour can produce a melee zero without turning the attack into a splash`() {
        val f = Fixture()
        f.npc.vars["varn.flat_armour"] = 5

        val queued = f.physicalNpc(HitType.Melee, 5)

        assertEquals(listOf(5 to false), f.npcModified)
        assertEquals(0, queued.damage)
        assertFalse(queued.isMagicSplash)
        assertEquals(5, f.npc.vars["varn.flat_armour"])
        assertFalse(f.impactNpc().isMagicSplash)
        assertEquals(f.startNpcHp, f.npc.hitpoints)
    }

    @Test
    fun `flat armour applies to every physical hitsplat without consuming the stat`() {
        for (style in listOf(HitType.Melee, HitType.Ranged)) {
            for ((armour, expectedDamage) in listOf(5 to 3, -2 to 10)) {
                // Match Amoxliatl's persistent baseline through the legacy explicit override.
                val f = Fixture(npcType = "npc.amoxliatl")
                f.npc.vars["varn.flat_armour"] = armour

                repeat(3) { index ->
                    val queued = f.physicalNpc(style, 8)

                    assertEquals(expectedDamage, queued.damage)
                    assertFalse(queued.isMagicSplash)
                    assertSame(queued, f.impactNpc())
                    assertEquals(f.startNpcHp - expectedDamage * (index + 1), f.npc.hitpoints)
                    assertEquals(armour, f.npc.vars["varn.flat_armour"])
                }
                assertEquals(List(3) { 8 to false }, f.npcModified)
            }
        }
    }

    @Test
    fun `low physical damage floors at zero after armour while incoming zero stays zero`() {
        for (style in listOf(HitType.Melee, HitType.Ranged)) {
            for ((armour, damage, expected) in listOf(
                Triple(5, 0, 0), Triple(5, 1, 0), Triple(5, 5, 0), Triple(5, 6, 1),
                Triple(-2, 0, 0), Triple(-2, 1, 3), Triple(0, 1, 1),
            )) {
                val f = Fixture()
                f.npc.vars["varn.flat_armour"] = armour

                val queued = f.physicalNpc(style, damage)

                assertEquals(expected, queued.damage, "style=$style armour=$armour damage=$damage")
                assertFalse(queued.isMagicSplash)
                assertSame(queued, f.impactNpc())
                assertEquals(f.startNpcHp - expected, f.npc.hitpoints)
                assertEquals(armour, f.npc.vars["varn.flat_armour"])
            }
        }
    }

    @Test
    fun `flat armour arithmetic cannot overflow or exceed the packed hitsplat range`() {
        val maxDamage = Hitmark.DAMAGE_BIT_MASK.toInt()
        for ((armour, damage, expected) in listOf(
            Triple(Int.MAX_VALUE, 1, 0),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, 0),
            Triple(Int.MIN_VALUE, 1, maxDamage),
            Triple(Int.MIN_VALUE, Int.MAX_VALUE, maxDamage),
            Triple(-1, maxDamage, maxDamage),
            Triple(1, maxDamage, maxDamage - 1),
        )) {
            val f = Fixture()
            f.npc.vars["varn.flat_armour"] = armour

            val queued = f.physicalNpc(HitType.Melee, damage)

            assertEquals(expected, queued.damage, "armour=$armour damage=$damage")
            assertFalse(queued.isMagicSplash)
            val impact = f.impactNpc()
            assertEquals(minOf(expected, f.startNpcHp), impact.damage)
            assertEquals(maxOf(f.startNpcHp - expected, 0), f.npc.hitpoints)
            assertEquals(armour, f.npc.vars["varn.flat_armour"])
        }
    }

    @Test
    fun `typeless and non-player damage ignore both signs of flat armour`() {
        for (armour in listOf(-5, 5)) {
            val f = Fixture()
            f.npc.vars["varn.flat_armour"] = armour
            val npcSource = Npc("npc.king_dragon", CoordGrid(2274, 4695)).apply {
                slotId = 2
                assignUid()
            }

            val typeless = f.npc.queueHit(f.source, HIT_DELAY, HitType.Typeless, 3, f.npcModifier)
            assertEquals(3, typeless.damage)
            assertSame(typeless, f.impactNpc())

            for (style in listOf(HitType.Melee, HitType.Ranged)) {
                val npcHit = f.npc.queueHit(npcSource, HIT_DELAY, style, 3, f.npcModifier)
                assertEquals(3, npcHit.damage)
                assertTrue(npcHit.isFromNpc)
                assertSame(npcHit, f.impactNpc())

                val sourceless = f.npc.queueHit(HIT_DELAY, style, 3, f.npcModifier)
                assertEquals(3, sourceless.damage)
                assertFalse(sourceless.isFromPlayer)
                assertFalse(sourceless.isFromNpc)
                assertSame(sourceless, f.impactNpc())
            }
            assertEquals(f.startNpcHp - 15, f.npc.hitpoints)
            assertEquals(armour, f.npc.vars["varn.flat_armour"])
        }
    }

    @Test
    fun `style immunity produces a zero without declaring a splash`() {
        val f = Fixture()
        f.npc.vars["varn.immune_magic"] = 1

        assertEquals(0, f.magicNpc(damage = 8).damage)
        assertEquals(listOf(8 to false), f.npcModified)
        assertFalse(f.impactNpc().isMagicSplash)
        assertEquals(f.startNpcHp, f.npc.hitpoints)
    }

    @Test
    fun `hitpoint floor copy preserves ordinary attack metadata when damage becomes zero`() {
        val f = Fixture()
        f.floor = f.npc.hitpoints
        val queued = f.magicNpc(damage = 5)

        val impact = f.impactNpc()

        assertEquals(5, queued.damage)
        assertEquals(0, impact.damage)
        assertNotSame(queued, impact)
        assertFalse(impact.isMagicSplash)
        assertTrue(impact.isSecondaryObj(f.spell))
        assertSame(f.source, impact.resolvePlayerSource(f.players))
        assertEquals(f.startNpcHp, f.npc.hitpoints)
    }

    @Test
    fun `explicit splashes neither gain damage from nor consume flat armour`() {
        for (armour in listOf(-5, 5)) {
            val f = Fixture()
            f.npc.vars["varn.flat_armour"] = armour

            val queued = f.magicNpc(splash = true)

            assertEquals(listOf(0 to true), f.npcModified)
            assertEquals(0, queued.damage)
            assertTrue(queued.isMagicSplash)
            assertEquals(armour, f.npc.vars["varn.flat_armour"])
            val impact = f.impactNpc()
            assertSame(queued, impact)
            assertEquals(0, impact.damage)
            assertTrue(impact.isMagicSplash)
            assertTrue(impact.isSecondaryObj(f.spell))
            assertEquals(f.startNpcHp, f.npc.hitpoints)
            assertEquals(armour, f.npc.vars["varn.flat_armour"])
        }
    }

    @Test
    fun `ordinary magic ignores flat armour for both zero and positive damage`() {
        for (armour in listOf(-5, 5)) {
            for (damage in listOf(0, 8)) {
                val f = Fixture()
                f.npc.vars["varn.flat_armour"] = armour

                val queued = f.magicNpc(damage = damage)

                assertEquals(listOf(damage to false), f.npcModified)
                assertEquals(damage, queued.damage)
                assertFalse(queued.isMagicSplash)
                val impact = f.impactNpc()
                assertSame(queued, impact)
                assertFalse(impact.isMagicSplash)
                assertEquals(f.startNpcHp - damage, f.npc.hitpoints)
                assertEquals(armour, f.npc.vars["varn.flat_armour"])
            }
        }
    }

    @Test
    fun `powered staff magic also ignores flat armour without a spell object`() {
        val f = Fixture()
        f.npc.vars["varn.flat_armour"] = -5

        val queued = f.poweredStaffNpc(damage = 8)

        assertEquals(8, queued.damage)
        assertFalse(queued.isMagicSplash)
        assertSame(queued, f.impactNpc())
        assertEquals(f.startNpcHp - 8, f.npc.hitpoints)
        assertEquals(-5, f.npc.vars["varn.flat_armour"])
    }

    @Test
    fun `negative flat armour cannot revive a style immune hit or consume its modifier`() {
        for (style in listOf(HitType.Melee, HitType.Ranged, HitType.Magic)) {
            val f = Fixture()
            f.npc.vars["varn.flat_armour"] = -5
            when (style) {
                HitType.Melee -> f.npc.vars["varn.immune_melee"] = 1
                HitType.Ranged -> f.npc.vars["varn.immune_ranged"] = 1
                HitType.Magic -> f.npc.vars["varn.immune_magic"] = 1
                else -> error("Only attack styles with immunity are tested")
            }

            val queued = f.npc.queueHit(f.source, HIT_DELAY, style, 8, f.npcModifier)

            assertEquals(listOf(8 to false), f.npcModified)
            assertEquals(0, queued.damage)
            assertFalse(queued.isMagicSplash)
            assertSame(queued, f.impactNpc())
            assertEquals(f.startNpcHp, f.npc.hitpoints)
            assertEquals(-5, f.npc.vars["varn.flat_armour"])
        }
    }

    @Test
    fun `ironman blocked hitsplat copy retains splash or ordinary zero outcome`() {
        for (splash in listOf(false, true)) {
            val f = Fixture()
            f.source.gamemode = PlayerGamemode.IRONMAN
            f.npc.recordDamage(f.target, 1)
            val queued = f.magicNpc(splash = splash)

            val impact = f.impactNpc()

            assertNotSame(queued, impact)
            assertEquals(hitmark_groups.ironman_blocked.lit.asRSCM(), impact.hitmark.self)
            assertEquals(splash, impact.isMagicSplash)
            assertEquals(0, impact.damage)
            assertSame(f.source, impact.resolvePlayerSource(f.players))
        }
    }

    @Test
    fun `unannotated player hits do not claim a splash for any combat style`() {
        for (style in HitType.entries) {
            val f = Fixture()
            val hit = f.npc.queueHit(f.source, HIT_DELAY, style, 0, f.npcModifier)

            assertFalse(hit.isMagicSplash)
            assertFalse(f.impactNpc().isMagicSplash)
            assertEquals(style, f.npcImpacts.single().type)
        }
    }

    @Test
    fun `NPC source and source-less hits retain their identity without claiming accuracy`() {
        val f = Fixture()
        val npcSource = Npc("npc.king_dragon", CoordGrid(2274, 4695)).apply {
            slotId = 2
            assignUid()
        }
        val npcs = NpcList().also { it[npcSource.slotId] = npcSource }
        f.npc.queueHit(npcSource, HIT_DELAY, HitType.Magic, 0, f.npcModifier)
        val npcImpact = f.impactNpc()
        assertFalse(npcImpact.isMagicSplash)
        assertTrue(npcImpact.isFromNpc)
        assertSame(npcSource, npcImpact.resolveNpcSource(npcs))

        f.npc.queueHit(HIT_DELAY, HitType.Typeless, 1, f.npcModifier, hitmark_groups.poison_damage)
        val poisonImpact = f.impactNpc()
        assertFalse(poisonImpact.isMagicSplash)
        assertFalse(poisonImpact.isFromNpc)
        assertFalse(poisonImpact.isFromPlayer)
        assertEquals(HitType.Typeless, poisonImpact.type)
    }

    @Test
    fun `player target receives splash metadata through its strong queue and impact event`() {
        for (splash in listOf(false, true)) {
            val f = Fixture()
            val returned = f.magicPlayer(splash = splash)
            val queued = f.playerQueues()

            assertEquals(listOf("queue.com_retaliate_player".asRSCM(), "queue.hit".asRSCM()), queued.map { it.id })
            assertEquals(if (splash) 1 else HIT_DELAY, queued.first().remainingCycles)
            assertEquals(HIT_DELAY, queued.last().remainingCycles)
            assertEquals(QueueCategory.Strong.id, queued.last().category)
            assertSame(returned, queued.last().args)
            assertEquals(listOf(0 to splash), f.playerModified)
            val impact = f.impactPlayer()
            assertSame(returned, impact)
            assertEquals(splash, impact.isMagicSplash)
            assertEquals(0, impact.damage)
            assertSame(f.source, impact.resolvePlayerSource(f.players))
            assertTrue(impact.isSecondaryObj(f.spell))
        }
    }

    @Test
    fun `player protection prayer can round an ordinary hit to zero without making a splash`() {
        val f = Fixture()
        VarPlayerIntMapSetter.set(f.target, "varbit.prayer_protectfrommagic", 1)

        val queued = f.magicPlayer(damage = 1)

        assertEquals(listOf(1 to false), f.playerModified)
        assertEquals(0, queued.damage)
        assertFalse(queued.isMagicSplash)
        assertFalse(f.impactPlayer().isMagicSplash)
    }

    @Test
    fun `nonmagic hits cannot be marked as magic splashes or enter a queue`() {
        val f = Fixture()
        assertThrows<IllegalArgumentException> {
            f.npc.queueHit(f.source, HIT_DELAY, HitType.Melee, 0, f.npcModifier, isMagicSplash = true)
        }
        assertThrows<IllegalArgumentException> {
            f.target.queueHit(f.source, HIT_DELAY, HitType.Ranged, 0, f.playerModifier, isMagicSplash = true)
        }
        assertTrue(f.npc.queueList.isEmpty)
        assertTrue(f.target.queueList.isEmpty)
        assertTrue(f.npcModified.isEmpty())
        assertTrue(f.playerModified.isEmpty())
    }

    private class Fixture(npcType: String = "npc.king_dragon") {
        val events = EventBus()
        val source = player(slot = 1)
        val target = player(slot = 2)
        val players = PlayerList().also {
            it[source.slotId] = source
            it[target.slotId] = target
        }
        val npc = Npc(npcType, CoordGrid(2271, 4695)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
        }
        val startNpcHp = npc.hitpoints
        val spell = checkNotNull(ServerCacheManager.getItem("obj.01_wind_strike".asRSCM()))
        val npcModifier = StandardNpcHitModifier(events)
        val playerModifier = StandardPlayerHitModifier(events)
        val npcModified = mutableListOf<Pair<Int, Boolean>>()
        val playerModified = mutableListOf<Pair<Int, Boolean>>()
        val npcImpacts = mutableListOf<Hit>()
        val playerImpacts = mutableListOf<Hit>()
        var floor = 0
        private val random = mockk<GameRandom>()
        private val processor = StandardNpcHitProcessor(
            players, events, emptySet(), setOf(NpcHitpointsFloorHook { _, _ -> floor }),
        )
        private val npcAccess = StandardNpcAccess(
            npc, GameCoroutine("magic-splash-npc"),
            StandardNpcAccessContext({ random }, { npcModifier }, { processor }),
        )
        private val playerContext = mockk<ProtectedAccessContext>().also {
            every { it.eventBus } returns events
            every { it.playerList } returns players
            every { it.random } returns random
        }
        private val playerAccess = ProtectedAccess(target, GameCoroutine("magic-splash-player"), playerContext)
        private val manager = PlayerAttackManager(
            random, events, mockk(), mockk(), mockk(), npcModifier, playerModifier,
            mockk(), mockk(), mockk(), mockk(), emptySet(),
        )

        init {
            val context = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
            with(NpcHitScript()) { context.startup() }
            with(PlayerHitScript()) { context.startup() }
            events.subscribeKeyed(NpcHitEvents.Modify::class.java, npc.id.toLong()) {
                npcModified += hit.damage to hit.isMagicSplash
            }
            events.subscribeKeyed(NpcHitEvents.Impact::class.java, npc.id.toLong()) {
                npcImpacts += hit
            }
            events.subscribeUnbound(PlayerHitEvents.Modify::class.java) {
                playerModified += hit.damage to hit.isMagicSplash
            }
            events.subscribeUnbound(PlayerHitEvents.Impact::class.java) { playerImpacts += hit }
        }

        fun magicNpc(splash: Boolean = false, damage: Int = 0): Hit =
            if (splash) manager.queueSplashHit(source, npc, spell, CLIENT_DELAY, HIT_DELAY)
            else manager.queueMagicHit(source, npc, spell, damage, CLIENT_DELAY, HIT_DELAY)

        fun magicPlayer(splash: Boolean = false, damage: Int = 0): Hit =
            if (splash) manager.queueSplashHit(source, target, spell, CLIENT_DELAY, HIT_DELAY)
            else manager.queueMagicHit(source, target, spell, damage, CLIENT_DELAY, HIT_DELAY)

        fun poweredStaffNpc(damage: Int): Hit =
            manager.queueMagicHit(source, npc, null, damage, CLIENT_DELAY, HIT_DELAY)

        fun physicalNpc(style: HitType, damage: Int): Hit =
            when (style) {
                HitType.Melee -> manager.queueMeleeHit(source, npc, damage, HIT_DELAY)
                HitType.Ranged -> manager.queueRangedHit(source, npc, null, damage, CLIENT_DELAY, HIT_DELAY)
                else -> error("Only physical attack styles are supported")
            }

        fun npcQueues(): List<NpcQueueList.Queue> {
            val iterator = checkNotNull(npc.queueList.iterator())
            return buildList { while (iterator.hasNext()) add(iterator.next()) }
        }

        fun playerQueues(): List<PlayerQueueList.Queue> {
            val iterator = checkNotNull(target.queueList.iterator())
            return buildList { while (iterator.hasNext()) add(iterator.next()) }
        }

        fun impactNpc(): Hit {
            val queue = npcQueues().single { it.id == "queue.hit".asRSCM() }
            val before = npcImpacts.size
            npc.queueList.removeAll("queue.hit")
            runImmediately {
                assertTrue(events.publish(npcAccess, NpcQueueEvents.Default(npc, queue.args as Hit, queue.id)))
            }
            assertEquals(before + 1, npcImpacts.size)
            return npcImpacts.last()
        }

        fun impactPlayer(): Hit {
            val queue = playerQueues().single { it.id == "queue.hit".asRSCM() }
            val before = playerImpacts.size
            target.queueList.removeAll("queue.hit")
            runImmediately {
                assertTrue(events.publish(playerAccess, PlayerQueueEvents.Protected(queue.args as Hit, queue.id)))
            }
            assertEquals(before + 1, playerImpacts.size)
            return playerImpacts.last()
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

    companion object {
        private const val CLIENT_DELAY = 60
        private const val HIT_DELAY = 3

        @JvmStatic
        @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
        }

        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Hit processing unexpectedly suspended." }.getOrThrow()
        }
    }
}
