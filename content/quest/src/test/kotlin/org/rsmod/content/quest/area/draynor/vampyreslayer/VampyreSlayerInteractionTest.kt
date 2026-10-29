package org.rsmod.content.quest.area.draynor.vampyreslayer

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.util.Wearpos
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import net.rsprot.protocol.game.outgoing.misc.player.RunClientScript
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.death.NpcAttackValidateResult
import org.rsmod.api.game.process.npc.NpcRegenProcessor
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.owner.assignSpawnOwner
import org.rsmod.api.player.dialogue.align.TextAlignment
import org.rsmod.api.player.events.PlayerTimerEvent
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.api.player.input.ResumePauseButtonInput
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.npc.NpcRegistry
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.table.QuestRow
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.hit.Hitmark
import org.rsmod.game.interact.InteractionNpcOp
import org.rsmod.game.interact.InteractionNpcT
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class VampyreSlayerInteractionTest {
    @Test fun `ordinary and repaired Imcando hammers finish while retaining the hammer`() {
        val hammers = listOf(
            Hammer to null,
            "obj.imcando_hammer" to null,
            "obj.imcando_hammer_offhand" to null,
            "obj.imcando_hammer" to Wearpos.RightHand,
            "obj.imcando_hammer_offhand" to Wearpos.LeftHand,
        )
        for ((hammer, wornSlot) in hammers) {
            val f = Fixture(2)
            f.player.inv[0] = InvObj(Stake, 1)
            if (wornSlot == null) f.player.inv[1] = InvObj(hammer, 1)
            else f.player.worn[wornSlot.slot] = InvObj(hammer, 1)
            val count = f.count()
            f.fatalHit(count)
            assertEquals(1, f.player.vampyreCompletionPending, "Hammer=$hammer slot=$wornSlot")
            assertEquals(0, f.player.inv.count(Stake))
            assertEquals(1, f.player.inv.count(hammer) + f.player.worn.count(hammer))
            assertFalse(f.encounter.commitStake(f.player, count))
            f.script.settle(f.access())
            f.assertReward()
        }
    }

    @Test fun `broken Imcando hammers never consume the stake or finish the quest`() {
        for (worn in listOf(false, true)) {
            val f = Fixture(2)
            f.player.inv[0] = InvObj(Stake, 1)
            if (worn) f.player.worn[Wearpos.RightHand.slot] = InvObj("obj.imcando_hammer_broken", 1)
            else f.player.inv[1] = InvObj("obj.imcando_hammer_broken", 1)
            val count = f.count()
            f.fatalHit(count)
            assertEquals(35, count.hitpoints)
            assertEquals(1, f.player.inv.count(Stake))
            assertEquals(0, f.player.vampyreCompletionPending)
            assertEquals(1, f.player.inv.count("obj.imcando_hammer_broken") + f.player.worn.count("obj.imcando_hammer_broken"))
        }
    }

    @Test fun `an equipped repaired hammer does not replace the stake requirement`() {
        val f = Fixture(2)
        f.player.worn[Wearpos.LeftHand.slot] = InvObj("obj.imcando_hammer_offhand", 1)
        val count = f.count()
        f.fatalHit(count)
        assertEquals(35, count.hitpoints)
        assertEquals(1, f.player.worn.count("obj.imcando_hammer_offhand"))
        assertEquals(0, f.player.vampyreCompletionPending)
    }

    @Test fun `combat warning is advisory below twenty and absent at twenty`() {
        for (level in listOf(19, 20)) {
            val f = Fixture()
            f.player.appearance.combatLevel = level
            f.talk(); f.finish(listOf(1, 2))
            assertEquals(level < 20, f.output().contains("recommended level of 20"))
            assertEquals(1, f.player.vars["varp.vampire"])
        }
    }

    @Test fun `Morgan optional history remains available without advancing quest or items`() {
        val f = Fixture()
        f.talk(); f.finish(listOf(1, 1))
        assertTrue(f.output().contains("generations"))
        assertEquals(1, f.player.vars["varp.vampire"])
        f.talk(); f.finish(listOf(1))
        assertEquals(1, f.player.vars["varp.vampire"])
        assertEquals(0, f.player.vars["varp.qp"])
        assertEquals(0, f.player.inv.count(Stake))
    }

    @Test fun `declining to ask Harlow leaves the beer and stage unchanged`() {
        val f = Fixture(1)
        f.player.inv[0] = InvObj("obj.beer", 1)
        f.talk(harlow = true); f.finish(listOf(2))
        assertEquals(1, f.player.vars["varp.vampire"])
        assertEquals(1, f.player.inv.count("obj.beer"))
        assertEquals(0, f.player.vampyreBeerGiven)
    }

    @Test fun `refusing Harlow beer has a response whether or not beer is carried`() {
        for (hasBeer in listOf(false, true)) {
            val f = Fixture(2)
            if (hasBeer) f.player.inv[0] = InvObj("obj.beer", 1)
            f.talk(harlow = true); f.finish(listOf(2))
            assertTrue(f.output().contains("can't help yoush"))
            assertEquals(if (hasBeer) 1 else 0, f.player.inv.count("obj.beer"))
            assertEquals(0, f.player.inv.count(Stake))
            assertEquals(0, f.player.vampyreBeerGiven)
        }
    }

    @Test fun `Harlow reminder Count and career branches return to menu without new rewards`() {
        val f = Fixture(2)
        f.player.vampyreStakeGiven = 1
        f.player.vampyreBeerGiven = 1
        f.player.inv[0] = InvObj(Stake, 1)
        f.talk(harlow = true); f.finish(listOf(1, 1, 2, 3))
        assertTrue(f.output().contains("General shtores sell hammers"))
        assertTrue(f.output().contains("too weak"))
        assertTrue(f.output().contains("Morytania"))
        assertEquals(1, f.player.inv.count(Stake))
        assertEquals(2, f.player.vars["varp.vampire"])
        assertEquals(0, f.player.statMap.getXP("stat.attack"))
    }

    @Test fun `each Harlow follow-up omits only the subject just discussed`() {
        val topics = listOf(
            "Remind me what I need to do.",
            "Has nobody seen Count Draynor?",
            "Were you really a vampyre hunter?",
        )
        for ((index, subject) in topics.withIndex()) {
            val f = Fixture(2)
            f.player.vampyreStakeGiven = 1
            f.player.vampyreBeerGiven = 1
            f.player.inv[0] = InvObj(Stake, 1)
            f.talk(harlow = true); f.finish(listOf(index + 1, 3))
            val menus = f.harlowMenus()
            assertEquals(topics + "I'll get going.", menus.first())
            assertEquals(topics.filterNot { it == subject } + "I'll get going.", menus.last())
            assertEquals(2, menus.size)
            assertEquals(1, f.player.inv.count(Stake))
            assertEquals(2, f.player.vars["varp.vampire"])
            assertEquals(0, f.player.vars["varp.qp"])
        }
    }

    @Test fun `cancelling a Harlow follow-up restores every subject on the next conversation`() {
        val f = Fixture(2)
        f.player.vampyreStakeGiven = 1
        f.player.vampyreBeerGiven = 1
        f.player.inv[0] = InvObj(Stake, 1)
        f.talk(harlow = true)
        f.until { f.player.ui.containsModal("interface.chatmenu") }
        f.select(2)
        f.until { f.player.ui.containsModal("interface.chatmenu") }
        assertEquals(3, f.harlowMenus().last().size)
        f.cancel()
        f.talk(harlow = true); f.finish(listOf(4))
        assertEquals(4, f.harlowMenus().last().size)
        assertEquals(1, f.player.inv.count(Stake))
        assertEquals(1, f.player.vampyreBeerGiven)
        assertEquals(1, f.player.vampyreStakeGiven)
    }

    @Test fun `native relocated Harlow offers post Blood Moon dialogue without quest rewards`() {
        val f = Fixture(3)
        val original = checkNotNull(ServerCacheManager.getNpc("npc.dr_harlow".asRSCM()))
        val relocated = checkNotNull(ServerCacheManager.getNpc("npc.dr_harlow_myq6".asRSCM()))
        val quest = QuestRow.getRow("dbrow.quest_bloodmoonrises")
        val progress = checkNotNull(ServerCacheManager.getVarbit(relocated.multiVarBit))
        val interactions = NpcInteractions(f.events)
        assertNull(interactions.multiNpc(relocated, f.player.vars))
        VarPlayerIntMapSetter.set(f.player, progress, quest.endstate)
        assertNull(interactions.multiNpc(original, f.player.vars))
        assertEquals("npc.dr_harlow_vis", checkNotNull(interactions.multiNpc(relocated, f.player.vars)).internalName)
        val before = f.player.inv.objs.toList()
        repeat(2) { f.talkNpc("npc.dr_harlow_myq6"); f.finish() }
        assertTrue(f.output().contains("Morytania"))
        assertEquals(before, f.player.inv.objs.toList())
        assertEquals(3, f.player.vars["varp.vampire"])
        assertEquals(0, f.player.vars["varp.qp"])
        assertEquals(0, f.player.statMap.getXP("stat.attack"))
        assertEquals(0, f.player.vampyreStakeGiven)
    }

    @Test fun `beer disappearing while the offer is open is not exchanged for a stake`() {
        val f = Fixture(2)
        f.player.inv[0] = InvObj("obj.beer", 1)
        f.talk(harlow = true)
        f.until { f.player.ui.containsModal("interface.chatmenu") }
        f.player.inv[0] = null
        f.finish(listOf(1))
        assertEquals(0, f.player.inv.count(Stake))
        assertEquals(0, f.player.vampyreBeerGiven)
        assertEquals(0, f.player.vampyreStakeGiven)
    }

    @Test fun `a recovered stake during replacement dialogue is not duplicated`() {
        val f = Fixture(2)
        f.player.vampyreStakeGiven = 1
        f.player.vampyreBeerGiven = 1
        f.talk(harlow = true)
        f.player.inv[0] = InvObj(Stake, 1)
        f.finish(listOf(3))
        assertEquals(1, f.player.inv.count(Stake))
    }

    @Test fun `a finishing hit during coffin opening does not summon another Count`() {
        val f = Fixture(2)
        f.coffin()
        f.player.vampyreCompletionPending = 1
        f.finish()
        assertEquals(0, f.npcs.count { it.isType(CountDraynor) })
        assertEquals(1, f.player.vampyreCompletionPending)
    }

    @Test fun `Morgan starts native progress only after accepting`() {
        val declined = Fixture()
        declined.talk(); declined.finish(listOf(2))
        assertEquals(0, declined.player.vars["varp.vampire"])
        val accepted = Fixture()
        accepted.talk(); accepted.finish(listOf(1))
        assertEquals(1, accepted.player.vars["varp.vampire"])
        assertEquals(0, accepted.player.vars["varp.qp"])
    }

    @Test fun `Harlow asks for beer before accepting it and cannot start the quest`() {
        val unstarted = Fixture()
        unstarted.player.inv[0] = InvObj("obj.beer", 1)
        unstarted.talk(harlow = true); unstarted.finish()
        assertEquals(0, unstarted.player.vars["varp.vampire"])
        assertEquals(1, unstarted.player.inv.count("obj.beer"))
        val started = Fixture(1)
        started.player.inv[0] = InvObj("obj.beer", 1)
        started.talk(harlow = true); started.finish(listOf(1))
        assertEquals(2, started.player.vars["varp.vampire"])
        assertEquals(1, started.player.inv.count("obj.beer"))
        assertEquals(0, started.player.inv.count(Stake))
    }

    @Test fun `beer and stake exchange is atomic with a full backpack`() {
        val f = Fixture(2)
        repeat(28) { f.player.inv[it] = InvObj("obj.bronze_dagger", 1) }
        f.player.inv[0] = InvObj("obj.beer", 1)
        f.talk(harlow = true); f.finish(listOf(1, 3))
        assertEquals(0, f.player.inv.count("obj.beer"))
        assertEquals(1, f.player.inv.count(Stake))
        assertEquals(27, f.player.inv.count("obj.bronze_dagger"))
        assertEquals(1, f.player.vampyreBeerGiven)
        assertEquals(1, f.player.vampyreStakeGiven)
        assertFalse(f.script.exchangeBeer(f.player))
        assertEquals(1, f.player.inv.count(Stake))
    }

    @Test fun `absent beer cannot set flags or produce a stake`() {
        val f = Fixture(2)
        assertFalse(f.script.exchangeBeer(f.player))
        assertEquals(0, f.player.vampyreBeerGiven)
        assertEquals(0, f.player.vampyreStakeGiven)
        assertEquals(0, f.player.inv.count(Stake))
    }

    @Test fun `cancelling after accepting the exchange preserves both committed sides`() {
        val f = Fixture(2)
        f.player.inv[0] = InvObj("obj.beer", 1)
        f.talk(harlow = true)
        f.until { f.player.vampyreStakeGiven == 1 }
        f.cancel()
        assertEquals(0, f.player.inv.count("obj.beer"))
        assertEquals(1, f.player.inv.count(Stake))
        f.talk(harlow = true); f.finish(listOf(4))
        assertEquals(1, f.player.inv.count(Stake))
    }

    @Test fun `lost stakes are replaced without charging another beer`() {
        val f = Fixture(2)
        f.player.vampyreStakeGiven = 1
        f.player.vampyreBeerGiven = 1
        repeat(28) { f.player.inv[it] = InvObj("obj.bronze_dagger", 1) }
        f.talk(harlow = true); f.finish()
        assertEquals(0, f.player.inv.count(Stake))
        f.player.inv[0] = null
        f.talk(harlow = true); f.finish(listOf(3))
        assertEquals(1, f.player.inv.count(Stake))
        assertEquals(27, f.player.inv.count("obj.bronze_dagger"))
    }

    @Test fun `garlic independently drains each full stat and is idempotent between regen ticks`() {
        val f = Fixture(2)
        val count = f.count()
        assertTrue(VampyreSlayerEncounter.weakenWithGarlic(count))
        assertEquals(20, count.attackLvl)
        assertEquals(15, count.strengthLvl)
        assertEquals(0, count.defenceLvl)
        assertEquals(25, count.hitpoints)
        assertFalse(VampyreSlayerEncounter.weakenWithGarlic(count))
        assertEquals(25, count.hitpoints)
        count.strengthLvl = count.baseStrengthLvl
        count.hitpoints = count.baseHitpointsLvl
        assertFalse(VampyreSlayerEncounter.weakenWithGarlic(count))
        assertEquals(15, count.strengthLvl)
        assertEquals(25, count.hitpoints)
    }

    @Test fun `Count regains one stat point every five ticks with garlic reapplying only at full`() {
        val f = Fixture(2)
        val count = f.count()
        count.regenClock = count.regenRate
        VampyreSlayerEncounter.weakenWithGarlic(count)
        val regen = NpcRegenProcessor()
        repeat(4) { regen.process(count) }
        assertEquals(20, count.attackLvl)
        regen.process(count)
        assertEquals(21, count.attackLvl)
        repeat(45) { regen.process(count) }
        assertEquals(30, count.attackLvl)
        assertEquals(10, count.defenceLvl)
        assertTrue(VampyreSlayerEncounter.weakenWithGarlic(count))
        assertEquals(20, count.attackLvl)
        assertEquals(10, count.defenceLvl)
        repeat(100) { regen.process(count) }
        assertEquals(30, count.defenceLvl)
        VampyreSlayerEncounter.weakenWithGarlic(count)
        assertEquals(0, count.defenceLvl)
    }

    @Test fun `auto attack validates garlic every attempt without consuming it`() {
        val f = Fixture(2)
        val count = f.count()
        f.player.inv[0] = InvObj(Garlic, 1)
        f.player.interaction = InteractionNpcOp(InteractionOp.Op2, count, true, true)
        assertEquals(NpcAttackValidateResult.Pass, f.encounter.validate(f.player, count))
        assertEquals(25, count.hitpoints)
        assertEquals(1, f.player.inv.count(Garlic))
        f.encounter.validate(f.player, count)
        assertEquals(25, count.hitpoints)
    }

    @Test fun `garlic does not drain on a standalone spell or on a Nightmare Zone copy`() {
        val f = Fixture(2)
        f.player.inv[0] = InvObj(Garlic, 1)
        val count = f.count()
        f.player.interaction = InteractionNpcT(null, mockk(), -1, count, false, true)
        f.encounter.validate(f.player, count)
        assertEquals(35, count.hitpoints)
        val nmz = Npc("npc.nzone_count_draynor_normal", f.player.coords)
        f.encounter.validate(f.player, nmz)
        assertEquals(nmz.baseHitpointsLvl, nmz.hitpoints)
    }

    @Test fun `missing stake or hammer restores Count fully and preserves quest progress`() {
        for (hasStake in listOf(false, true)) {
            val f = Fixture(2)
            if (hasStake) f.player.inv[0] = InvObj(Stake, 1)
            val count = f.count()
            f.fatalHit(count)
            assertEquals(35, count.hitpoints)
            assertFalse("queue.death" in count.queueList)
            assertEquals(2, f.player.vars["varp.vampire"])
            assertEquals(0, f.player.vampyreCompletionPending)
            assertEquals(if (hasStake) 1 else 0, f.player.inv.count(Stake))
            assertTrue(f.output().contains("regenerate"))
        }
    }

    @Test fun `finishing consumes exactly one stake and completion grants rewards once`() {
        val f = Fixture(2)
        f.player.inv[0] = InvObj(Stake, 1)
        f.player.inv[1] = InvObj(Hammer, 1)
        f.player.inv[2] = InvObj(Garlic, 1)
        val count = f.count()
        assertFalse(f.encounter.commitStake(f.player, count), "A living Count cannot be finished")
        f.fatalHit(count)
        assertEquals(0, f.player.inv.count(Stake))
        assertEquals(1, f.player.inv.count(Hammer))
        assertEquals(1, f.player.inv.count(Garlic))
        assertEquals(1, f.player.vampyreCompletionPending)
        assertEquals(2, f.player.vars["varp.vampire"])
        f.script.settle(f.access())
        f.assertReward()
        f.script.settle(f.access())
        f.assertReward()
    }

    @Test fun `saved completion survives logout and login without needing another stake`() {
        val f = Fixture(2)
        f.player.inv[0] = InvObj(Stake, 1)
        f.player.inv[1] = InvObj(Hammer, 1)
        f.fatalHit(f.count())
        f.events.publish(SessionStateEvent.Logout(f.player))
        assertTrue(f.npcs.none { it.isType(CountDraynor) })
        val restored = Fixture()
        restored.player.vars.backing.putAll(f.player.vars.backing)
        restored.events.publish(SessionStateEvent.Login(restored.player))
        restored.events.publish(PlayerTimerEvent.Soft(restored.player, VampyreSettlementTimer.asRSCM()))
        restored.assertReward()
        assertEquals(0, restored.player.inv.count(Stake))
    }

    @Test fun `coffin refuses before and after quest but spawns a nonrespawning world Count during quest`() {
        for (stage in listOf(0, 3)) {
            val f = Fixture(stage)
            f.coffin(); f.finish()
            assertTrue(f.npcs.none { it.isType(CountDraynor) })
            assertTrue(f.output().contains("sealed shut"))
        }
        val f = Fixture(1)
        f.coffin(); f.finish()
        val count = f.npcs.single { it.isType(CountDraynor) }
        assertFalse(count.respawns)
        f.coffin(); f.finish()
        assertEquals(1, f.npcs.count { it.isType(CountDraynor) })
        f.events.publish(SessionStateEvent.Logout(f.player))
        assertFalse(count.isSlotAssigned)
    }

    private class Fixture(stage: Int = 0) {
        val events = EventBus()
        private val client = RecordingClient()
        private val coroutine = GameCoroutine("vampyre-slayer-test")
        private var result: Result<Unit>? = null
        val context = ProtectedAccessContextFactory.empty().copy(
            getEventBus = { events }, getAlignment = { TextAlignment() },
            getNpcInteractions = { NpcInteractions(events) },
        )

        @OptIn(InternalApi::class)
        val player = Player().apply {
            this.client = this@Fixture.client
            uuid = 789L
            slotId = 1
            assignUid()
            coords = CoordGrid(3078, 9774)
            currentMapClock = 100
            processedMapClock = 100
            inv = Inventory(checkNotNull(ServerCacheManager.getInventory("inv.inv".asRSCM())), arrayOfNulls(28))
            worn = Inventory(checkNotNull(ServerCacheManager.getInventory("inv.worn".asRSCM())), arrayOfNulls(14))
        }

        val script = VampyreSlayer()
        val npcs = NpcList()
        val players = PlayerList()
        val npcRepo = NpcRepository(MapClock(100), NpcRegistry(npcs, CollisionFlagMap(), events), npcs)
        private val factory = mockk<ProtectedAccessContextFactory>().also { every { it.create() } returns context }
        val encounter = VampyreSlayerEncounter(script, npcRepo, mockk<LocRepository>(relaxed = true), npcs, players,
            AiPlayerInteractions(events, players), ProtectedAccessLauncher(factory))

        init {
            with(script) { ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup() }
            with(encounter) { ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup() }
            players[player.slotId] = player
            VarPlayerIntMapSetter.set(player, "varp.vampire", stage)
        }

        fun access() = ProtectedAccess(player, coroutine, context)

        fun count(): Npc = Npc(CountDraynor, player.coords.translateX(1)).also {
            it.assignSpawnOwner(player, player.currentMapClock)
            npcRepo.add(it, Int.MAX_VALUE)
            it.respawns = false
        }

        fun fatalHit(npc: Npc) {
            npc.hitpoints = 0
            npc.queue("queue.death", 1)
            val mark = "hitmark.regular_damage_lit".asRSCM()
            val hit = Hit(HitType.Melee, Hitmark.fromPlayerSource(mark, mark, mark, 1, 0, player.slotId),
                player.uid.packed, null, null)
            events.publish(NpcHitEvents.Impact(npc, hit))
        }

        fun talk(harlow: Boolean = false) {
            talkNpc(if (harlow) "npc.dr_harlow" else "npc.morgan")
        }

        fun talkNpc(symbol: String) {
            val npc = Npc(symbol, player.coords.translateZ(1))
            run { assertTrue(events.publish(access(), NpcEvents.Op1(npc))) }
        }

        fun harlowMenus(): List<List<String>> = client.messages.filterIsInstance<RunClientScript>()
            .mapNotNull { it.values.getOrNull(1) as? String }
            .filter { it.contains("I'll get going.") }
            .map { it.split('|') }

        fun select(option: Int) {
            check(player.ui.containsModal("interface.chatmenu"))
            advance(listOf(option).iterator())
        }

        fun coffin() {
            val type = checkNotNull(ServerCacheManager.getObject("loc.vampcoffin".asRSCM()))
            val bound = BoundLocInfo(LocInfo(2, CoordGrid(3077, 9775), LocEntity(type.id, 10, 3)), type)
            run { assertTrue(events.publish(access(), LocEvents.Op1(bound, bound, type))) }
        }

        private fun run(block: suspend () -> Unit) {
            while (player.isDelayed) {
                player.currentMapClock++
                player.processedMapClock = player.currentMapClock
            }
            player.clearPendingAction(events)
            result = null
            player.activeCoroutine = coroutine
            block.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) { this@Fixture.result = result }
            })
            result?.getOrThrow()
        }

        fun until(predicate: () -> Boolean) {
            repeat(150) {
                if (predicate()) return
                advance()
            }
            fail<Unit>("Condition was not reached: ${output()}")
        }

        fun finish(options: List<Int> = emptyList()) {
            val selections = options.iterator()
            repeat(150) {
                if (coroutine.isIdle) return
                advance(selections)
            }
            fail<Unit>("Interaction did not finish: ${output()}")
        }

        private fun advance(options: Iterator<Int> = emptyList<Int>().iterator()) {
            if (coroutine.isAwaiting(ResumePauseButtonInput::class)) {
                val parent = listOf("chat_left", "chat_right", "messagebox", "chatmenu")
                    .firstOrNull { player.ui.containsModal("interface.$it") }
                    ?: error("Unknown dialogue: ${output()}")
                val input = if (parent == "chatmenu") {
                    ResumePauseButtonInput("component.chatmenu:options", if (options.hasNext()) options.next() else 1)
                } else ResumePauseButtonInput("component.$parent:continue", -1)
                coroutine.resumeWith(input)
            } else {
                player.currentMapClock++
                player.processedMapClock = player.currentMapClock
                coroutine.advance()
            }
            result?.getOrThrow()
        }

        fun cancel() {
            coroutine.cancel()
            assertInstanceOf(CancellationException::class.java, result?.exceptionOrNull())
            result = null
            player.activeCoroutine = null
        }

        fun assertReward() {
            assertEquals(3, script.quest.getQuestStage(player))
            assertEquals(3, player.vars["varp.vampire"])
            assertEquals(3, player.vars["varp.qp"])
            assertEquals(4825, player.statMap.getXP("stat.attack"))
            assertEquals(0, player.vampyreCompletionPending)
        }

        fun output() = client.messages.joinToString("\n")
    }

    private class RecordingClient : Client<Any, Any> {
        val messages = mutableListOf<Any>()
        override fun write(message: Any) { messages += message }
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private val restored = mutableListOf<() -> Unit>()

        @OptIn(InternalApi::class)
        @JvmStatic @BeforeAll fun cache() {
            ServerCacheManager.init(241).close()
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val old = field.get(null)
                restored += { field.set(null, old) }
            }
            val oldStorage = InvVirtualStorageHolder.instance
            restored += { InvVirtualStorageHolder.instance = oldStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic @AfterAll fun restore() {
            restored.asReversed().forEach { it() }
            restored.clear()
        }
    }
}
