package org.rsmod.content.other.treasuretrails

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.NpcServerType
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.api.player.events.interact.NpcOverrideEvent
import org.rsmod.api.player.hook.NpcOpOverrideHook
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.random.GameRandom
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.map.CoordGrid

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class NpcClueOverrideTest {
    @Test
    fun `six cryptics use only their native NPC and preserve normal fallback`() {
        val clues = BeginnerClues.ALL.filterIsInstance<BeginnerClue.Cryptic>()
        assertEquals(4, clues.count { !it.charlie && !it.hotCold })
        assertEquals(1, clues.count { it.charlie })
        assertEquals(1, clues.count { it.hotCold })
        for (clue in clues) {
            val f = Fixture()
            f.player.storeBeginnerState(BeginnerClueState(clue.rowId, 2, 0, 1))
            val target = clue.data.target.first()
            val npc = Npc(target.npc, target.coord)
            f.events.subscribeSuspend(NpcEvents.Op1::class.java, npc.id.toLong()) { f.normalTalks++ }
            val selected = f.interactions.opTrigger(f.player, npc, InteractionOp.Op1)
            assertTrue(selected is NpcOverrideEvent, clue.data.clueText)
            assertTrue(f.interactions.opTrigger(f.player, f.other, InteractionOp.Op1) is NpcEvents.Op1)
            f.player.inv.fillNulls()
            f.run { f.interactions.dispatchOverride(this, selected as NpcOverrideEvent) }
            assertEquals(1, f.normalTalks)
        }
    }

    @Test
    fun `delayed dig is bound to original coroutine clue generation and hotcold target`() {
        for (change in listOf("generation", "target", "scroll", "position")) {
            val f = Fixture()
            val target = BeginnerHotColdTargets.ALL.first()
            f.player.storeBeginnerState(BeginnerClueState(RELDO_ROW.asRSCM(), 1, 0, 1))
            f.player.beginnerHotColdTarget = target.rowId
            f.player.coords = target.target.coord
            f.run {
                with(f.script) {
                    assertTrue(beforeDig())
                    when (change) {
                        "generation" -> f.player.beginnerGeneration++
                        "target" -> f.player.beginnerHotColdTarget = BeginnerHotColdTargets.ALL.last().rowId
                        "scroll" -> f.player.inv.fillNulls()
                        "position" -> f.player.coords = f.player.coords.translate(xOffset = 4, zOffset = 0)
                    }
                    dig()
                    dig()
                }
            }
            assertEquals(0, f.player.inv.physicalCount(BEGINNER_CASKET), change)
            assertEquals(0, f.player.beginnerCompletedSteps, change)
        }
        val f = Fixture()
        val map = BeginnerClues.ALL.filterIsInstance<BeginnerClue.Map>().first()
        f.player.storeBeginnerState(BeginnerClueState(map.rowId, 1, 0, 1))
        f.player.coords = map.data.target.coord
        f.run { with(f.script) { assertTrue(beforeDig()) } }
        // A fresh coroutine must not consume the pending callback from the previous one.
        f.run { with(f.script) { dig() } }
        assertEquals(0, f.player.inv.physicalCount(BEGINNER_CASKET))
    }

    @Test
    fun `assigned Charlie request remains a talk claim but never claims unrelated NPC operations`() {
        val f = Fixture()
        val clue = BeginnerClues.ALL.filterIsInstance<BeginnerClue.Cryptic>().single { it.charlie }
        f.player.storeBeginnerState(BeginnerClueState(clue.rowId, 1, 0, 1))
        f.player.beginnerCharlieTask = BeginnerCharlieTasks.ALL.first().rowId
        val target = clue.data.target.first()
        val npc = Npc(target.npc, target.coord)
        assertTrue(f.interactions.opTrigger(f.player, npc, InteractionOp.Op1) is NpcOverrideEvent)
        assertFalse(f.interactions.opTrigger(f.player, npc, InteractionOp.Op2) is NpcOverrideEvent)
        assertFalse(f.interactions.opTrigger(f.player, npc, InteractionOp.Op3) is NpcOverrideEvent)
        f.player.beginnerRow = 0
        assertFalse(f.interactions.opTrigger(f.player, npc, InteractionOp.Op1) is NpcOverrideEvent)
    }

    @Test
    fun `matching active anagram intercepts talk while unrelated NPC uses its normal dialogue`() {
        val f = Fixture()
        assertTrue(f.interactions.opTrigger(f.player, f.npc, InteractionOp.Op1) is NpcOverrideEvent)
        assertTrue(f.interactions.opTrigger(f.player, f.other, InteractionOp.Op1) is NpcEvents.Op1)
        assertEquals(0, f.normalTalks)
    }

    @Test
    fun `trade and attack operations keep their normal events even when a hook claims NPC`() {
        val f = Fixture()
        val broadHook = object : NpcOpOverrideHook {
            override fun claims(player: Player, npc: Npc, type: NpcServerType) = true
            override suspend fun ProtectedAccess.talk(npc: Npc, type: NpcServerType) = Unit
        }
        val interactions = NpcInteractions(f.events, setOf(broadHook))
        for (op in listOf(InteractionOp.Op2, InteractionOp.Op3)) {
            assertFalse(interactions.opTrigger(f.player, f.npc, op) is NpcOverrideEvent)
        }
    }

    @Test
    fun `removed or completed clue restores normal handler at dispatch time`() {
        for (remove in listOf(true, false)) {
            val f = Fixture()
            val selected = f.interactions.opTrigger(f.player, f.npc, InteractionOp.Op1) as NpcOverrideEvent
            if (remove) f.player.inv.fillNulls() else f.player.beginnerRow = 0
            f.run { f.interactions.dispatchOverride(this, selected) }
            assertEquals(1, f.normalTalks)
            assertEquals(0, f.player.inv.physicalCount(BEGINNER_CASKET))
        }
    }

    @Test
    fun `banked clue never intercepts a quest or shop NPC`() {
        val f = Fixture()
        f.player.inv.fillNulls()
        f.player.invMap.getOrPut("inv.bank")[0] = InvObj(BEGINNER_SCROLL)
        assertTrue(f.interactions.opTrigger(f.player, f.npc, InteractionOp.Op1) is NpcEvents.Op1)
    }

    @Test
    fun `multiple claiming plugins fail explicitly regardless of set iteration order`() {
        val f = Fixture()
        val other = object : NpcOpOverrideHook {
            override fun claims(player: Player, npc: Npc, type: NpcServerType) = true
            override suspend fun ProtectedAccess.talk(npc: Npc, type: NpcServerType) = Unit
        }
        for (hooks in listOf(linkedSetOf(f.script, other), linkedSetOf(other, f.script))) {
            assertThrows(IllegalStateException::class.java) {
                NpcInteractions(f.events, hooks).opTrigger(f.player, f.npc, InteractionOp.Op1)
            }
        }
    }

    @Test
    fun `morphed NPC uses resolved type for claims and falls back when morph changes`() {
        val f = Fixture()
        val parentType = f.other.type.copy(multiVarp = "varp.cluequest_main".asRSCM(),
            transforms = mutableListOf(f.npc.type.id), multiDefault = f.other.type.id)
        val parent = Npc(parentType, f.npc.coords)
        val selected = f.interactions.opTrigger(f.player, parent, InteractionOp.Op1) as NpcOverrideEvent
        assertEquals(f.npc.type.id, selected.type.id)
        f.player.vars.backing.put("varp.cluequest_main".asRSCM(), 1)
        f.run { f.interactions.dispatchOverride(this, selected) }
        assertEquals(1, f.normalTalks)
    }

    @Test
    fun `NPC interaction still creates a route and never completes a clue before reaching NPC`() {
        val f = Fixture()
        val before = f.player.beginnerState()
        f.interactions.interact(f.player, f.npc, InteractionOp.Op1)
        assertNotNull(f.player.routeRequest)
        assertNotNull(f.player.interaction)
        assertEquals(before, f.player.beginnerState())
        assertEquals(0, f.normalTalks)
    }

    @Test
    fun `map requires the cache target plane and tile and the actual carried scroll`() {
        val f = Fixture()
        val clue = BeginnerClues.ALL.filterIsInstance<BeginnerClue.Map>().first()
        f.player.storeBeginnerState(BeginnerClueState(clue.rowId, 2, 0, 1))
        f.player.coords = clue.data.target.coord
        assertTrue(f.script.claims(f.player))
        f.player.coords = f.player.coords.translate(xOffset = 1, zOffset = 0)
        assertFalse(f.script.claims(f.player))
        f.player.coords = clue.data.target.coord
        f.player.inv.fillNulls()
        assertFalse(f.script.claims(f.player))
    }

    @OptIn(InternalApi::class)
    private class Fixture {
        val events = EventBus()
        val player = Player().apply {
            inv = inventory("inv.inv", 28)
            worn = inventory("inv.worn", 14)
            inv[0] = InvObj(BEGINNER_SCROLL)
        }
        val clue = BeginnerClues.ALL.filterIsInstance<BeginnerClue.Anagram>().first()
        val npc = Npc(clue.data.target.first().npc, clue.data.target.first().coord).apply {
            slotId = 1
            assignUid()
        }
        val other = Npc("npc.bob", CoordGrid(3229, 3208, 0)).apply {
            slotId = 2
            assignUid()
        }
        val script = BeginnerTrailScript(object : GameRandom {
            override fun of(maxExclusive: Int) = 0
            override fun of(minInclusive: Int, maxInclusive: Int) = minInclusive
            override fun randomDouble() = 0.0
        }, BeginnerClueHelper(events))
        val interactions = NpcInteractions(events, setOf(script))
        var normalTalks = 0
        init {
            player.storeBeginnerState(BeginnerClueState(clue.rowId, 2, 0, 1))
            for (target in listOf(npc, other)) {
                events.subscribeSuspend(NpcEvents.Op1::class.java, target.id.toLong()) { normalTalks++ }
                events.subscribeSuspend(NpcEvents.Op2::class.java, target.id.toLong()) { normalTalks++ }
                events.subscribeSuspend(NpcEvents.Op3::class.java, target.id.toLong()) { normalTalks++ }
            }
        }
        fun run(action: suspend ProtectedAccess.() -> Unit) {
            val access = ProtectedAccess(player, GameCoroutine("clue-hook-test"),
                ProtectedAccessContextFactory.empty().copy(getEventBus = { events }))
            var completion: Result<Unit>? = null
            val body: suspend () -> Unit = { access.action() }
            body.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) { completion = result }
            })
            checkNotNull(completion).getOrThrow()
        }
        private fun inventory(symbol: String, size: Int) = Inventory(
            checkNotNull(ServerCacheManager.getInventory(symbol.asRSCM())), arrayOfNulls(size),
        )
    }
    companion object {
        @JvmStatic @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
