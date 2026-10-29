package org.rsmod.content.other.treasuretrails

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import net.rsprot.protocol.game.outgoing.interfaces.IfOpenSub
import net.rsprot.protocol.game.outgoing.misc.player.RunClientScript
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.table.GameframeRow
import org.rsmod.events.EventBus
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.ui.Component

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class BeginnerClueHelperTest {
    @Test
    fun `inactive stale device and other-tier shared coordinate never corrupt beginner measurement history`() {
        val f = Fixture(RELDO_ROW.asRSCM())
        val target = BeginnerHotColdTargets.ALL.first()
        f.player.beginnerHotColdTarget = target.rowId
        f.player.inv[1] = InvObj(BEGINNER_DEVICE)
        f.player.coords = target.target.coord.translate(xOffset = 40, zOffset = 0)
        f.feel()
        val first = f.player.beginnerHotColdCoord
        f.player.nativeHotColdCoord = target.target.coord.translate(xOffset = 400, zOffset = 400).packed
        f.player.coords = target.target.coord.translate(xOffset = 35, zOffset = 0)
        f.feel()
        assertEquals(listOf(f.player.coords.packed, first, 35, 40), f.script(CLUE_HELPER_HOTCOLD).values)
        val device = checkNotNull(f.player.inv[1])
        f.player.inv[1] = InvObj(BEGINNER_DEVICE)
        val saved = f.player.beginnerHotColdCoord to f.player.nativeHotColdCoord
        assertNull(f.player.feelBeginnerDevice(1, device))
        assertEquals(saved, f.player.beginnerHotColdCoord to f.player.nativeHotColdCoord)
        f.player.inv[0] = null
        assertEquals("The strange device is inactive.", f.player.feelBeginnerDevice(1, checkNotNull(f.player.inv[1])))
        assertEquals(saved, f.player.beginnerHotColdCoord to f.player.nativeHotColdCoord)
        f.player.storeBeginnerState(BeginnerClueState(0, 0, 0, f.player.beginnerGeneration))
        assertEquals(0, f.player.beginnerHotColdCoord)
        assertEquals(saved.second, f.player.nativeHotColdCoord)
    }

    @Test
    fun `all supported initial clues use native helper row and repeated synchronization is silent`() {
        for (clue in BeginnerClues.ALL) {
            val f = Fixture(clue.rowId)
            f.helper.show(f.player)
            assertEquals(clue.rowId, f.player.nativeClueHelperRow)
            assertEquals(listOf(clue.rowId), f.script(CLUE_HELPER_INIT).values)
            val before = f.client.messages.toList()
            repeat(3) { f.helper.synchronizeAll() }
            assertEquals(before, f.client.messages)
            assertEquals(1, f.player.inv.physicalCount(BEGINNER_SCROLL))
        }
    }

    @Test
    fun `Charlie helper follows saved request without consuming items or advancing clue`() {
        val f = Fixture(CHARLIE_ROW.asRSCM())
        f.helper.show(f.player)
        val expected = f.player.beginnerState()
        for (task in BeginnerCharlieTasks.ALL) {
            f.player.beginnerCharlieTask = task.rowId
            f.helper.synchronizeAll()
            assertEquals(task.rowId, f.player.nativeClueHelperRow)
            assertEquals(listOf(task.rowId), f.script(CLUE_HELPER_INIT).values)
        }
        assertEquals(expected, f.player.beginnerState())
        assertEquals(0, f.player.inv.physicalCount(BEGINNER_CASKET))
    }

    @Test
    fun `Reldo sends exact native four-argument readings and saves actual coordinates`() {
        val f = Fixture(RELDO_ROW.asRSCM())
        val target = BeginnerHotColdTargets.ALL.first()
        f.player.beginnerHotColdTarget = target.rowId
        f.player.inv[1] = InvObj(BEGINNER_DEVICE)
        f.helper.show(f.player)
        assertEquals(RELDO_ROW.asRSCM(), f.player.nativeClueHelperRow)
        f.player.coords = target.target.coord.translate(xOffset = 80, zOffset = 70)
        val first = f.player.coords.packed
        f.feel()
        assertEquals(first, f.player.beginnerHotColdCoord)
        assertEquals(first, f.player.nativeHotColdCoord)
        assertEquals(target.rowId, f.player.nativeClueHelperRow)
        assertEquals(listOf(first, -1, 80, -1), f.script(CLUE_HELPER_HOTCOLD).values)
        f.player.coords = target.target.coord.translate(xOffset = 29, zOffset = 29)
        val second = f.player.coords.packed
        f.feel()
        assertEquals(listOf(second, first, 29, 80), f.script(CLUE_HELPER_HOTCOLD).values)
        assertEquals(2, f.scripts(CLUE_HELPER_HOTCOLD).size)
    }

    @Test
    fun `reconnect replays last actual observation without fabricating a previous coordinate`() {
        val old = Fixture(RELDO_ROW.asRSCM())
        old.player.beginnerHotColdTarget = BeginnerHotColdTargets.ALL.first().rowId
        old.player.inv[1] = InvObj(BEGINNER_DEVICE)
        old.player.coords = BeginnerHotColdTargets.ALL.first().target.coord.translate(xOffset = 155, zOffset = 60)
        old.feel()
        val resumed = Fixture(RELDO_ROW.asRSCM())
        resumed.player.vars.backing.putAll(old.player.vars.backing)
        resumed.player.coords = BeginnerHotColdTargets.ALL.first().target.coord
        resumed.helper.show(resumed.player)
        assertEquals(listOf(old.player.coords.packed, -1, 155, -1),
            resumed.script(CLUE_HELPER_HOTCOLD).values)
        assertEquals(old.player.beginnerState(), resumed.player.beginnerState())
        assertEquals(old.player.beginnerHotColdTarget, resumed.player.beginnerHotColdTarget)
    }

    @Test
    fun `close disable and reset affect presentation only and respect native option settings`() {
        val f = Fixture()
        val expected = f.player.beginnerState()
        f.helper.show(f.player)
        f.helper.close(f.player)
        repeat(2) { f.helper.synchronizeAll() }
        assertFalse(f.player.ui.containsOverlay(CLUE_HELPER))
        assertEquals(expected.row, f.player.nativeClueHelperRow)
        assertTrue(f.scripts(CLUE_HELPER_RESET).isEmpty(), "Closing the box must not clear native NPC highlights")
        f.helper.show(f.player)
        assertTrue(f.player.ui.containsOverlay(CLUE_HELPER))
        f.helper.reset(f.player)
        f.helper.disable(f.player)
        assertFalse(f.player.nativeClueInfoboxEnabled)
        assertFalse(f.player.ui.containsOverlay(CLUE_HELPER))
        assertEquals(expected.row, f.player.nativeClueHelperRow)
        f.set("varbit.option_cluehelper_enabled", 0)
        f.helper.synchronizeAll()
        assertEquals(-1, f.player.nativeClueHelperRow)
        f.set("varbit.option_cluehelper_enabled", 1)
        f.player.nativeClueInfoboxEnabled = true
        f.helper.synchronizeAll()
        assertTrue(f.player.ui.containsOverlay(CLUE_HELPER))
        assertEquals(expected, f.player.beginnerState())
        assertEquals(1, f.player.inv.physicalCount(BEGINNER_SCROLL))
    }

    @Test
    fun `banked removed or completed clue clears stale helper without changing saved progression`() {
        for (change in listOf("bank", "removed", "completed")) {
            val f = Fixture()
            f.helper.show(f.player)
            if (change == "completed") f.player.beginnerRow = 0 else f.player.inv[0] = null
            if (change == "bank") f.player.invMap.getOrPut("inv.bank")[0] = InvObj(BEGINNER_SCROLL)
            val expected = f.player.beginnerState()
            f.helper.synchronizeAll()
            assertFalse(f.player.ui.containsOverlay(CLUE_HELPER), change)
            assertEquals(-1, f.player.nativeClueHelperRow, change)
            val packets = f.client.messages.toList()
            repeat(2) { f.helper.synchronizeAll(); f.helper.clear(f.player) }
            assertEquals(packets, f.client.messages)
            assertEquals(expected, f.player.beginnerState())
        }
    }

    @Test
    fun `new trail at same native row resets presentation and foreign helper ownership is preserved`() {
        val f = Fixture()
        f.helper.show(f.player)
        f.helper.close(f.player)
        f.player.beginnerGeneration++
        f.helper.synchronizeAll()
        assertTrue(f.player.ui.containsOverlay(CLUE_HELPER))
        assertEquals(2, f.scripts(CLUE_HELPER_INIT).size)
        val foreignRow = BeginnerClues.ALL.first { it.rowId != f.player.beginnerRow }.rowId
        f.player.nativeClueHelperRow = foreignRow
        val before = f.client.messages.toList()
        f.helper.reset(f.player)
        f.helper.disable(f.player)
        assertTrue(f.player.nativeClueInfoboxEnabled)
        f.helper.synchronizeAll()
        f.helper.clear(f.player)
        assertEquals(foreignRow, f.player.nativeClueHelperRow)
        assertEquals(before, f.client.messages)
    }

    @Test
    fun `helper target uses native mappings on fixed and both resizable gameframes`() {
        val tops = listOf("interface.toplevel", "interface.toplevel_osrs_stretch", "interface.toplevel_pre_eoc")
            .map { it.asRSCM() }.toSet()
        val frames = GameframeRow.all().filter { it.toplevel in tops }
        assertEquals(tops, frames.map { it.toplevel }.toSet())
        for (frame in frames) {
            val f = Fixture()
            val mappings = frame.mappings.filterValuesNotNull().associate { it.key.packed to it.value.packed }
            val base = CLUE_HELPER_TARGET.asRSCM()
            val target = checkNotNull(mappings[base]) { "Native frame ${frame.toplevel} lacks helper target mapping" }
            for ((from, to) in mappings) f.player.ui.gameframe[Component(from)] = Component(to)
            f.helper.show(f.player)
            val opened = f.client.messages.filterIsInstance<IfOpenSub>().single()
            assertEquals(target, opened.destinationCombinedId)
            assertEquals(CLUE_HELPER.asRSCM(), opened.interfaceId)
        }
    }

    @OptIn(InternalApi::class)
    private class Fixture(row: Int = BeginnerClues.ALL.first().rowId) {
        val client = RecordingClient()
        val helper = BeginnerClueHelper(EventBus())
        val player = Player(client = client).apply {
            inv = Inventory(checkNotNull(ServerCacheManager.getInventory("inv.inv".asRSCM())), arrayOfNulls(28))
            worn = Inventory(checkNotNull(ServerCacheManager.getInventory("inv.worn".asRSCM())), arrayOfNulls(14))
            inv[0] = InvObj(BEGINNER_SCROLL)
            storeBeginnerState(BeginnerClueState(row, 2, 0, 1))
            nativeClueHelperRow = -1
        }
        init {
            set("varbit.option_cluehelper_enabled", 1)
            set("varbit.option_cluehelper_infobox_enabled", 1)
        }
        fun set(symbol: String, value: Int) = VarPlayerIntMapSetter.set(player, symbol, value)
        fun scripts(symbol: String) = client.messages.filterIsInstance<RunClientScript>().filter { it.id == symbol.asRSCM() }
        fun script(symbol: String) = scripts(symbol).last()
        fun feel() {
            val coord = player.beginnerHotColdCoord
            val distance = player.beginnerHotColdDistance
            assertNotNull(player.feelBeginnerDevice(1, checkNotNull(player.inv[1])))
            helper.measured(player, coord, distance)
        }
    }

    private class RecordingClient : Client<Any, Any> {
        val messages = mutableListOf<Any>()
        override fun write(message: Any) { messages += message }
        override fun close() = Unit
        override fun read(player: Player) = Unit
        override fun flush() = Unit
        override fun flushHighPriority() = Unit
        override fun unregister(service: Any, player: Player) = Unit
    }

    companion object {
        @JvmStatic @BeforeAll fun cache() { ServerCacheManager.init(241).close() }
    }
}
