package org.rsmod.api.obj.plugin

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
import org.rsmod.annotations.InternalApi
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.player.events.interact.ObjDefaultEvents
import org.rsmod.api.player.hook.PlayerObjTakeValidateHook
import org.rsmod.api.player.hook.PlayerObjTakeValidator
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
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
class ObjTakePluginTest {
    @Test
    fun `foreign mode public and private stacks cannot be picked up even with matching character UUID`() {
        for (public in listOf(false, true)) {
            val f = Fixture()
            val obj = Obj.fromOwner(f.player, f.player.coords, "obj.coins", 12)
            assertTrue(f.repo.add(obj, 100))
            if (public) obj.reveal()
            f.player.worldType = WorldType.RAGING_ECHOES_LEAGUE
            repeat(2) { f.pick(obj) }
            assertTrue(f.exists(obj))
            assertEquals(12, obj.count)
            assertEquals(0, f.player.inv.physicalCount("obj.coins"))
            assertEquals(0, f.notifications)
            f.player.worldType = WorldType.MAIN
            f.pick(obj)
            assertFalse(f.exists(obj))
            assertEquals(12, f.player.inv.physicalCount("obj.coins"))
            assertEquals(1, f.notifications)
        }
    }

    @Test
    fun `mode replacement during delayed pickup preserves the old stack for the original save`() {
        val f = Fixture()
        val obj = f.spawn(far = true, count = 9)
        f.pick(obj)
        f.player.worldType = WorldType.RAGING_ECHOES_LEAGUE
        f.advance()
        assertTrue(f.exists(obj))
        assertEquals(9, obj.count)
        assertEquals(0, f.player.inv.physicalCount("obj.coins"))
        assertEquals(0, f.notifications)
        f.player.worldType = WorldType.MAIN
        f.player.coords = obj.coords
        f.pick(obj)
        assertFalse(f.exists(obj))
        assertEquals(9, f.player.inv.physicalCount("obj.coins"))
        assertEquals(1, f.notifications)
    }

    @Test
    fun `shared server ground item remains collectible after a mode switch`() {
        val f = Fixture()
        val obj = Obj.fromServer(MapClock(100), f.player.coords, "obj.coins", 4)
        assertTrue(f.repo.add(obj, 100))
        f.player.worldType = WorldType.RAGING_ECHOES_LEAGUE
        f.pick(obj)
        assertFalse(f.exists(obj))
        assertEquals(4, f.player.inv.physicalCount("obj.coins"))
        assertEquals(1, f.notifications)
    }

    @Test
    fun `successful pickup transfers exactly once and callback follows both commits`() {
        val f = Fixture()
        val obj = f.spawn(count = 12)
        f.pick(obj)
        assertEquals(12, f.player.inv.physicalCount("obj.coins"))
        assertFalse(f.exists(obj))
        assertEquals(1, f.notifications)
        f.pick(obj)
        assertEquals(12, f.player.inv.physicalCount("obj.coins"))
        assertEquals(1, f.notifications)
    }

    @Test
    fun `full inventory and stack overflow preserve ground count and skip callback`() {
        val full = Fixture()
        full.fill()
        val obj = full.spawn()
        full.pick(obj)
        assertTrue(full.exists(obj))
        assertEquals(0, full.notifications)
        assertEquals(28, full.player.inv.physicalCount("obj.bronze_dagger"))
        val overflow = Fixture()
        overflow.player.inv[0] = InvObj("obj.coins", Int.MAX_VALUE)
        val stack = overflow.spawn()
        overflow.pick(stack)
        assertTrue(overflow.exists(stack))
        assertEquals(Int.MAX_VALUE, overflow.player.inv.physicalCount("obj.coins"))
        assertEquals(0, overflow.notifications)
    }

    @Test
    fun `inventory becoming full during pickup animation leaves the ground item intact`() {
        val f = Fixture()
        val obj = f.spawn(far = true)
        f.pick(obj)
        assertTrue(f.exists(obj))
        f.fill()
        f.advance()
        assertTrue(f.exists(obj))
        assertEquals(0, f.notifications)
        assertEquals(0, f.player.inv.physicalCount("obj.coins"))
    }

    @Test
    fun `validator is rechecked after delay and rejection keeps both stores unchanged`() {
        val f = Fixture()
        val obj = f.spawn(far = true)
        f.pick(obj)
        f.denied = true
        f.advance()
        assertTrue(f.exists(obj))
        assertEquals(0, f.notifications)
        assertEquals(0, f.player.inv.physicalCount("obj.coins"))
    }

    @Test
    fun `vanished ground object cannot be replaced by another object of the same type`() {
        val f = Fixture()
        val obj = f.spawn(far = true)
        f.pick(obj)
        assertTrue(f.repo.del(obj))
        val replacement = f.spawn(far = true, count = 7)
        f.advance()
        assertTrue(f.exists(replacement))
        assertEquals(7, replacement.count)
        assertEquals(0, f.player.inv.physicalCount("obj.coins"))
        assertEquals(0, f.notifications)
    }

