package org.rsmod.api.account

import com.fasterxml.jackson.databind.ObjectMapper
import dev.openrune.ServerCacheManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.account.autosave.PlayerAutosaveOrchestrator
import org.rsmod.api.account.character.inv.CharacterInventoryApplier
import org.rsmod.api.account.character.inv.CharacterInventoryPipeline
import org.rsmod.api.account.character.inv.CharacterInventorySaveData
import org.rsmod.api.account.character.main.CharacterAccountApplier
import org.rsmod.api.account.character.main.CharacterAccountRepository
import org.rsmod.api.account.saver.AccountSavingService
import org.rsmod.api.account.saver.request.AccountSaveRequest
import org.rsmod.api.account.saver.request.AccountSaveResponse
import org.rsmod.api.attr.AttributeMap
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerPersistenceHints
import org.rsmod.game.inv.InvObj
import org.rsmod.game.world.WorldType

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
@ResourceLock("PlayerPersistenceHints")
class AutosaveSnapshotTest {
    private val listenerField = PlayerPersistenceHints::class.java.getDeclaredField("listener").apply { isAccessible = true }
    private var oldListener: Any? = null
    private var oldAttrSink = AttributeMap.persistenceMutationSink

    @BeforeEach fun retainHooks() {
        oldListener = listenerField.get(PlayerPersistenceHints)
        oldAttrSink = AttributeMap.persistenceMutationSink
    }

    @AfterEach fun restoreHooks() {
        PlayerPersistenceHints.leave()
        listenerField.set(PlayerPersistenceHints, oldListener)
        AttributeMap.persistenceMutationSink = oldAttrSink
    }

    @Test
    fun `persistence hints during a multi slot transaction capture only its completed state at tick end`() {
        val f = Fixture()
        f.player.inv[0] = InvObj("obj.coins", 10)
        f.player.inv.clearModifiedSlots()
        PlayerPersistenceHints.enter(f.player)
        try {
            f.player.inv[0] = null
            PlayerPersistenceHints.notify(f.player)
            assertTrue(f.requests.isEmpty(), "The debit half of a transaction must never be snapshotted")
            assertTrue(f.captureThreads.isEmpty())
            f.player.inv[1] = InvObj("obj.coins", 7)
            PlayerPersistenceHints.notify(f.player)
            f.player.inv[2] = InvObj("obj.airrune", 3)
            PlayerPersistenceHints.notify(f.player)
            assertTrue(f.requests.isEmpty())
        } finally {
            PlayerPersistenceHints.leave()
        }
        // The inventory output processor may clear these before autosave drains its hints.
        f.player.inv.clearModifiedSlots()
        f.endTick()
        assertEquals(1, f.requests.size)
        assertEquals(setOf(1, 2), f.inventory(0).objs.keys)
        assertEquals(7, f.inventory(0).objs.getValue(1).count)
        assertEquals(3, f.inventory(0).objs.getValue(2).count)
        assertEquals(listOf(Thread.currentThread().id), f.captureThreads)
    }

    @Test
    fun `dirty changes while a snapshot is pending survive cleared inventory flags and writer callbacks`() {
        val f = Fixture()
        f.changeCoins(10)
        f.endTick()
        f.changeCoins(6)
        f.player.inv[1] = InvObj("obj.airrune", 4)
        PlayerPersistenceHints.notify(f.player)
        f.player.inv.clearModifiedSlots()
        f.endTick()
        assertEquals(1, f.requests.size)
        assertEquals(10, f.inventory(0).objs.getValue(0).count)

        f.completeOnWriter(0, AccountSaveResponse.Success(f.player))
        assertEquals(1, f.requests.size, "A saver callback must not capture game state on its thread")
        assertEquals(1, f.captureThreads.size)
        f.endTick()
        assertEquals(2, f.requests.size)
        assertEquals(6, f.inventory(1).objs.getValue(0).count)
        assertEquals(4, f.inventory(1).objs.getValue(1).count)
        assertEquals(10, f.inventory(0).objs.getValue(0).count)
        assertTrue(f.captureThreads.all { it == Thread.currentThread().id })

        // A stale duplicate completion must not release the newer in-flight save.
        f.completeOnWriter(0, AccountSaveResponse.Success(f.player))
        f.changeCoins(5)
        f.endTick()
        assertEquals(2, f.requests.size)
        f.completeOnWriter(1, AccountSaveResponse.Success(f.player))
        f.endTick()
        assertEquals(3, f.requests.size)
        assertEquals(5, f.inventory(2).objs.getValue(0).count)
    }

