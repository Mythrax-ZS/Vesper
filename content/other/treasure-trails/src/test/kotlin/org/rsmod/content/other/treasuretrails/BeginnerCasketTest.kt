package org.rsmod.content.other.treasuretrails

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.InvScope
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
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.invtx.invClear
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.content.interfaces.collectionlog.collectionTransmit
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.obj.Obj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class BeginnerCasketTest {
    @Test
    fun `entire 44280 outcome space has exact published unique black common and cabbage odds`() {
        val outcomes = (0 until BeginnerRewards.totalWeight).map(BeginnerRewards::select)
        val grouped = outcomes.groupingBy { it.item.id }.eachCount()
        assertEquals(74, grouped.size)
        assertEquals(mapOf("UNIQUE" to 15, "BLACK" to 18, "COMMON" to 40, "CABBAGE" to 1),
            BeginnerRewards.rows.groupingBy { it.pool }.eachCount())
        for (row in BeginnerRewards.rows) {
            val expected = when (row.pool) {
                "UNIQUE" -> 123 // 1/360 of 44,280
                "BLACK" -> 55 // 11/8,856
                "COMMON" -> 990 // 11/492
                "CABBAGE" -> 1845 // 1/24
                else -> error("Unexpected pool")
            }
            assertEquals(expected, grouped[row.item.id], row.item.internalName)
        }
        assertThrows(IllegalArgumentException::class.java) { BeginnerRewards.select(44_280) }
        assertThrows(IllegalArgumentException::class.java) { BeginnerRewards.select(-1) }
    }

    @Test
    fun `one two three rolls and every quantity endpoint and note transform are reachable`() {
        var offset = 0
        for (row in BeginnerRewards.rows) {
            for (upper in listOf(false, true)) {
                for (rolls in 1..3) {
                    val random = RewardRandom(rolls, offset, upper)
                    val loot = BeginnerRewards.roll(random)
                    assertEquals(rolls, loot.size)
                    val id = if (row.noted) row.item.certlink else row.item.id
                    assertTrue(loot.all { it.id == id && it.count == if (upper) row.maximum else row.minimum })
                    assertEquals(rolls, random.selections)
                    assertEquals(rolls + 1, random.rangeCalls)
                }
            }
            offset += row.weight
        }
    }

    @Test
    fun `opening consumes exactly one expected casket and logs before physical delivery`() {
        val f = Fixture()
        f.put(0, BEGINNER_CASKET, 2)
        val source = f.player.inv[0]!!
        assertNull(f.open(source = source, rewards = listOf(InvObj("obj.mole_slippers"))).failure)
        assertEquals(1, f.player.beginnerCasketsOpened)
        assertEquals(1, f.player.totalCasketsOpened)
        assertEquals(1, f.player.inv.physicalCount(BEGINNER_CASKET))
        assertEquals(0, f.player.inv.physicalCount("obj.mole_slippers"))
        assertEquals(1, f.player.collectionTransmit.physicalCount("obj.mole_slippers"))
        assertEquals(CasketFailure.INPUT, f.open(source = source).failure)
        assertTrue(f.deliver())
        assertTrue(f.deliver())
        assertEquals(1, f.player.inv.physicalCount("obj.mole_slippers"))
        assertEquals(1, f.player.collectionTransmit.physicalCount("obj.mole_slippers"))
        assertEquals(1, f.rollCalls)
    }

    @Test
    fun `vanished or replaced input never rolls awards milestones or logs`() {
        val f = Fixture()
        f.put(0, BEGINNER_CASKET)
        val source = f.player.inv[0]!!
        f.player.inv[0] = null
        assertEquals(CasketFailure.INPUT, f.open(source = source).failure)
        f.put(0, "obj.bronze_dagger")
        assertEquals(CasketFailure.INPUT, f.open(source = source).failure)
        assertEquals(0, f.rollCalls)
        assertEquals(0, f.player.beginnerCasketsOpened)
        assertTrue(f.player.collectionTransmit.isEmpty())
    }

    @Test
    fun `unsupported young account and Entrana preserve casket without consuming randomness`() {
        val f = Fixture()
        f.put(0, BEGINNER_CASKET)
        f.set("varp.playtime", 59_999)
        assertEquals(CasketFailure.ACCOUNT_AGE_UNVERIFIED, f.open().failure)
        f.set("varp.playtime", 60_000)
        f.player.coords = CoordGrid(2834, 3335, 0)
        assertEquals(CasketFailure.ENTRANA, f.open().failure)
        assertEquals(1, f.player.inv.physicalCount(BEGINNER_CASKET))
        assertEquals(0, f.rollCalls)
        assertTrue(onEntranaSurface(CoordGrid(2752, 3328, 0)))
        assertTrue(onEntranaSurface(CoordGrid(2879, 3391, 1)))
        assertFalse(onEntranaSurface(CoordGrid(2751, 3328, 0)))
        assertFalse(onEntranaSurface(CoordGrid(2880, 3391, 0)))
        f.player.coords = CoordGrid(2822, 9774, 0) // Entrana Dungeon is exempt.
        assertNull(f.open().failure)
    }

    @Test
    fun `completion counter overflow and reward journal overflow preserve every input`() {
        val f = Fixture()
        f.put(0, BEGINNER_CASKET)
        f.player.beginnerCasketsOpened = Int.MAX_VALUE
        assertEquals(CasketFailure.COUNTER_LIMIT, f.open().failure)
        assertEquals(0, f.rollCalls)
        f.player.beginnerCasketsOpened = 0
        val huge = listOf(InvObj("obj.airrune", Int.MAX_VALUE), InvObj("obj.airrune"))
        assertEquals(CasketFailure.SPACE, f.open(rewards = huge).failure)
        assertEquals(1, f.player.inv.physicalCount(BEGINNER_CASKET))
        assertEquals(0, f.player.beginnerCasketsOpened)
        assertTrue(f.pending.isEmpty())
    }

    @Test
    fun `full inventory receives freed casket slot and drops only the remainder`() {
        val f = Fixture()
        f.fill()
        f.put(0, BEGINNER_CASKET)
        val loot = listOf(InvObj("obj.mole_slippers"), InvObj("obj.frog_slippers"), InvObj("obj.bear_slippers"))
        assertNull(f.open(rewards = loot).failure)
        assertTrue(f.deliver())
        assertEquals(1, f.player.inv.physicalCount("obj.mole_slippers"))
        assertEquals(2, f.ground().sumOf { it.count })
        assertTrue(f.pending.isEmpty())
        assertTrue(f.deliver())
        assertEquals(2, f.ground().sumOf { it.count })
    }

    @Test
    fun `partial stack space commits inventory and ground amounts without integer overflow`() {
        val f = Fixture()
        f.fill()
        f.put(0, BEGINNER_CASKET, 2)
        f.put(1, "obj.airrune", Int.MAX_VALUE - 3)
        assertNull(f.open(rewards = listOf(InvObj("obj.airrune", 35))).failure)
        assertTrue(f.deliver())
        assertEquals(Int.MAX_VALUE, f.player.inv.physicalCount("obj.airrune"))
        assertEquals(32, f.ground().sumOf { it.count })
        assertTrue(f.pending.isEmpty())
    }

    @Test
    fun `failed ground placement rolls back the inventory portion and pending consume`() {
        val f = Fixture()
        f.fill()
        f.put(0, BEGINNER_CASKET, 2)
        f.put(1, "obj.airrune", Int.MAX_VALUE - 3)
        assertNull(f.open(rewards = listOf(InvObj("obj.airrune", 35))).failure)
        assertFalse(f.player.deliverBeginnerRewards { false })
        assertEquals(Int.MAX_VALUE - 3, f.player.inv.physicalCount("obj.airrune"))
        assertEquals(35, f.pending.physicalCount("obj.airrune"))
        assertEquals(CasketFailure.PENDING, f.open().failure)
        assertEquals(1, f.rollCalls)
        assertTrue(f.deliver())
        assertEquals(32, f.ground().sumOf { it.count })
    }

    @Test
    fun `noted food survives journal inventory display and full inventory ground delivery`() {
        val cabbage = checkNotNull(ServerCacheManager.getItem("obj.cabbage".asRSCM())).certlink
        for (full in listOf(false, true)) {
            val f = Fixture()
            if (full) f.fill()
            f.put(0, BEGINNER_CASKET, 2)
            val notedCabbage = checkNotNull(ServerCacheManager.getItem(cabbage))
            val rewards = listOf(InvObj(notedCabbage, 5), InvObj(notedCabbage, 9))
            assertNull(f.open(rewards = rewards).failure)
            assertEquals(14, f.pending.countId(cabbage))
            assertEquals(0, f.pending.physicalCount("obj.cabbage"))
            val display = f.player.storeBeginnerRewardPresentation(rewards)
            assertEquals(14, display.countId(cabbage))
            assertEquals(1, display.occupiedSpace())
            assertEquals(14, f.player.beginnerRewardPresentation().countId(cabbage))
            assertTrue(f.deliver())
            assertEquals(if (full) 0 else 14, f.player.inv.countId(cabbage))
            assertEquals(if (full) 14 else 0, f.ground().filter { it.type == cabbage }.sumOf { it.count })
            assertEquals(0, f.player.inv.physicalCount("obj.cabbage"))
        }
    }

    @Test
    fun `ground merge and existing maximum stack preserve total count without replay`() {
        for (old in listOf(50, Int.MAX_VALUE)) {
            val f = Fixture()
            f.fill()
            f.put(0, BEGINNER_CASKET, 2)
            assertTrue(f.repo.add(Obj.fromOwner(f.player, f.player.coords, "obj.airrune", old), 100))
            assertNull(f.open(rewards = listOf(InvObj("obj.airrune", 20))).failure)
            assertTrue(f.deliver())
            assertTrue(f.deliver())
            assertEquals(old.toLong() + 20, f.ground().sumOf { it.count.toLong() })
            assertEquals(if (old == 50) 1 else 2, f.ground().size)
        }
    }

    @Test
    fun `persisted pending rewards reconnect without reroll completion or duplicate collection log`() {
        val old = Fixture()
        old.fill()
        old.put(0, BEGINNER_CASKET, 2)
        assertNull(old.open(rewards = listOf(InvObj("obj.mole_slippers"))).failure)
        assertFalse(old.player.deliverBeginnerRewards { false })
        assertEquals(InvScope.Perm, old.pending.type.scope)
        val next = Fixture()
        old.player.inv.objs.copyInto(next.player.inv.objs)
        next.player.vars.backing.putAll(old.player.vars.backing)
        for (inventory in old.player.invMap.values.filter { it.type.scope == InvScope.Perm }) {
            inventory.objs.copyInto(next.player.invMap.getOrPut(inventory.internalName).objs)
        }
        assertTrue(next.deliver())
        assertEquals(1, next.ground().sumOf { it.count })
        assertTrue(next.pending.isEmpty())
        assertEquals(1, next.player.beginnerCasketsOpened)
        assertEquals(1, next.player.collectionTransmit.physicalCount("obj.mole_slippers"))
        assertEquals(0, next.rollCalls)
    }

    @Test
    fun `case milestones occur at fifty and one hundred and unlock only on consumption`() {
        for ((before, item) in listOf(49 to "obj.scroll_case_beginner_minor", 99 to "obj.scroll_case_beginner_major")) {
            val f = Fixture()
            f.player.beginnerCasketsOpened = before
            f.player.totalCasketsOpened = before
            f.put(0, BEGINNER_CASKET)
            assertNull(f.open().failure)
            assertEquals(1, f.pending.physicalCount(item))
            assertEquals(1, f.player.collectionTransmit.physicalCount(item))
            assertEquals(2, ClueOwnership.capacity(f.player, ClueTier.Beginner))
            assertTrue(f.deliver())
            val slot = f.player.inv.objs.indexOfFirst { it?.id == item.asRSCM() }
            assertTrue(f.player.consumeScrollCase(slot, item.removePrefix("obj.")))
            assertEquals(3, ClueOwnership.capacity(f.player, ClueTier.Beginner))
        }
        val early = Fixture()
        early.player.beginnerCasketsOpened = 48
        early.put(0, BEGINNER_CASKET)
        assertNull(early.open().failure)
        assertEquals(0, early.pending.physicalCount("obj.scroll_case_beginner_minor"))
    }

    @Test
    fun `lost case is reissued while held banked and consumed cases are not duplicated`() {
        val f = Fixture()
        f.player.beginnerCasketsOpened = 50
        f.put(0, BEGINNER_CASKET, 4)
        val case = "obj.scroll_case_beginner_minor"
        assertNull(f.open().failure)
        assertEquals(1, f.pending.physicalCount(case))
        assertTrue(f.deliver())
        assertNull(f.open().failure)
        assertEquals(0, f.pending.physicalCount(case))
        assertTrue(f.deliver())
        val slot = f.player.inv.objs.indexOfFirst { it?.id == case.asRSCM() }
        f.player.invMap.getOrPut("inv.bank")[0] = f.player.inv[slot]
        f.player.inv[slot] = null
        assertNull(f.open().failure)
        assertEquals(0, f.pending.physicalCount(case))
        assertTrue(f.deliver())
        f.player.invMap.getOrPut("inv.bank")[0] = null
        f.set("varbit.scroll_case_beginner_minor", 1)
        assertNull(f.open().failure)
        assertEquals(0, f.pending.physicalCount(case))
    }

    @Test
    fun `Explore unlocks at six hundred including historical counts and does not reset`() {
        val early = Fixture()
        early.player.beginnerCasketsOpened = 598
        early.put(0, BEGINNER_CASKET, 2)
        assertNull(early.open().failure)
        assertEquals(0, early.player.vars["varbit.emote_explore"])
        assertTrue(early.deliver())
        assertNull(early.open().failure)
        assertEquals(1, early.player.vars["varbit.emote_explore"])
        val historical = Fixture()
        historical.player.beginnerCasketsOpened = 700
        historical.put(0, BEGINNER_CASKET)
        assertNull(historical.open().failure)
        assertEquals(1, historical.player.vars["varbit.emote_explore"])
    }

    @Test
    fun `presentation cannot feed Barrows logout inventory and is a detached immutable snapshot`() {
        val f = Fixture()
        val display = f.player.invMap.getOrPut(BEGINNER_DISPLAY)
        assertEquals(InvScope.Temp, display.type.scope)
        assertTrue(f.player.invAdd(display, "obj.mole_slippers").success)
        val packet = f.player.beginnerRewardPresentation()
        assertEquals(NATIVE_REWARD_INV.asRSCM(), packet.type.id)
        assertEquals(1, packet.physicalCount("obj.mole_slippers"))
        assertNull(f.player.invMap[NATIVE_REWARD_INV])
        // Barrows logout reads this map entry; it must not see clue presentation items.
        val barrows = f.player.invMap.getOrPut(NATIVE_REWARD_INV)
        assertTrue(barrows.isEmpty())
        f.player.invClear(display)
        assertEquals(1, packet.physicalCount("obj.mole_slippers"))
        repeat(3) { f.player.invClear(display); assertTrue(f.deliver()) }
        assertTrue(barrows.isEmpty())
        assertEquals(0, f.player.inv.physicalCount("obj.mole_slippers"))
    }

    private fun Inventory.countId(id: Int): Int = objs.filterNotNull().filter { it.id == id }.sumOf { it.count }

    private class RewardRandom(val rolls: Int, val outcome: Int, val upper: Boolean) : GameRandom {
        var rangeCalls = 0
        var selections = 0
        override fun of(maxExclusive: Int): Int { selections++; return outcome }
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            rangeCalls++
            return if (rangeCalls == 1) rolls else if (upper) maxInclusive else minInclusive
        }
        override fun randomDouble(): Double = error("Unexpected floating random")
    }

    @OptIn(InternalApi::class)
    private class Fixture {
        val player = Player().apply {
            worldType = WorldType.MAIN
            uuid = 391L
            observerUUID = uuid
            slotId = 1
            assignUid()
            coords = CoordGrid(3222, 3218, 0)
            inv = inventory("inv.inv", 28)
            worn = inventory("inv.worn", 14)
        }
        val repo = ObjRepository(MapClock(100), ObjRegistry(ZoneUpdateMap()))
        val pending get() = player.invMap.getOrPut(BEGINNER_PENDING)
        var rollCalls = 0
        init { set("varp.playtime", 60_000) }
        fun set(symbol: String, value: Int) = VarPlayerIntMapSetter.set(player, symbol, value)
        fun put(slot: Int, obj: String, count: Int = 1) { player.inv[slot] = InvObj(obj, count) }
        fun fill() { for (slot in player.inv.indices) put(slot, "obj.bronze_dagger") }
        fun open(source: InvObj = player.inv[0]!!, rewards: List<InvObj> = listOf(InvObj("obj.airrune", 15))) =
            player.openBeginnerCasket(0, source) { rollCalls++; rewards }
        fun deliver() = player.deliverBeginnerRewards { repo.add(Obj.fromOwner(player, player.coords, it), 100) }
        fun ground() = repo.findAll(player.coords).toList()
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
