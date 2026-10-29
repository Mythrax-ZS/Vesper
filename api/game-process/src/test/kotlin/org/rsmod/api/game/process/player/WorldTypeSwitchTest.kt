package org.rsmod.api.game.process.player

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.account.loader.request.AccountLoadResponse
import org.rsmod.api.account.saver.request.AccountSaveResponse
import org.rsmod.api.player.events.EngineQueueEvents
import org.rsmod.api.player.events.PlayerTimerEvent
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.registry.account.AccountRegistry
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.entity.player.WorldTypeChangedEvent
import org.rsmod.game.entity.util.ShuffledPlayerList
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueType
import org.rsmod.game.world.WorldType

@ResourceLock("ServerCacheManager")
class WorldTypeSwitchTest {
    @Test
    fun `switch holds gameplay until the exact outgoing checkpoint is saved and target applies once`() {
        val f = fixture()
        f.player.inv[0] = InvObj("obj.coins", 17)
        f.start()
        assertTrue(f.player.worldTypeSwitching)
        assertFalse(f.player.canProcess)
        assertTrue(f.player.isAccessProtected)
        assertTrue(f.player.persistenceSuspended)
        assertEquals(WorldType.MAIN, f.saves.single().snapshot.worldType)
        assertTrue(f.loads.isEmpty())
        f.saved()
        assertTrue(f.player.worldTypeSwitching)
        val response = f.response(WorldType.RAGING_ECHOES_LEAGUE) { it.inv[0] = InvObj("obj.coins", 3) }
        repeat(2) { f.loads.single().callback(response) }
        f.service.processPendingSwitches()
        assertEquals(WorldType.RAGING_ECHOES_LEAGUE, f.player.worldType)
        assertEquals(3, f.player.inv.physicalCount("obj.coins"))
        assertEquals(1, f.changed.size)
        assertFalse(f.player.worldTypeSwitching)
        assertFalse(f.player.persistenceSuspended)
        assertTrue(f.player.regionRebuildPending)
    }

    @Test
    fun `worker callbacks neither start loads nor apply target effects before the game-thread drain`() {
        val f = fixture()
        f.start()
        Thread { f.saves.single().callback(AccountSaveResponse.Success(f.player)) }.apply { start(); join() }
        assertTrue(f.loads.isEmpty())
        f.service.processPendingSwitches()
        var appliedThread: Thread? = null
        val response = f.response(WorldType.RAGING_ECHOES_LEAGUE) { appliedThread = Thread.currentThread() }
        Thread { f.loads.single().callback(response) }.apply { start(); join() }
        assertNull(appliedThread)
        assertEquals(WorldType.MAIN, f.player.worldType)
        f.service.processPendingSwitches()
        assertSame(Thread.currentThread(), appliedThread)
    }

    @Test
    fun `outgoing area cleanup is settled and included in the saved checkpoint before target installation`() {
        val f = fixture()
        val area = "area.motherlode_mine".asRSCM()
        f.queueCache.addLabelled(EngineQueueType.AreaExit, area)
        f.events.subscribeSuspend<ProtectedAccess, EngineQueueEvents.Labelled>(EngineQueueEvents.Labelled::class.java,
            EventBus.composeLongKey(area, EngineQueueType.AreaExit.id)) {
            assertEquals(WorldType.MAIN, player.worldType)
            player.inv[0] = InvObj("obj.coins", 22)
        }
        f.player.inv[0] = InvObj("obj.coins", 17)
        f.player.activeAreas.add(area.toShort())
        f.start()
        assertEquals(22, f.player.inv.physicalCount("obj.coins"))
        assertEquals(f.capture(f.player), f.saves.single().snapshot)
        assertTrue(f.player.engineQueueList.isEmpty)
        assertTrue(f.player.activeAreas.isEmpty())
        f.saved()
        f.loaded()
        assertEquals(3, f.player.inv.physicalCount("obj.coins"))
    }

    @Test
    fun `suspending area cleanup aborts preparation without discarding outgoing rewards or coroutine`() {
        val f = fixture()
        val area = "area.motherlode_mine".asRSCM()
        var completed = false
        f.queueCache.addLabelled(EngineQueueType.AreaExit, area)
        f.events.subscribeSuspend<ProtectedAccess, EngineQueueEvents.Labelled>(EngineQueueEvents.Labelled::class.java,
            EventBus.composeLongKey(area, EngineQueueType.AreaExit.id)) {
            player.inv[0] = InvObj("obj.coins", 22)
            delay(2)
            completed = true
        }
        f.player.activeAreas.add(area.toShort())
        f.start()
        assertFalse(f.player.worldTypeSwitching)
        assertTrue(f.saves.isEmpty())
        assertEquals(WorldType.MAIN, f.player.worldType)
        assertEquals(22, f.player.inv.physicalCount("obj.coins"))
        assertFalse(completed)
        assertTrue(f.player.activeCoroutine?.isSuspended == true)
    }

