package org.rsmod.content.other.treasuretrails

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.quest.manager.ItemRewardDisplay
import org.rsmod.content.quest.manager.Quest
import org.rsmod.content.quest.manager.QuestRequirementPolicy
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.content.quest.manager.rewards
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class BeginnerClueTransitionsTest {
    @Test
    fun `Reldo target and measured distance persist without reroll across save and reload`() {
        val f = Fixture()
        val target = f.reldo(length = 2)
        val expected = f.player.beginnerState()
        assertNull(f.player.receiveBeginnerDevice(expected))
        val device = checkNotNull(f.player.inv.objs.first { it?.id == BEGINNER_DEVICE.asRSCM() })
        val slot = f.player.inv.objs.indexOf(device)
        f.player.coords = target.target.coord.translate(xOffset = 50, zOffset = 30)
        assertEquals("The device is very hot.", f.player.feelBeginnerDevice(slot, device))
        val resumed = Fixture()
        for (i in f.player.inv.indices) resumed.player.inv[i] = f.player.inv[i]
        resumed.player.vars.backing.putAll(f.player.vars.backing)
        assertNull(resumed.player.assignBeginnerHotColdTarget(expected) { error("Must not reroll") })
        assertEquals(target.rowId, resumed.player.beginnerHotColdTarget)
        resumed.player.coords = target.target.coord.translate(xOffset = 49, zOffset = 30)
        assertEquals("The device is very hot, and warmer than last time.",
            resumed.player.feelBeginnerDevice(slot, device))
        assertEquals("The device is very hot, and the same temperature as last time.",
            resumed.player.feelBeginnerDevice(slot, device))
        resumed.player.coords = target.target.coord.translate(xOffset = 70, zOffset = 0)
        assertEquals("The device is hot, but colder than last time.",
            resumed.player.feelBeginnerDevice(slot, device))
        assertEquals(expected, resumed.player.beginnerState())
    }

    @Test
    fun `Reldo preserves full inventory and clue then gives one reusable device without completion`() {
        val f = Fixture()
        f.fill()
        f.reldo()
        val expected = f.player.beginnerState()
        val before = f.snapshot()
        assertEquals(ClueFailure.SPACE, f.player.receiveBeginnerDevice(expected))
        assertEquals(before, f.snapshot())
        f.player.inv[1] = null
        repeat(3) { assertNull(f.player.receiveBeginnerDevice(expected)) }
        assertEquals(1, f.player.inv.physicalCount(BEGINNER_DEVICE))
        assertEquals(1, f.player.inv.physicalCount(BEGINNER_SCROLL))
        assertEquals(0, f.player.inv.physicalCount(BEGINNER_CASKET))
        assertEquals(expected, f.player.beginnerState())
        val banked = Fixture()
        banked.reldo()
        banked.player.invMap.getOrPut("inv.bank")[0] = InvObj(BEGINNER_DEVICE)
        assertNull(banked.player.receiveBeginnerDevice(banked.player.beginnerState()))
        assertEquals(0, banked.player.inv.physicalCount(BEGINNER_DEVICE))
        assertEquals(1, banked.player.invMap.getOrPut("inv.bank").physicalCount(BEGINNER_DEVICE))
    }

    @Test
    fun `device handout and feeling revalidate vanished inputs and unrelated clues`() {
        val f = Fixture()
        f.reldo()
        val expected = f.player.beginnerState()
        f.player.inv[0] = null
        assertEquals(ClueFailure.INPUT, f.player.receiveBeginnerDevice(expected))
        f.put(1, BEGINNER_DEVICE)
        val device = checkNotNull(f.player.inv[1])
        assertEquals("The strange device is inactive.", f.player.feelBeginnerDevice(1, device))
        assertEquals(0, f.player.beginnerHotColdDistance)
        f.put(0, BEGINNER_SCROLL)
        f.put(1, BEGINNER_DEVICE)
        val before = f.snapshot()
        assertNull(f.player.feelBeginnerDevice(1, device))
        assertEquals(before, f.snapshot())
        f.player.storeBeginnerState(expected.copy(row = f.firstRow))
        assertEquals(ClueFailure.STALE, f.player.receiveBeginnerDevice(expected))
        assertEquals("The strange device is inactive.",
            f.player.feelBeginnerDevice(1, checkNotNull(f.player.inv[1])))
    }

    @Test
    fun `temperature thresholds and diagonal distances match beginner device boundaries`() {
        val bands = listOf(0..3 to "visibly shaking", 4..29 to "incredibly hot",
            30..69 to "very hot", 70..99 to "hot", 100..149 to "warm",
            150..199 to "cold", 200..499 to "very cold", 500..16383 to "ice cold")
        for ((range, text) in bands) {
            assertEquals(text, hotColdTemperature(range.first))
            assertEquals(text, hotColdTemperature(range.last))
        }
        val target = BeginnerHotColdTargets.ALL.first().target.coord
        assertEquals(3, hotColdDistance(target.translate(xOffset = 3, zOffset = -3), target))
        assertEquals(0, hotColdDistance(target.copy(level = target.level + 1), target))
    }

    @Test
    fun `all five hotcold destinations accept radius three only on their native plane`() {
        assertEquals(5, BeginnerHotColdTargets.ALL.size)
        for (target in BeginnerHotColdTargets.ALL) {
            val f = Fixture()
            f.reldo(targetRow = target.rowId)
            val expected = f.player.beginnerState()
            for (coords in listOf(target.target.coord.translate(xOffset = 4, zOffset = 0),
                target.target.coord.copy(level = target.target.coord.level + 1))) {
                f.player.coords = coords
                val before = f.snapshot()
                assertFalse(f.player.atBeginnerHotColdTarget())
                assertEquals(ClueFailure.STALE,
                    f.player.completeBeginnerHotColdStep(expected, target.rowId, 0))
                assertEquals(before, f.snapshot())
            }
            f.player.coords = target.target.coord.translate(xOffset = -3, zOffset = 3)
            assertTrue(f.player.atBeginnerHotColdTarget())
            assertEquals(ClueFailure.STALE, f.player.completeBeginnerStep(expected, 0))
            assertNull(f.player.completeBeginnerHotColdStep(expected, target.rowId, 0))
            assertEquals(1, f.player.inv.physicalCount(BEGINNER_CASKET))
            assertEquals(0, f.player.beginnerHotColdTarget)
            assertEquals(0, f.player.beginnerHotColdDistance)
            val completed = f.snapshot()
            assertEquals(ClueFailure.STALE,
                f.player.completeBeginnerHotColdStep(expected, target.rowId, 0))
            assertEquals(completed, f.snapshot())
        }
    }

    @Test
    fun `hotcold casket overflow keeps target reading clue and reusable device unchanged`() {
        val f = Fixture()
        val target = f.reldo()
        val expected = f.player.beginnerState()
        assertNull(f.player.receiveBeginnerDevice(expected))
        f.player.coords = target.target.coord
        val slot = f.player.inv.objs.indexOfFirst { it?.id == BEGINNER_DEVICE.asRSCM() }
        f.player.feelBeginnerDevice(slot, checkNotNull(f.player.inv[slot]))
        f.put(2, BEGINNER_CASKET, Int.MAX_VALUE)
        val before = f.snapshot()
        assertEquals(ClueFailure.SPACE,
            f.player.completeBeginnerHotColdStep(expected, target.rowId, 0))
        assertEquals(before, f.snapshot())
        f.put(2, BEGINNER_CASKET, Int.MAX_VALUE - 1)
        assertNull(f.player.completeBeginnerHotColdStep(expected, target.rowId, 0))
        assertEquals(Int.MAX_VALUE, f.player.inv.physicalCount(BEGINNER_CASKET))
        assertEquals(1, f.player.inv.physicalCount(BEGINNER_DEVICE))
    }

    @Test
    fun `hotcold stale generation or target and duplicate clue cannot advance or create a device`() {
        val f = Fixture()
        val target = f.reldo()
        val expected = f.player.beginnerState()
        f.player.coords = target.target.coord
        f.player.invMap.getOrPut("inv.bank")[0] = InvObj(BEGINNER_SCROLL)
        val before = f.snapshot()
        assertEquals(ClueFailure.DUPLICATE, f.player.receiveBeginnerDevice(expected))
        assertEquals(ClueFailure.DUPLICATE,
            f.player.completeBeginnerHotColdStep(expected, target.rowId, 0))
        assertEquals(before, f.snapshot())
        f.player.invMap.getOrPut("inv.bank").fillNulls()
        assertEquals(ClueFailure.STALE, f.player.completeBeginnerHotColdStep(expected,
            BeginnerHotColdTargets.ALL.last().rowId, 0))
        f.player.storeBeginnerState(expected.copy(generation = nextClueGeneration(expected.generation)))
        assertNull(f.player.assignBeginnerHotColdTarget(f.player.beginnerState()) { target.rowId })
        assertEquals(ClueFailure.STALE, f.player.receiveBeginnerDevice(expected))
        assertEquals(ClueFailure.STALE,
            f.player.completeBeginnerHotColdStep(expected, target.rowId, 0))
    }

    @Test
    fun `Charlie chooses one permanent request without consuming an already carried item`() {
        val f = Fixture(completedQuest = false)
        f.charlie(length = 2)
        val task = BeginnerCharlieTasks.ALL.first()
        f.player.inv[1] = InvObj(task.item)
        val inventory = f.player.inv.objs.toList()
        val expected = f.player.beginnerState()
        var choices = 0
        repeat(3) {
            assertNull(f.player.assignBeginnerCharlieTask(expected) { choices++; task.rowId })
        }
        assertEquals(1, choices)
        assertEquals(task.rowId, f.player.beginnerCharlieTask)
        assertEquals(expected, f.player.beginnerState())
        assertEquals(inventory, f.player.inv.objs.toList())
        val resumed = Fixture(completedQuest = false)
        resumed.player.vars.backing.putAll(f.player.vars.backing)
        for (slot in f.player.inv.indices) resumed.player.inv[slot] = f.player.inv[slot]
        assertNull(resumed.player.assignBeginnerCharlieTask(expected) { error("Must not reroll") })
        assertEquals(task.rowId, resumed.player.beginnerCharlieTask)
        assertNull(resumed.player.completeBeginnerCharlieStep(expected, task.rowId, f.otherRow))
        assertEquals(0, resumed.player.beginnerCharlieTask)
        assertEquals(1, resumed.player.beginnerCompletedSteps)
        assertEquals(f.otherRow, resumed.player.beginnerRow)
        assertEquals(1, resumed.player.inv.physicalCount(BEGINNER_SCROLL))
        assertEquals(0, resumed.player.inv.physicalCount(BEGINNER_CASKET))
    }

    @Test
    fun `all eight native Charlie requests consume exactly one premade item and finish once`() {
        assertEquals(8, BeginnerCharlieTasks.ALL.size)
        for (task in BeginnerCharlieTasks.ALL) {
            val f = Fixture()
            f.charlie()
            assertNull(f.player.assignBeginnerCharlieTask(f.player.beginnerState()) { task.rowId })
            f.player.inv[1] = InvObj(task.item)
            f.player.inv[2] = InvObj(task.item)
            val expected = f.player.beginnerState()
            assertNull(f.player.completeBeginnerCharlieStep(expected, task.rowId, 0), task.data.clueText)
            assertEquals(1, f.player.inv.objs.count { it?.id == task.item.id })
            assertEquals(1, f.player.inv.physicalCount(BEGINNER_CASKET))
            assertEquals(0, f.player.inv.physicalCount(BEGINNER_SCROLL))
            assertEquals(0, f.player.beginnerCharlieTask)
            val completed = f.snapshot()
            repeat(3) {
                assertEquals(ClueFailure.STALE,
                    f.player.completeBeginnerCharlieStep(expected, task.rowId, 0))
            }
            assertEquals(completed, f.snapshot())
        }
    }

    @Test
    fun `noted banked and equipped items cannot satisfy Charlie's carried unnoted request`() {
        val task = BeginnerCharlieTasks.ALL.first()
        val note = checkNotNull(ServerCacheManager.getItem(task.item.certlink))
        for (location in listOf("note", "inv.bank", "inv.worn")) {
            val f = Fixture()
            f.charlie()
            val expected = f.player.beginnerState()
            assertNull(f.player.assignBeginnerCharlieTask(expected) { task.rowId })
            when (location) {
                "note" -> f.player.inv[1] = InvObj(note)
                "inv.worn" -> f.player.worn[0] = InvObj(task.item)
                else -> f.player.invMap.getOrPut(location)[0] = InvObj(task.item)
            }
            val before = f.snapshot()
            assertEquals(ClueFailure.TASK_ITEM,
                f.player.completeBeginnerCharlieStep(expected, task.rowId, 0), location)
            assertEquals(before, f.snapshot())
        }
    }

    @Test
    fun `full inventory Charlie hand-in frees its own slots and casket overflow rolls everything back`() {
        val task = BeginnerCharlieTasks.ALL.first()
        val f = Fixture()
        f.fill()
        f.charlie()
        val expected = f.player.beginnerState()
        assertNull(f.player.assignBeginnerCharlieTask(expected) { task.rowId })
        f.player.inv[1] = InvObj(task.item)
        f.put(2, BEGINNER_CASKET, Int.MAX_VALUE)
        val before = f.snapshot()
        assertEquals(ClueFailure.SPACE,
            f.player.completeBeginnerCharlieStep(expected, task.rowId, 0))
        assertEquals(before, f.snapshot())
        f.put(2, BEGINNER_CASKET, Int.MAX_VALUE - 1)
        assertNull(f.player.completeBeginnerCharlieStep(expected, task.rowId, 0))
        assertEquals(Int.MAX_VALUE, f.player.inv.physicalCount(BEGINNER_CASKET))
        assertEquals(25, f.player.inv.physicalCount("obj.bronze_dagger"))
        assertEquals(0, f.player.inv.objs.count { it?.id == task.item.id })
    }

    @Test
    fun `vanished Charlie item or scroll cannot consume the other input or advance state`() {
        val task = BeginnerCharlieTasks.ALL.first()
        for (removedSlot in listOf(0, 1)) {
            val f = Fixture()
            f.charlie()
            val expected = f.player.beginnerState()
            assertNull(f.player.assignBeginnerCharlieTask(expected) { task.rowId })
            f.player.inv[1] = InvObj(task.item)
            f.player.inv[removedSlot] = null
            val before = f.snapshot()
            val failure = if (removedSlot == 0) ClueFailure.INPUT else ClueFailure.TASK_ITEM
            assertEquals(failure, f.player.completeBeginnerCharlieStep(expected, task.rowId, 0))
            assertEquals(before, f.snapshot())
        }
    }

    @Test
    fun `ordinary completion and mismatched task callbacks cannot bypass Charlie's request`() {
        val task = BeginnerCharlieTasks.ALL.first()
        val f = Fixture()
        f.charlie()
        val expected = f.player.beginnerState()
        assertEquals(ClueFailure.STALE, f.player.completeBeginnerStep(expected, 0))
        assertEquals(ClueFailure.STALE, f.player.completeBeginnerCharlieStep(expected, 0, 0))
        assertNull(f.player.assignBeginnerCharlieTask(expected) { task.rowId })
        f.player.inv[1] = InvObj(task.item)
        val before = f.snapshot()
        assertEquals(ClueFailure.STALE, f.player.completeBeginnerCharlieStep(
            expected, BeginnerCharlieTasks.ALL.last().rowId, 0))
        assertEquals(before, f.snapshot())
        f.player.storeBeginnerState(expected.copy(generation = nextClueGeneration(expected.generation)))
        assertEquals(0, f.player.beginnerCharlieTask)
        assertNull(f.player.assignBeginnerCharlieTask(f.player.beginnerState()) { task.rowId })
        val fresh = f.snapshot()
        assertEquals(ClueFailure.STALE,
            f.player.completeBeginnerCharlieStep(expected, task.rowId, 0))
        assertEquals(fresh, f.snapshot())
    }

    @Test
    fun `duplicate active clue rejects Charlie assignment and item hand-in intact`() {
        val task = BeginnerCharlieTasks.ALL.first()
        val f = Fixture()
        f.charlie()
        val expected = f.player.beginnerState()
        f.player.invMap.getOrPut("inv.bank")[0] = InvObj(BEGINNER_SCROLL)
        assertEquals(ClueFailure.DUPLICATE, f.player.assignBeginnerCharlieTask(expected) {
            error("An ineligible request must not roll")
        })
        assertEquals(0, f.player.beginnerCharlieTask)
        f.player.invMap.getOrPut("inv.bank").fillNulls()
        assertNull(f.player.assignBeginnerCharlieTask(expected) { task.rowId })
        f.player.inv[1] = InvObj(task.item)
        f.player.invMap.getOrPut("inv.bank")[0] = InvObj(BEGINNER_SCROLL)
        val before = f.snapshot()
        assertEquals(ClueFailure.DUPLICATE,
            f.player.completeBeginnerCharlieStep(expected, task.rowId, 0))
        assertEquals(before, f.snapshot())
    }

    @Test
    fun `trail lengths have exact ten forty-five forty-five distribution`() {
        assertEquals(mapOf(1 to 10, 2 to 45, 3 to 45),
            (0 until 100).groupingBy(::beginnerTrailLength).eachCount())
        assertThrows(IllegalArgumentException::class.java) { beginnerTrailLength(100) }
        assertEquals(1, nextClueGeneration(Int.MAX_VALUE))
    }

    @Test
    fun `box requires completed X Marks the Spot and preserves all input on rejection`() {
        val f = Fixture(completedQuest = false)
        f.put(0, BEGINNER_BOX)
        val before = f.snapshot()
        assertEquals(ClueFailure.QUEST, f.open())
        assertEquals(before, f.snapshot())
        f.set("varbit.cluequest", 8)
        assertNull(f.open())
        assertEquals(1, f.player.inv.physicalCount(BEGINNER_SCROLL))
        assertEquals(0, f.player.inv.physicalCount(BEGINNER_BOX))
    }

    @Test
    fun `single box opens in a full inventory while stacked boxes need another slot`() {
        val f = Fixture()
        f.fill()
        f.put(0, BEGINNER_BOX, 2)
        val before = f.snapshot()
        assertEquals(ClueFailure.SPACE, f.open())
        assertEquals(before, f.snapshot())
        f.put(0, BEGINNER_BOX)
        assertNull(f.open())
        assertEquals(27, f.player.inv.physicalCount("obj.bronze_dagger"))
        assertEquals(1, f.player.inv.physicalCount(BEGINNER_SCROLL))
    }

    @Test
    fun `active clue in bank grave or death office prevents opening another box`() {
        for (storage in listOf("inv.bank", "inv.gravestone", "inv.death_permanent")) {
            val f = Fixture()
            f.put(0, BEGINNER_BOX, 2)
            f.player.invMap.getOrPut(storage)[0] = InvObj(BEGINNER_SCROLL)
            val before = f.snapshot()
            assertEquals(ClueFailure.DUPLICATE, f.open(), storage)
            assertEquals(before, f.snapshot(), storage)
            assertEquals(3, ClueOwnership.count(f.player, ClueTier.Beginner))
        }
    }

    @Test
    fun `vanished or replaced box slot cannot be consumed from elsewhere`() {
        val f = Fixture()
        f.put(1, BEGINNER_BOX)
        f.put(0, "obj.bronze_dagger")
        val before = f.snapshot()
        assertEquals(ClueFailure.INPUT, f.open())
        assertEquals(before, f.snapshot())
        assertNull(f.open(slot = 1))
        val opened = f.snapshot()
        repeat(3) { assertEquals(ClueFailure.DUPLICATE, f.open(slot = 1)) }
        assertEquals(opened, f.snapshot())
    }

    @Test
    fun `three step trail gives only next scroll then only final casket`() {
        val f = Fixture()
        f.put(0, BEGINNER_BOX, 2)
        assertNull(f.open(length = 3))
        repeat(3) { step ->
            val expected = f.player.beginnerState()
            assertNull(f.player.completeBeginnerStep(expected, f.otherRow))
            val after = f.snapshot()
            repeat(2) { assertEquals(ClueFailure.STALE,
                f.player.completeBeginnerStep(expected, f.otherRow)) }
            assertEquals(after, f.snapshot())
            assertEquals(if (step == 2) 0 else 1, f.player.inv.physicalCount(BEGINNER_SCROLL))
            assertEquals(if (step == 2) 1 else 0, f.player.inv.physicalCount(BEGINNER_CASKET))
            assertEquals(1, f.player.inv.physicalCount(BEGINNER_BOX))
        }
        assertEquals(0, f.player.beginnerCompletedSteps)
        assertEquals(0, f.player.beginnerRow)
    }

    @Test
    fun `casket stack overflow preserves scroll and progress and exact limit succeeds once`() {
        val f = Fixture()
        f.put(0, BEGINNER_BOX)
        assertNull(f.open(length = 1))
        f.put(1, BEGINNER_CASKET, Int.MAX_VALUE)
        val before = f.snapshot()
        val expected = f.player.beginnerState()
        assertEquals(ClueFailure.SPACE, f.player.completeBeginnerStep(expected, 0))
        assertEquals(before, f.snapshot())
        f.put(1, BEGINNER_CASKET, Int.MAX_VALUE - 1)
        assertNull(f.player.completeBeginnerStep(expected, 0))
        assertEquals(Int.MAX_VALUE, f.player.inv.physicalCount(BEGINNER_CASKET))
        assertEquals(0, f.player.inv.physicalCount(BEGINNER_SCROLL))
        assertEquals(ClueFailure.STALE, f.player.completeBeginnerStep(expected, 0))
    }

    @Test
    fun `scroll vanishing after a clue match cannot advance or grant a casket`() {
        val f = Fixture()
        f.put(0, BEGINNER_BOX)
        assertNull(f.open(length = 1))
        val expected = f.player.beginnerState()
        f.player.inv.fillNulls()
        val before = f.snapshot()
        assertEquals(ClueFailure.INPUT, f.player.completeBeginnerStep(expected, 0))
        assertEquals(before, f.snapshot())
    }

    @Test
    fun `fresh trail resets abandoned progress and invalidates old completion callbacks`() {
        val f = Fixture()
        f.put(0, BEGINNER_BOX)
        assertNull(f.open(length = 3))
        assertNull(f.player.completeBeginnerStep(f.player.beginnerState(), f.otherRow))
        val abandoned = f.player.beginnerState()
        f.player.inv.fillNulls()
        f.put(0, BEGINNER_BOX)
        assertNull(f.open(length = 2))
        assertEquals(0, f.player.beginnerCompletedSteps)
        assertNotEquals(abandoned.generation, f.player.beginnerGeneration)
        val before = f.snapshot()
        assertEquals(ClueFailure.STALE, f.player.completeBeginnerStep(abandoned, f.otherRow))
        assertEquals(before, f.snapshot())
    }

    @Test
    fun `saved inventory and permanent vars resume the same trail without extra items`() {
        val old = Fixture()
        old.put(0, BEGINNER_BOX)
        assertNull(old.open(length = 2))
        assertNull(old.player.completeBeginnerStep(old.player.beginnerState(), old.otherRow))
        val resumed = Fixture()
        for (slot in old.player.inv.indices) resumed.player.inv[slot] = old.player.inv[slot]
        resumed.player.vars.backing.putAll(old.player.vars.backing)
        val expected = old.player.beginnerState()
        assertEquals(expected, resumed.player.beginnerState())
        assertNull(resumed.player.completeBeginnerStep(expected, 0))
        assertEquals(1, resumed.player.inv.physicalCount(BEGINNER_CASKET))
        assertEquals(0, resumed.player.inv.physicalCount(BEGINNER_SCROLL))
    }

    @Test
    fun `raw prequest clue initializes once and caskets never consume capacity`() {
        val f = Fixture(completedQuest = false)
        f.put(0, BEGINNER_SCROLL)
        f.put(1, BEGINNER_CASKET, Int.MAX_VALUE)
        assertNull(f.player.initializeBeginnerScroll(f.firstRow, 2))
        val before = f.snapshot()
        assertNull(f.player.initializeBeginnerScroll(f.otherRow, 1))
        assertEquals(before, f.snapshot())
        assertEquals(1, ClueOwnership.count(f.player, ClueTier.Beginner))
        assertEquals(2, ClueOwnership.capacity(f.player, ClueTier.Beginner))
        f.set("varbit.scroll_case_beginner_minor", 1)
        f.set("varbit.scroll_case_beginner_major", 1)
        f.set("varbit.scroll_case_mimic", 1)
        assertEquals(5, ClueOwnership.capacity(f.player, ClueTier.Beginner))
    }

    @Test
    fun `case consumption frees one item and permanently raises only its intended cap once`() {
        val f = Fixture()
        f.fill()
        f.put(0, "obj.scroll_case_beginner_minor")
        assertTrue(f.player.consumeScrollCase(0, "scroll_case_beginner_minor"))
        assertEquals(0, f.player.inv.physicalCount("obj.scroll_case_beginner_minor"))
        assertEquals(3, ClueOwnership.capacity(f.player, ClueTier.Beginner))
        assertEquals(2, ClueOwnership.capacity(f.player, ClueTier.Easy))
        f.put(0, "obj.scroll_case_beginner_minor")
        val before = f.snapshot()
        assertFalse(f.player.consumeScrollCase(0, "scroll_case_beginner_minor"))
        assertEquals(before, f.snapshot())
        assertEquals(1, f.player.inv.physicalCount("obj.scroll_case_beginner_minor"))
        f.put(0, "obj.scroll_case_mimic")
        assertTrue(f.player.consumeScrollCase(0, "scroll_case_mimic"))
        assertEquals(4, ClueOwnership.capacity(f.player, ClueTier.Beginner))
        assertEquals(3, ClueOwnership.capacity(f.player, ClueTier.Easy))
    }

    @Test
    fun `vanished scroll case never sets an unlock bit`() {
        val f = Fixture()
        f.put(1, "obj.scroll_case_beginner_major")
        assertFalse(f.player.consumeScrollCase(0, "scroll_case_beginner_major"))
        assertEquals(0, f.player.vars["varbit.scroll_case_beginner_major"])
        assertEquals(1, f.player.inv.physicalCount("obj.scroll_case_beginner_major"))
    }

    @Test
    fun `drop policy shares box and active clue capacity without counting reward caskets`() {
        val f = Fixture()
        f.put(0, BEGINNER_BOX)
        f.put(1, BEGINNER_SCROLL)
        f.put(2, BEGINNER_CASKET, Int.MAX_VALUE)
        assertFalse(ClueDropPolicy.mayDrop(f.player, BEGINNER_SCROLL))
        f.set("varbit.option_trail_reminder_beginner", 1)
        assertFalse(ClueDropPolicy.mayDrop(f.player, BEGINNER_BOX))
        f.set("varbit.scroll_case_beginner_minor", 1)
        assertTrue(ClueDropPolicy.mayDrop(f.player, BEGINNER_BOX))
        assertTrue(ClueDropPolicy.mayDrop(f.player, "obj.coins"))
        f.put(0, BEGINNER_BOX, 2)
        f.set("varbit.option_trail_reminder_beginner", 0)
        assertTrue(ClueDropPolicy.mayDrop(f.player, BEGINNER_BOX))
    }

    @OptIn(InternalApi::class)
    private class Fixture(completedQuest: Boolean = true) {
        val player = Player().apply {
            inv = inventory("inv.inv", 28)
            worn = inventory("inv.worn", 14)
        }
        val firstRow = "dbrow.cluehelper_map_beginner_0".asRSCM()
        val otherRow = "dbrow.cluehelper_map_beginner_1".asRSCM()
        init { if (completedQuest) set("varbit.cluequest", 8) }
        fun set(symbol: String, value: Int) = VarPlayerIntMapSetter.set(player, symbol, value)
        fun put(slot: Int, obj: String, count: Int = 1) { player.inv[slot] = InvObj(obj, count) }
        fun fill() { for (slot in player.inv.indices) put(slot, "obj.bronze_dagger") }
        fun open(slot: Int = 0, length: Int = 2) = player.openBeginnerBox(slot, firstRow, length)
        fun snapshot() = listOf(player.inv.objs.toList(), player.beginnerState(), player.beginnerCharlieTask,
            player.beginnerHotColdTarget, player.beginnerHotColdDistance)
        fun charlie(length: Int = 1) {
            put(0, BEGINNER_SCROLL)
            player.storeBeginnerState(BeginnerClueState(CHARLIE_ROW.asRSCM(), length, 0, 1))
        }
        fun reldo(length: Int = 1, targetRow: Int = BeginnerHotColdTargets.ALL.first().rowId): org.rsmod.api.table.cluehelper.CluehelperClueHotcoldRow {
            put(0, BEGINNER_SCROLL)
            player.storeBeginnerState(BeginnerClueState(RELDO_ROW.asRSCM(), length, 0, 1))
            assertNull(player.assignBeginnerHotColdTarget(player.beginnerState()) { targetRow })
            return checkNotNull(BeginnerHotColdTargets.get(targetRow))
        }
        private fun inventory(symbol: String, size: Int) = Inventory(
            checkNotNull(ServerCacheManager.getInventory(symbol.asRSCM())), arrayOfNulls(size),
        )
    }

    companion object {
        private val RESTORATIONS = mutableListOf<() -> Unit>()

        @OptIn(InternalApi::class)
        @JvmStatic @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
            val policy = QuestRequirements.activePolicy()
            RESTORATIONS += { QuestRequirements.install(policy) }
            QuestRequirements.install(QuestRequirementPolicy())
            Quest.register("quest_xmarksthespot", "varp.cluequest_main",
                ItemRewardDisplay("obj.cluequest_casket"), rewards {}, "varbit.cluequest")
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val old = field.get(null)
                RESTORATIONS += { field.set(null, old) }
            }
            val oldStorage = InvVirtualStorageHolder.instance
            RESTORATIONS += { InvVirtualStorageHolder.instance = oldStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic @AfterAll
        fun restore() { RESTORATIONS.asReversed().forEach { it() }; RESTORATIONS.clear() }
    }
}