    @Test
    fun `failed autosave retains dirty state and retries the latest completed inventory after backoff`() {
        val f = Fixture()
        f.changeCoins(12)
        f.endTick()
        f.changeCoins(8)
        f.completeOnWriter(0, AccountSaveResponse.ExcessiveRetries(f.player))
        assertEquals(1, f.captureThreads.size)
        f.endTick()
        f.player.currentMapClock += 499
        f.endTick()
        assertEquals(1, f.requests.size)
        f.player.currentMapClock++
        f.endTick()
        assertEquals(2, f.requests.size)
        assertEquals(12, f.inventory(0).objs.getValue(0).count)
        assertEquals(8, f.inventory(1).objs.getValue(0).count)
        f.completeOnWriter(1, AccountSaveResponse.Success(f.player))
        f.endTick()
        assertEquals(2, f.requests.size, "A successful clean retry should not enqueue another save")
    }

    @Test
    fun `a switching player retains a dirty mutation until the game thread resumes processing`() {
        val f = Fixture()
        f.changeCoins(9)
        f.player.worldTypeSwitching = true
        repeat(3) { f.endTick() }
        assertTrue(f.requests.isEmpty())
        f.player.worldTypeSwitching = false
        f.endTick()
        assertEquals(1, f.requests.size)
        assertEquals(9, f.inventory(0).objs.getValue(0).count)
    }

    private class Fixture {
        val requests = mutableListOf<AccountSaveRequest>()
        val captureThreads = mutableListOf<Long>()
        private val repository = spyk(CharacterAccountRepository(ObjectMapper(), CharacterAccountApplier()))
        private val inventories = CharacterInventoryPipeline(CharacterInventoryApplier())
        private val saver = mockk<AccountSavingService>()
        val player = Player().apply {
            accountId = 41
            characterId = 73
            worldType = WorldType.MAIN
            slotId = 1
            currentMapClock = 100
            processedMapClock = 100
            inv = invMap.getOrPut("inv.inv")
        }
        private val autosave: PlayerAutosaveOrchestrator

        init {
            every { repository.capture(any()) } answers {
                captureThreads += Thread.currentThread().id
                callOriginal()
            }
            every { saver.queue(any()) } answers { requests += firstArg<AccountSaveRequest>() }
            autosave = PlayerAutosaveOrchestrator(AccountManager(mockk(), saver, repository, setOf(inventories)))
        }

        fun changeCoins(count: Int) {
            player.inv[0] = InvObj("obj.coins", count)
            PlayerPersistenceHints.notify(player)
            player.inv.clearModifiedSlots()
        }

        fun endTick() = autosave.processEndOfTick(listOf(player))

        fun inventory(index: Int): CharacterInventorySaveData.Inventory =
            (requests[index].snapshot.segments.getValue(inventories.javaClass.name) as CharacterInventorySaveData)
                .inventories.single { it.key == "inv.inv" }

        fun completeOnWriter(index: Int, response: AccountSaveResponse) {
            val writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "autosave-test-writer") }
            try {
                writer.submit { requests[index].callback(response) }.get(5, TimeUnit.SECONDS)
            } finally {
                writer.shutdownNow()
            }
        }
    }

    companion object {
        @JvmStatic @BeforeAll fun cache() { ServerCacheManager.init(241).close() }
    }
}