    @Test
    fun `unexpected outgoing inventory mutation aborts without rolling back the change or installing target`() {
        val f = fixture()
        f.player.inv[0] = InvObj("obj.coins", 17)
        f.start()
        f.saved()
        f.player.inv[0] = InvObj("obj.coins", 11)
        f.loaded()
        assertEquals(WorldType.MAIN, f.player.worldType)
        assertEquals(11, f.player.inv.physicalCount("obj.coins"))
        assertTrue(f.changed.isEmpty())
        assertFalse(f.player.worldTypeSwitching)
        assertFalse(f.player.persistenceSuspended)
    }

    @Test
    fun `save failure rejected load and load failure all resume the original save unchanged`() {
        for (failure in 0..2) {
            val f = fixture()
            f.player.inv[0] = InvObj("obj.coins", 17)
            f.start()
            when (failure) {
                0 -> f.saved(response = AccountSaveResponse.ExcessiveRetries(f.player))
                1 -> { f.acceptLoads = false; f.saved() }
                2 -> { f.saved(); f.loads.single().callback(AccountLoadResponse.Err.Timeout); f.service.processPendingSwitches() }
            }
            assertEquals(WorldType.MAIN, f.player.worldType)
            assertEquals(17, f.player.inv.physicalCount("obj.coins"))
            assertFalse(f.player.worldTypeSwitching)
            assertFalse(f.player.persistenceSuspended)
            assertTrue(f.changed.isEmpty())
        }
    }

    @Test
    fun `timeout then another switch ignores old save and load callbacks without releasing the new attempt`() {
        val f = fixture()
        f.start()
        f.saved()
        val oldLoad = f.loads.single()
        f.service.processPendingSwitches(System.nanoTime() + 61_000_000_000L)
        assertFalse(f.player.worldTypeSwitching)
        f.start(WorldType.TWISTED_LEAGUE)
        f.saves.first().callback(AccountSaveResponse.Success(f.player))
        oldLoad.callback(f.response(WorldType.RAGING_ECHOES_LEAGUE))
        f.service.processPendingSwitches()
        assertEquals(1, f.loads.size)
        assertTrue(f.player.worldTypeSwitching)
        assertEquals(WorldType.MAIN, f.player.worldType)
        f.saved()
        f.loaded()
        assertEquals(WorldType.TWISTED_LEAGUE, f.player.worldType)
        assertEquals(1, f.changed.size)
    }

    @Test
    fun `disconnect logout shutdown and replacement session cancel without applying late callbacks`() {
        for (exit in 0..4) {
            val f = fixture()
            f.start()
            f.saved()
            when (exit) {
                0 -> f.player.clientDisconnected.set(true)
                1 -> f.player.pendingLogout = true
                2 -> f.player.pendingShutdown = true
                3 -> f.players[1] = Player()
                4 -> f.player.client = SwitchWorldFixture.SilentClient()
            }
            f.loads.single().callback(f.response(WorldType.RAGING_ECHOES_LEAGUE))
            f.service.processPendingSwitches()
            assertEquals(WorldType.MAIN, f.player.worldType)
            assertFalse(f.player.worldTypeSwitching)
            assertTrue(f.changed.isEmpty())
        }
    }

    @Test
    fun `target transform failure restores outgoing items and disconnects for clean runtime reconstruction`() {
        val f = fixture()
        f.player.inv[0] = InvObj("obj.coins", 17)
        f.start()
        f.saved()
        f.loaded { it.inv[0] = InvObj("obj.coins", 99); error("failed target transform") }
        assertEquals(WorldType.MAIN, f.player.worldType)
        assertEquals(17, f.player.inv.physicalCount("obj.coins"))
        assertFalse(f.player.worldTypeSwitching)
        assertTrue(f.player.forceDisconnect)
        assertTrue(f.changed.isEmpty())
    }

    @Test
    fun `failed checkpoint restore marks live state invalid and disconnects rather than allowing a corrupt save`() {
        val f = fixture()
        f.start()
        f.saved()
        f.restoreFails = true
        f.loaded { error("failed transform") }
        assertTrue(f.player.forceDisconnect)
        assertTrue(f.player.persistenceInvalidated)
        assertTrue(f.player.persistenceSuspended)
        assertFalse(f.player.worldTypeSwitching)
    }