    @Test
    fun `changed ground count is validated against current stack capacity after delay`() {
        val f = Fixture()
        f.player.inv[0] = InvObj("obj.coins", Int.MAX_VALUE - 1)
        val obj = f.spawn(far = true)
        f.pick(obj)
        obj.change(2)
        f.advance()
        assertTrue(f.exists(obj))
        assertEquals(2, obj.count)
        assertEquals(Int.MAX_VALUE - 1, f.player.inv.physicalCount("obj.coins"))
        assertEquals(0, f.notifications)
    }

    @Test
    fun `movement expiry or private ownership change prevents delayed pickup`() {
        for (change in 0..2) {
            val f = Fixture()
            val obj = f.spawn(far = true)
            f.pick(obj)
            when (change) {
                0 -> f.player.coords = f.player.coords.translate(xOffset = 5, zOffset = 0)
                1 -> obj.setTimers(obj.timerCycle, obj.revealCycle, f.player.currentMapClock)
                2 -> f.player.uuid = 9999L
            }
            f.advance()
            assertTrue(f.exists(obj))
            assertEquals(0, f.notifications)
            assertEquals(0, f.player.inv.physicalCount("obj.coins"))
        }
    }

    @Test
    fun `postcommit callback failure cannot replay items and does not skip other callbacks`() {
        val f = Fixture(throwOnTake = true)
        val obj = f.spawn()
        assertThrows(IllegalStateException::class.java) { f.pick(obj) }
        assertFalse(f.exists(obj))
        assertEquals(1, f.player.inv.physicalCount("obj.coins"))
        assertEquals(1, f.notifications)
        f.pick(obj)
        assertEquals(1, f.notifications)
        assertEquals(1, f.player.inv.physicalCount("obj.coins"))
    }

    @OptIn(InternalApi::class)
    private class Fixture(throwOnTake: Boolean = false) {
        val player = Player().apply {
            client = SilentClient()
            uuid = 912L
            observerUUID = uuid
            worldType = WorldType.MAIN
            slotId = 1
            assignUid()
            coords = CoordGrid(3222, 3218, 0)
            currentMapClock = 100
            processedMapClock = 100
            inv = Inventory(checkNotNull(ServerCacheManager.getInventory("inv.inv".asRSCM())), arrayOfNulls(28))
            worn = Inventory(checkNotNull(ServerCacheManager.getInventory("inv.worn".asRSCM())), arrayOfNulls(14))
        }
        val repo = ObjRepository(MapClock(100), ObjRegistry(ZoneUpdateMap()))
        private val events = EventBus()
        private val coroutine = GameCoroutine("pickup-test")
        private var completion: Result<Unit>? = null
        var denied = false
        var notifications = 0
        init {
            val failing = object : PlayerObjTakeValidateHook {
                override fun validateTake(player: Player, obj: Obj, objType: ItemServerType): String? = null
                override fun onTake(player: Player, obj: Obj, objType: ItemServerType) {
                    if (throwOnTake) error("broken test callback")
                }
            }
            val recording = object : PlayerObjTakeValidateHook {
                override fun validateTake(player: Player, obj: Obj, objType: ItemServerType): String? =
                    if (denied) "No longer eligible" else null
                override fun onTake(player: Player, obj: Obj, objType: ItemServerType) {
                    assertFalse(exists(obj))
                    assertEquals(obj.count, player.inv.physicalCount("obj.coins"))
                    notifications++
                }
            }
            with(ObjTakePlugin(repo, PlayerObjTakeValidator(linkedSetOf(failing, recording)))) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        fun spawn(far: Boolean = false, count: Int = 1): Obj {
            val coords = player.coords.translate(xOffset = if (far) 1 else 0, zOffset = 0)
            val obj = Obj.fromPersonal(player, coords, "obj.coins", count)
            check(repo.add(obj, 100))
            return obj
        }
        fun exists(obj: Obj) = repo.findAll(obj.coords).any { it === obj }
        fun fill() { for (slot in player.inv.indices) player.inv[slot] = InvObj("obj.bronze_dagger") }
        fun pick(obj: Obj) {
            completion = null
            player.activeCoroutine = coroutine
            val access = ProtectedAccess(player, coroutine,
                ProtectedAccessContextFactory.empty().copy(getEventBus = { events }))
            val body: suspend () -> Unit = { events.publish(access, ObjDefaultEvents.Op3(obj)) }
            body.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) { completion = result }
            })
            completion?.getOrThrow()
        }
        fun advance() {
            player.currentMapClock++
            player.processedMapClock = player.currentMapClock
            coroutine.advance()
            completion?.getOrThrow()
        }
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
                val previous = field.get(null)
                RESTORATIONS += { field.set(null, previous) }
            }
            val previousStorage = InvVirtualStorageHolder.instance
            RESTORATIONS += { InvVirtualStorageHolder.instance = previousStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic @AfterAll
        fun restore() { RESTORATIONS.asReversed().forEach { it() }; RESTORATIONS.clear() }
    }
}
