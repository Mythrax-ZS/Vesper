package org.rsmod.content.other.grandexchange

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class GrandExchangeTransactionsTest {
    @Test
    fun `buy takes coins up front and fills at the guide price with a refund`() {
        val f = Fixture()
        f.player.inv[0] = stack(COINS, 10_000)
        assertEquals(GeResult.Ok, f.ge.buy(f.player, 0, LOGS, 10, 150))
        assertEquals(10_000 - 1_500, f.count(COINS))
        assertEquals(GeStatus.Complete, f.offer(0).status)
        assertEquals(GeResult.Ok, f.ge.collect(f.player, 0, asNote = false))
        assertEquals(10, f.count(LOGS))
        assertEquals(10_000 - 1_000, f.count(COINS))
        assertTrue(f.offer(0).isEmpty)
    }

    @Test
    fun `buy without enough coins changes nothing`() {
        val f = Fixture()
        f.player.inv[0] = stack(COINS, 999)
        assertEquals(GeResult.MissingCoins, f.ge.buy(f.player, 0, LOGS, 10, 100))
        assertEquals(999, f.count(COINS))
        assertTrue(f.offer(0).isEmpty)
    }

    @Test
    fun `sell removes plain and noted items then pays the guide price minus tax`() {
        val f = Fixture()
        f.player.inv[0] = stack(LOGS, 1)
        f.player.inv[1] = stack(LOGS_NOTED, 4)
        assertEquals(GeResult.Ok, f.ge.sell(f.player, 0, LOGS, 5, 100))
        assertEquals(0, f.count(LOGS) + f.count(LOGS_NOTED))
        assertEquals(GeResult.Ok, f.ge.collect(f.player, 0))
        assertEquals(5 * 100 - 5 * 2, f.count(COINS))
    }

    @Test
    fun `sell with too few items takes nothing`() {
        val f = Fixture()
        f.player.inv[0] = stack(LOGS_NOTED, 4)
        assertEquals(GeResult.MissingItems, f.ge.sell(f.player, 0, LOGS, 5, 100))
        assertEquals(4, f.count(LOGS_NOTED))
        assertTrue(f.offer(0).isEmpty)
    }

    @Test
    fun `pending buy abort returns the exact escrow and a pending sell returns the items`() {
        val f = Fixture()
        f.player.inv[0] = stack(COINS, 900)
        f.player.inv[1] = stack(LOGS, 1)
        assertEquals(GeResult.Ok, f.ge.buy(f.player, 0, LOGS, 10, 90))
        assertEquals(GeResult.Ok, f.ge.sell(f.player, 1, LOGS, 1, 101))
        assertEquals(0, f.count(COINS) + f.count(LOGS))
        assertEquals(GeResult.Ok, f.ge.abort(f.player, 0))
        assertEquals(GeResult.Ok, f.ge.abort(f.player, 1))
        assertEquals(GeResult.NothingToAbort, f.ge.abort(f.player, 1))
        f.ge.collect(f.player, 0)
        f.ge.collect(f.player, 1, asNote = false)
        assertEquals(900, f.count(COINS))
        assertEquals(1, f.count(LOGS))
        assertTrue(f.offers().all { it.isEmpty })
    }

    @Test
    fun `collection with a full inventory keeps the claim and retries without loss`() {
        val f = Fixture()
        f.player.inv[0] = stack(COINS, 500)
        assertEquals(GeResult.Ok, f.ge.buy(f.player, 0, LOGS, 5, 100))
        f.fill(except = 0)
        assertEquals(GeResult.Ok, f.ge.collect(f.player, 0, asNote = false))
        assertEquals(1, f.count(LOGS))
        assertEquals(4, f.offer(0).claimItems)
        for (slot in 1..4) f.player.inv[slot] = null
        assertEquals(GeResult.Ok, f.ge.collect(f.player, 0, asNote = false))
        assertEquals(5, f.count(LOGS))
        assertEquals(GeResult.NothingToCollect, f.ge.collect(f.player, 0))
    }

    @Test
    fun `full inventory with no free slot collects nothing and loses nothing`() {
        val f = Fixture()
        f.player.inv[0] = stack(COINS, 100)
        assertEquals(GeResult.Ok, f.ge.buy(f.player, 0, LOGS, 1, 100))
        f.fill(except = -1)
        assertEquals(GeResult.NoSpace, f.ge.collect(f.player, 0, asNote = false))
        assertEquals(1, f.offer(0).claimItems)
        assertEquals(GeStatus.Complete, f.offer(0).status)
    }

    @Test
    fun `coin stack at the maximum accepts only what fits and keeps the rest claimable`() {
        val f = Fixture()
        f.player.inv[0] = stack(LOGS_NOTED, 1_000)
        assertEquals(GeResult.Ok, f.ge.sell(f.player, 0, LOGS, 1_000, 100))
        val owed = f.offer(0).claimCoins
        f.player.inv[0] = stack(COINS, Int.MAX_VALUE - 500)
        assertEquals(GeResult.Ok, f.ge.collect(f.player, 0))
        assertEquals(Int.MAX_VALUE, f.count(COINS))
        assertEquals(owed - 500, f.offer(0).claimCoins)
    }

    @Test
    fun `busy slot invalid slot untradeable item and invalid totals are rejected`() {
        val f = Fixture()
        f.player.inv[0] = stack(COINS, 1_000)
        assertEquals(GeResult.Ok, f.ge.buy(f.player, 0, LOGS, 1, 10))
        assertEquals(GeResult.SlotBusy, f.ge.buy(f.player, 0, LOGS, 1, 10))
        assertEquals(GeResult.InvalidSlot, f.ge.buy(f.player, 8, LOGS, 1, 10))
        assertEquals(GeResult.NotTradeable, f.ge.buy(f.player, 1, COINS, 1, 10))
        assertEquals(GeResult.InvalidOffer, f.ge.buy(f.player, 1, LOGS, 0, 10))
        assertEquals(GeResult.InvalidOffer, f.ge.buy(f.player, 1, LOGS, 65_536, 32_768))
        assertEquals(990, f.count(COINS))
    }

    @Test
    fun `offers survive in player vars and refresh settles a price change`() {
        val f = Fixture()
        f.player.inv[0] = stack(COINS, 1_000)
        assertEquals(GeResult.Ok, f.ge.buy(f.player, 3, LOGS, 10, 50))
        assertEquals(GeStatus.Active, f.offer(3).status)
        f.prices.map[LOGS] = 40
        f.ge.refresh(f.player)
        assertEquals(GeStatus.Complete, f.offer(3).status)
        assertEquals(10, f.offer(3).claimItems)
        assertEquals(100, f.offer(3).claimCoins)
    }

    private class FakePrices : GuidePrices {
        val map = mutableMapOf(LOGS to 100)

        override fun price(item: Int) = map[item] ?: 0

        override fun buyLimit(item: Int) = 0

        override fun taxExempt(item: Int) = false
    }

    @OptIn(InternalApi::class)
    private class Fixture {
        val prices = FakePrices()
        val ge = GrandExchange(prices)
        val player =
            Player().apply {
                client = RecordingClient()
                uuid = 77L
                slotId = 1
                assignUid()
                coords = CoordGrid(3164, 3487, 0)
                currentMapClock = 100
                processedMapClock = 100
                inv = Inventory(checkNotNull(ServerCacheManager.getInventory("inv.inv".asRSCM())), arrayOfNulls(28))
            }

        fun count(obj: Int): Int = player.inv.objs.filterNotNull().filter { it.id == obj }.sumOf { it.count }

        fun offer(slot: Int) = GeSlots.read(player, slot)

        fun offers() = ge.offers(player)

        fun fill(except: Int) {
            for (slot in 0 until 28) {
                if (slot != except) player.inv[slot] = InvObj("obj.bronze_dagger", 1)
            }
        }
    }

    private class RecordingClient : Client<Any, Any> {
        override fun write(message: Any) {}

        override fun close() {}

        override fun read(player: Player) {}

        override fun flush() {}

        override fun flushHighPriority() {}

        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        fun stack(obj: Int, count: Int) = InvObj(RSCM.getReverseMapping(RSCMType.OBJ, obj), count)

        val COINS: Int by lazy { "obj.coins".asRSCM(RSCMType.OBJ) }
        val LOGS: Int by lazy { "obj.logs".asRSCM(RSCMType.OBJ) }
        val LOGS_NOTED: Int by lazy { ServerCacheManager.getItem(LOGS)!!.certlink }
        private val restored = mutableListOf<() -> Unit>()

        @JvmStatic
        @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
            for ((owner, name) in
                listOf(
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

        @JvmStatic
        @AfterAll
        fun restore() {
            restored.asReversed().forEach { it() }
            restored.clear()
        }
    }
}