    @Test
    fun `invalid checkpoint teardown bypasses corrupt gameplay hooks and requests exactly one invalid-state logout`() {
        val f = fixture()
        f.player.persistenceInvalidated = true
        f.player.forceDisconnect = true
        f.player.delay(100)
        f.player.queue("queue.hit", 20)
        var gameplayCleanup = false
        f.events.subscribeUnbound(SessionStateEvent.Logout::class.java) { gameplayCleanup = true }
        val registry = mockk<AccountRegistry>(relaxed = true)
        val logout = PlayerLogoutProcess(f.events, ShuffledPlayerList(f.players), registry,
            mockk(relaxed = true), PlayerLogoutProcessor(f.events, registry), mockk())
        repeat(2) { logout.process() }
        assertTrue(f.player.loggingOut)
        assertTrue(f.player.closeClient)
        assertFalse(f.player.canProcess)
        assertFalse(gameplayCleanup)
        assertTrue(f.player.queueList.isEmpty)
        verify(exactly = 1) { registry.queueLogout(f.player) }
    }

    @Test
    fun `postcommit login error never restores outgoing items after a target reward has been emitted`() {
        val f = fixture()
        f.player.inv[0] = InvObj("obj.coins", 17)
        f.events.subscribeUnbound(SessionStateEvent.Login::class.java) { error("login hook failed") }
        f.start()
        f.saved()
        f.loaded()
        assertEquals(WorldType.RAGING_ECHOES_LEAGUE, f.player.worldType)
        assertEquals(3, f.player.inv.physicalCount("obj.coins"))
        assertTrue(f.player.forceDisconnect)
        assertFalse(f.player.persistenceInvalidated)
        assertFalse(f.player.persistenceSuspended)
        assertFalse(f.player.canProcess)
        assertEquals(1, f.changed.size, "Outgoing global activity must detach even if target login fails")
    }

    @Test
    fun `one failed change listener cannot leave a later global owner attached to the outgoing save`() {
        val f = fixture()
        var detached = false
        f.events.subscribeUnbound(WorldTypeChangedEvent::class.java) { error("failed owner cleanup") }
        f.events.subscribeUnbound(WorldTypeChangedEvent::class.java) { detached = true }
        f.start()
        f.saved()
        f.loaded()
        assertTrue(detached)
        assertTrue(f.player.forceDisconnect)
        assertFalse(f.player.canProcess)
        assertEquals(WorldType.RAGING_ECHOES_LEAGUE, f.player.worldType)
        assertEquals(3, f.player.inv.physicalCount("obj.coins"))
    }

    @Test
    fun `required combat queues and delayed actions refuse switch before any snapshot`() {
        for (busy in 0..2) {
            val f = fixture()
            when (busy) {
                0 -> f.player.queue("queue.hit", 2)
                1 -> f.player.delay(2)
                2 -> f.player.preventLogoutUntil = f.player.currentMapClock + 10
            }
            assertTrue(f.service.switch(f.player, WorldType.TWISTED_LEAGUE, WorldType.entries) is WorldTypeSwitchService.Result.Busy)
            assertFalse(f.player.worldTypeSwitching)
            assertTrue(f.saves.isEmpty())
        }
    }

    @Test
    fun `pending player queues timers and protected actions pause and original timers resume after failure`() {
        val f = fixture()
        val clock = MapClock(f.player.currentMapClock)
        var fired = 0
        f.events.subscribeKeyed(PlayerTimerEvent.Soft::class.java, "timer.health_regen".asRSCM().toLong()) { fired++ }
        f.player.softTimer("timer.health_regen", 1)
        val launcher = mockk<ProtectedAccessLauncher>()
        val timers = PlayerTimerProcessor(clock, f.events, launcher)
        val queues = PlayerQueueProcessor(f.events, launcher)
        f.start()
        f.player.softQueue("queue.hit", 1)
        clock.tick()
        timers.process(f.player)
        queues.process(f.player)
        assertEquals(0, fired)
        assertEquals(1, f.player.queueList.size)
        assertFalse(f.player.isValidTarget())
        var ran = false
        assertFalse(ProtectedAccessLauncher.withProtectedAccess(f.player, ProtectedAccessContextFactory.empty()) { ran = true })
        assertFalse(ran)
        f.saved(response = AccountSaveResponse.ExcessiveRetries(f.player))
        timers.process(f.player)
        assertEquals(1, fired)
    }

    @Test
    fun `successful switch discards outgoing timers before target login handlers schedule replacements`() {
        val f = fixture()
        f.player.softTimer("timer.health_regen", 10)
        f.events.subscribeUnbound(SessionStateEvent.Login::class.java) { player.softTimer("timer.stat_regen", 5) }
        f.start()
        f.saved()
        f.loaded()
        val timers = f.player.softTimerMap.map { it.shortKey }.toSet()
        assertEquals(setOf("timer.stat_regen".asRSCM().toShort()), timers)
    }

    private fun fixture(): SwitchWorldFixture = SwitchWorldFixture(Player(client = SwitchWorldFixture.SilentClient()))

    companion object {
        @JvmStatic @BeforeAll fun cache() { ServerCacheManager.init(241).close() }
    }
}
