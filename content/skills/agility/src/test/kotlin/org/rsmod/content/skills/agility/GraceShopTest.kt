package org.rsmod.content.skills.agility

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.market.MarketPrices
import org.rsmod.api.player.events.PlayerQueueEvents
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.shops.currency.InvObjShopCurrency
import org.rsmod.api.shops.operation.StandardCurrencyShopOperations
import org.rsmod.api.shops.restock.ShopRestockProcess
import org.rsmod.content.generic.items.packs.ItemPackScript
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.shop.Shop
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class GraceShopTest {
    @Test
    fun `a complete graceful set costs 260 marks and sells back for 208`() {
        val f = Fixture()
        f.player.inv[0] = InvObj(MARK, 260)
        val pieces = f.shop.inv.objs.filterNotNull().filter { it.id != PACK.asRSCM() }
        for (piece in pieces) f.buy(piece.id)
        assertEquals(0, f.count(MARK))
        for (piece in pieces) {
            val symbol = checkNotNull(ServerCacheManager.getItem(piece.id)).internalName
            assertEquals(1, f.count(symbol))
            assertEquals(99, f.stock(symbol))
            f.sell(piece.id)
            assertEquals(0, f.count(symbol))
            assertEquals(100, f.stock(symbol))
        }
        assertEquals(208, f.count(MARK))
    }

    @Test
    fun `amylase packs buy for ten and sell for eight without changing item tradeability`() {
        val f = Fixture()
        f.player.inv[0] = InvObj(MARK, 50)
        f.buy(PACK.asRSCM(), 5)
        assertEquals(0, f.count(MARK))
        assertEquals(5, f.count(PACK))
        assertEquals(995, f.stock(PACK))
        f.sell(PACK.asRSCM(), 5)
        assertEquals(40, f.count(MARK))
        assertEquals(0, f.count(PACK))
        assertEquals(1000, f.stock(PACK))
        assertFalse(type(PACK).tradeable)
        assertFalse(type(PACK).stockmarket)
    }

    @Test
    fun `a full inventory rolls back stock and marks unless exact payment frees its slot`() {
        val f = Fixture()
        f.fill()
        f.player.inv[0] = InvObj(MARK, 11)
        val before = f.player.inv.objs.toList()
        repeat(2) { f.buy(PACK.asRSCM()) }
        assertEquals(before, f.player.inv.objs.toList())
        assertEquals(1000, f.stock(PACK))
        f.player.inv[0] = InvObj(MARK, 10)
        f.buy(PACK.asRSCM())
        assertEquals(1, f.count(PACK))
        assertEquals(0, f.count(MARK))
        assertEquals(999, f.stock(PACK))
        assertEquals(27, f.count("obj.bronze_dagger"))
    }

    @Test
    fun `insufficient marks and disappearing stock cannot consume currency`() {
        val f = Fixture()
        f.player.inv[0] = InvObj(MARK, 9)
        f.buy(PACK.asRSCM())
        assertEquals(9, f.count(MARK))
        assertEquals(0, f.count(PACK))
        val slot = f.shop.inv.indexOfFirst { it?.id == PACK.asRSCM() }
        f.shop.inv[slot] = InvObj(PACK, 1)
        f.player.inv[0] = InvObj(MARK, 30)
        repeat(3) { f.buy(PACK.asRSCM()) }
        assertEquals(20, f.count(MARK))
        assertEquals(1, f.count(PACK))
        assertEquals(0, f.stock(PACK))
    }

    @Test
    fun `selling into maximum mark and shop stacks is atomic`() {
        val f = Fixture()
        f.player.inv[0] = InvObj(MARK, Int.MAX_VALUE - 7)
        f.player.inv[1] = InvObj(PACK)
        f.sell(PACK.asRSCM())
        assertEquals(Int.MAX_VALUE - 7, f.count(MARK))
        assertEquals(1, f.count(PACK))
        assertEquals(1000, f.stock(PACK))
        f.player.inv[0] = InvObj(MARK, Int.MAX_VALUE - 8)
        val stockSlot = f.shop.inv.indexOfFirst { it?.id == PACK.asRSCM() }
        f.shop.inv[stockSlot] = InvObj(PACK, Int.MAX_VALUE)
        f.sell(PACK.asRSCM())
        assertEquals(Int.MAX_VALUE - 8, f.count(MARK))
        assertEquals(1, f.count(PACK))
        f.shop.inv[stockSlot] = InvObj(PACK, Int.MAX_VALUE - 1)
        f.operations.invSell(f.player, f.player.inv, f.shop, 1, 1)
        f.operations.invSell(f.player, f.player.inv, f.shop, 1, 1)
        assertEquals(Int.MAX_VALUE, f.count(MARK))
        assertEquals(Int.MAX_VALUE, f.stock(PACK))
        assertEquals(0, f.count(PACK))
    }

    @Test
    fun `recolours worn forms and unrelated items cannot be exchanged`() {
        for (obj in listOf("obj.zeah_graceful_hood_arceuus", "obj.graceful_hood_worn", "obj.amylase", "obj.coins")) {
            val f = Fixture()
            f.player.inv[0] = InvObj(obj)
            val before = f.shop.inv.objs.toList()
            f.sell(obj.asRSCM())
            assertEquals(1, f.count(obj))
            assertEquals(0, f.count(MARK))
            assertEquals(before, f.shop.inv.objs.toList())
        }
    }

    @Test
    fun `the default shop operations still reject untradeable amylase packs`() {
        val f = Fixture()
        val generic = StandardCurrencyShopOperations(
            InvObjShopCurrency(MARK, "mark of grace", "marks of grace"), f.restock, NO_PRICES,
        )
        f.player.inv[0] = InvObj(PACK)
        generic.invSell(f.player, f.player.inv, f.shop, 0, 1)
        assertEquals(1, f.count(PACK))
        assertEquals(0, f.count(MARK))
        assertEquals(1000, f.stock(PACK))
    }

    @Test
    fun `an unrelated general store still buys tradeable goods and rejects amylase packs`() {
        val f = Fixture()
        val store = Shop(Inventory.create("inv.generalshop1"), "currency.standard_gp", 40.0, 130.0, 3.0)
        val generic = StandardCurrencyShopOperations(
            InvObjShopCurrency("obj.coins", "coin"), f.restock, NO_PRICES,
        )
        f.player.inv[0] = InvObj("obj.bronze_dagger")
        generic.invSell(f.player, f.player.inv, store, 0, 1)
        assertEquals(0, f.count("obj.bronze_dagger"))
        assertEquals(1, store.inv.physicalCount("obj.bronze_dagger"))
        assertEquals(4, f.count("obj.coins"))
        f.player.inv[1] = InvObj(PACK)
        generic.invSell(f.player, f.player.inv, store, 1, 1)
        assertEquals(1, f.count(PACK))
        assertEquals(4, f.count("obj.coins"))
        assertEquals(0, store.inv.physicalCount(PACK))
    }

    @Test
    fun `restocking changes quantities without changing the fixed price`() {
        val f = Fixture()
        f.player.inv[0] = InvObj(MARK, 30)
        f.buy(PACK.asRSCM(), 2)
        assertEquals(10, f.count(MARK))
        assertEquals(998, f.stock(PACK))
        f.restock.process()
        assertEquals(999, f.stock(PACK))
        f.restock.process()
        assertEquals(1000, f.stock(PACK))
        f.buy(PACK.asRSCM())
        assertEquals(0, f.count(MARK))
        assertEquals(3, f.count(PACK))
    }

    @Test
    fun `native pack opening converts one pack into exactly one hundred crystals in a full inventory`() {
        val f = Fixture()
        f.fill()
        f.player.inv[0] = InvObj(PACK)
        repeat(2) { f.openPack(0) }
        assertEquals(0, f.count(PACK))
        assertEquals(100, f.count(CRYSTAL))
        assertEquals(27, f.count("obj.bronze_dagger"))
    }

    @Test
    fun `pack output overflow preserves the pack and exact maximum opens once`() {
        val f = Fixture()
        f.player.inv[0] = InvObj(PACK)
        f.player.inv[1] = InvObj(CRYSTAL, Int.MAX_VALUE - 99)
        f.openPack(0)
        assertEquals(1, f.count(PACK))
        assertEquals(Int.MAX_VALUE - 99, f.count(CRYSTAL))
        f.player.inv[1] = InvObj(CRYSTAL, Int.MAX_VALUE - 100)
        repeat(2) { f.openPack(0) }
        assertEquals(0, f.count(PACK))
        assertEquals(Int.MAX_VALUE, f.count(CRYSTAL))
    }

    @Test
    fun `queued pack opening rechecks disappeared inputs and changed output capacity`() {
        for (removeSource in listOf(false, true)) {
            val f = Fixture()
            f.player.inv[0] = InvObj(PACK)
            f.player.inv[1] = InvObj(PACK)
            f.openPack(0)
            assertEquals(100, f.count(CRYSTAL))
            if (removeSource) f.player.inv[1] = null
            else f.player.inv[0] = InvObj(CRYSTAL, Int.MAX_VALUE - 99)
            f.runQueuedOpen()
            assertEquals(if (removeSource) 0 else 1, f.count(PACK))
            assertEquals(if (removeSource) 100 else Int.MAX_VALUE - 99, f.count(CRYSTAL))
        }
    }

    private class Fixture {
        val restock = ShopRestockProcess(MapClock(100))
        val operations = GraceShopOperations(restock, NO_PRICES)
        val shop = Shop(Inventory.create(GraceShopOperations.INVENTORY),
            GraceShopOperations.CURRENCY, 80.0, 100.0, 0.0)
        val player = Player().apply {
            client = SilentClient()
            inv = Inventory.create("inv.inv")
            worn = Inventory.create("inv.worn")
            currentMapClock = 100
            processedMapClock = 100
        }
        private val events = EventBus()

        init {
            with(ItemPackScript()) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        fun buy(obj: Int, count: Int = 1) {
            val slot = shop.inv.indexOfFirst { it?.id == obj }
            check(slot >= 0)
            operations.shopBuy(player, player.inv, shop, slot, count)
        }

        fun sell(obj: Int, count: Int = 1) {
            val slot = player.inv.indexOfFirst { it?.id == obj }
            check(slot >= 0)
            operations.invSell(player, player.inv, shop, slot, count)
        }

        fun openPack(slot: Int) = publish(HeldObjEvents.Op1(slot, InvObj(PACK), type(PACK), player.inv))

        fun runQueuedOpen() {
            val iterator = checkNotNull(player.weakQueueList.iterator())
            assertTrue(iterator.hasNext())
            val queue = iterator.next()
            iterator.remove()
            iterator.cleanUp()
            publish(PlayerQueueEvents.Protected(queue.args, queue.id))
        }

        private fun publish(event: SuspendEvent<ProtectedAccess>) {
            var completed: Result<Boolean>? = null
            val access = ProtectedAccess(player, GameCoroutine(),
                ProtectedAccessContextFactory.empty().copy(getEventBus = { events }))
            val block: suspend () -> Boolean = { events.publish(access, event) }
            block.startCoroutine(object : Continuation<Boolean> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Boolean>) { completed = result }
            })
            assertTrue(checkNotNull(completed).getOrThrow())
        }

        fun count(obj: String) = player.inv.physicalCount(obj)
        fun stock(obj: String) = shop.inv.physicalCount(obj)
        fun fill() { for (slot in player.inv.indices) player.inv[slot] = InvObj("obj.bronze_dagger") }
    }

    private class SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private const val MARK = "obj.grace"
        private const val PACK = "obj.pack_amylase"
        private const val CRYSTAL = "obj.amylase"
        private val NO_PRICES = object : MarketPrices {
            override fun get(type: ItemServerType): Int? = null
        }
        private val RESTORATIONS = mutableListOf<() -> Unit>()

        private fun type(obj: String) = checkNotNull(ServerCacheManager.getItem(obj.asRSCM()))

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            ServerCacheManager.init(241).close()
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val previous = field.get(null)
                RESTORATIONS += { field.set(null, previous) }
            }
            val previousStorage = InvVirtualStorageHolder.instance
            RESTORATIONS += { InvVirtualStorageHolder.instance = previousStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic
        @AfterAll
        fun restoreGlobals() {
            RESTORATIONS.asReversed().forEach { it() }
            RESTORATIONS.clear()
        }
    }
}
