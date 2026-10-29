package org.rsmod.content.skills.agility

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
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
import org.rsmod.api.obj.plugin.ObjTakePlugin
import org.rsmod.api.player.events.interact.ObjDefaultEvents
import org.rsmod.api.player.hook.PlayerObjTakeValidator
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.table.agility.AgilityCoursesRow
import org.rsmod.api.table.agility.AgilityMarkSpawnsRow
import org.rsmod.content.interfaces.collectionlog.collectionTransmit
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
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class AgilityMarksTest {
    @Test
    fun `successful completion rolls two of six outcomes and unboosted penalty gives two of thirty`() {
        for ((base, outcomes) in listOf(20 to 6, 21 to 30)) {
            var successes = 0
            repeat(outcomes) { roll ->
                val f = Fixture(roll)
                f.player.statMap.setBaseLevel("stat.agility", base.toByte())
                f.player.statMap.setCurrentLevel("stat.agility", if (base == 20) 99 else 1)
                if (f.marks.completeLap(f.player, course(), minute = 100)) successes++
                assertEquals(outcomes, f.random.bounds.first())
            }
            assertEquals(2, successes)
        }
    }

    @Test
    fun `successful roll records collection log at spawn and cannot repeat before cooldown minute`() {
        val f = Fixture()
        assertTrue(f.marks.completeLap(f.player, course(), minute = 100))
        assertEquals(103, f.player.agilityMarkNextMinute)
        assertEquals(0, f.inventoryCount())
        assertEquals(1, f.player.collectionTransmit.physicalCount(AgilityMarks.MARK))
        repeat(2) { assertFalse(f.marks.completeLap(f.player, course(), minute = 102)) }
        assertEquals(1, f.groundCount())
        assertTrue(f.marks.completeLap(f.player, course(), minute = 103))
        assertEquals(2, f.groundCount())
        assertEquals(2, f.player.collectionTransmit.physicalCount(AgilityMarks.MARK))
        f.pick(f.stack())
        assertEquals(2, f.inventoryCount())
        assertEquals(2, f.player.collectionTransmit.physicalCount(AgilityMarks.MARK))
    }

    @Test
    fun `failed roll leaves cooldown eligible for a second lap in the same minute`() {
        val f = Fixture(2)
        assertFalse(f.marks.completeLap(f.player, course(), minute = 100))
        assertEquals(0, f.player.agilityMarkNextMinute)
        assertTrue(f.marks.completeLap(f.player, course(), minute = 100))
        assertEquals(1, f.groundCount())
    }

    @Test
    fun `stacking resets ten minute expiry and logout pauses only the ground lifetime`() {
        val f = Fixture()
        assertTrue(f.marks.completeLap(f.player, course(), minute = 100))
        f.elapse(999)
        assertEquals(1, f.remaining())
        assertTrue(f.marks.spawn(f.player, f.location))
        assertEquals(2, f.groundCount())
        assertEquals(1000, f.remaining())
        f.elapse(100)
        f.marks.logout(f.player)
        assertEquals(0, f.groundCount())
        assertEquals(2, f.storedCount())
        assertEquals(900, f.remaining())
        assertEquals(103, f.player.agilityMarkNextMinute)
        f.elapse(100000, online = false)
        f.marks.login(f.player)
        assertEquals(2, f.groundCount())
        assertEquals(900, f.remaining())
        assertEquals(103, f.player.agilityMarkNextMinute)
        f.elapse(899)
        assertEquals(2, f.groundCount())
        f.elapse(1)
        assertEquals(0, f.groundCount())
        assertEquals(0, f.storedCount())
        assertEquals(0, f.remaining())
    }

    @Test
    fun `repeated restore does not duplicate private ground rewards`() {
        val f = Fixture()
        f.marks.spawn(f.player, f.location)
        f.marks.logout(f.player)
        repeat(3) { f.marks.login(f.player) }
        assertEquals(1, f.groundCount())
        assertEquals(1, f.storedCount())
        f.pick(f.stack())
        f.marks.logout(f.player)
        f.marks.login(f.player)
        assertEquals(1, f.inventoryCount())
        assertEquals(0, f.groundCount())
        assertEquals(0, f.storedCount())
    }

    @Test
    fun `same character restoring and logging out another mode preserves each modes marks`() {
        val main = Fixture()
        repeat(2) { assertTrue(main.marks.spawn(main.player, main.location)) }
        val original = main.stack()
        val league = Fixture()
        league.player.worldType = WorldType.RAGING_ECHOES_LEAGUE
        assertEquals(main.player.uuid, league.player.uuid)
        VarPlayerIntMapSetter.set(league.player, league.location.countType, 3)
        VarPlayerIntMapSetter.set(league.player, league.location.lifetimeType, 700)

        repeat(3) { main.marks.login(league.player) }
        val leagueStack = checkNotNull(main.marks.ownStack(league.player, league.location))
        assertSame(original, main.stack())
        assertEquals(2, original.count)
        assertEquals(3, leagueStack.count)
        assertEquals(700, league.remaining())
        assertEquals(5, main.objects.findAll(main.location.coord).sumOf { it.count })

        main.marks.updateLifetimes(league.player)
        assertEquals(3, league.storedCount())
        main.marks.logout(league.player)
        assertSame(original, main.stack())
        assertEquals(2, main.objects.findAll(main.location.coord).sumOf { it.count })
        main.marks.login(league.player)
        assertEquals(3, checkNotNull(main.marks.ownStack(league.player, league.location)).count)
        assertEquals(5, main.objects.findAll(main.location.coord).sumOf { it.count })
    }

    @Test
    fun `full inventory preserves ground stack and persistent claim until space is available`() {
        val f = Fixture()
        f.fill()
        f.marks.spawn(f.player, f.location)
        val obj = f.stack()
        val before = f.player.inv.objs.toList()
        repeat(2) { f.pick(obj) }
        assertEquals(before, f.player.inv.objs.toList())
        assertEquals(1, f.groundCount())
        assertEquals(1, f.storedCount())
        f.player.inv[0] = null
        f.pick(obj)
        assertEquals(1, f.inventoryCount())
        assertEquals(0, f.groundCount())
        assertEquals(0, f.storedCount())
        assertEquals(27, f.player.inv.physicalCount("obj.bronze_dagger"))
    }

    @Test
    fun `inventory overflow rolls back the claim and exact maximum succeeds once`() {
        val f = Fixture()
        repeat(2) { f.marks.spawn(f.player, f.location) }
        val obj = f.stack()
        f.player.inv[0] = InvObj(AgilityMarks.MARK, Int.MAX_VALUE - 1)
        f.pick(obj)
        assertEquals(Int.MAX_VALUE - 1, f.inventoryCount())
        assertEquals(2, f.groundCount())
        assertEquals(2, f.storedCount())
        f.player.inv[0] = InvObj(AgilityMarks.MARK, Int.MAX_VALUE - 2)
        repeat(3) { f.pick(obj) }
        assertEquals(Int.MAX_VALUE, f.inventoryCount())
        assertEquals(0, f.groundCount())
        assertEquals(0, f.storedCount())
    }

    @Test
    fun `space disappearing during delayed pickup preserves every mark`() {
        val f = Fixture()
        for (slot in 0 until 27) f.player.inv[slot] = InvObj("obj.bronze_dagger")
        f.marks.spawn(f.player, f.location)
        val obj = f.stack()
        f.player.coords = obj.coords.translate(1, 0)
        f.startPickup(obj)
        assertNull(f.result)
        f.player.inv[27] = InvObj("obj.bronze_dagger")
        f.advancePickup()
        assertEquals(0, f.inventoryCount())
        assertEquals(1, f.groundCount())
        assertEquals(1, f.storedCount())
        assertEquals(28, f.player.inv.physicalCount("obj.bronze_dagger"))
    }

    @Test
    fun `ground disappearing during delayed pickup cannot grant or resurrect a mark`() {
        val f = Fixture()
        f.marks.spawn(f.player, f.location)
        val obj = f.stack()
        f.player.coords = obj.coords.translate(1, 0)
        f.startPickup(obj)
        assertTrue(f.objects.del(obj))
        f.advancePickup()
        assertEquals(0, f.inventoryCount())
        f.marks.logout(f.player)
        f.marks.login(f.player)
        assertEquals(0, f.groundCount())
        assertEquals(0, f.storedCount())
    }

    @Test
    fun `mark expiry during delayed pickup cannot grant an expired stack`() {
        val f = Fixture()
        f.marks.spawn(f.player, f.location)
        f.elapse(999)
        val obj = f.stack()
        f.player.coords = obj.coords.translate(1, 0)
        f.startPickup(obj)
        f.advancePickup()
        assertEquals(0, f.inventoryCount())
        assertEquals(0, f.groundCount())
        assertEquals(0, f.storedCount())
    }

    @Test
    fun `ground stack overflow cannot wrap counts or replace the existing stack`() {
        val f = Fixture()
        VarPlayerIntMapSetter.set(f.player, f.location.countType, Int.MAX_VALUE)
        VarPlayerIntMapSetter.set(f.player, f.location.lifetimeType, 1000)
        f.marks.login(f.player)
        val obj = f.stack()
        assertFalse(f.marks.spawn(f.player, f.location))
        assertSame(obj, f.stack())
        assertEquals(Int.MAX_VALUE, f.storedCount())
        assertEquals(Int.MAX_VALUE, f.groundCount())
    }

    @OptIn(InternalApi::class)
    private class Fixture(firstRoll: Int = 0) {
        val location = AgilityMarkSpawnsRow.getRow("dbrow.agility_mark_draynor_start")
        val clock = MapClock(100)
        private val registry = ObjRegistry(ZoneUpdateMap())
        val objects = ObjRepository(clock, registry)
        val random = FixedRandom(firstRoll)
        val marks = AgilityMarks(objects, random, clock)
        private val events = EventBus()
        val player = Player().apply {
            worldType = WorldType.MAIN
            client = SilentClient()
            uuid = 419L
            observerUUID = 9000L
            slotId = 1
            assignUid()
            coords = location.coord
            currentMapClock = clock.cycle
            processedMapClock = clock.cycle
            inv = inventory("inv.inv", 28)
            worn = inventory("inv.worn", 14)
            statMap.setBaseLevel("stat.agility", 1)
            statMap.setCurrentLevel("stat.agility", 1)
        }
        private var coroutine = GameCoroutine()
        var result: Result<Boolean>? = null

        init {
            with(ObjTakePlugin(objects, PlayerObjTakeValidator(setOf(AgilityMarkTakeHook(marks))))) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        fun elapse(ticks: Int, online: Boolean = true) {
            clock.cycle += ticks
            player.currentMapClock = clock.cycle
            player.processedMapClock = clock.cycle
            if (online) marks.updateLifetimes(player)
        }

        fun pick(obj: Obj) {
            startPickup(obj)
            assertNotNull(result)
        }

        fun startPickup(obj: Obj) {
            result = null
            coroutine = GameCoroutine()
            player.activeCoroutine = coroutine
            val access = ProtectedAccess(player, coroutine,
                ProtectedAccessContextFactory.empty().copy(getEventBus = { events }))
            val block: suspend () -> Boolean = { events.publish(access, ObjDefaultEvents.Op3(obj)) }
            block.startCoroutine(object : Continuation<Boolean> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Boolean>) { this@Fixture.result = result }
            })
            result?.getOrThrow()
        }

        fun advancePickup() {
            elapse(1)
            coroutine.advance()
            result?.getOrThrow()
            assertNotNull(result)
        }

        fun fill() { for (slot in player.inv.indices) player.inv[slot] = InvObj("obj.bronze_dagger") }
        fun inventoryCount(): Int = player.inv.physicalCount(AgilityMarks.MARK)
        fun groundCount(): Int = marks.ownStack(player, location)?.count ?: 0
        fun storedCount(): Int = player.vars[location.countType]
        fun remaining(): Int = player.vars[location.lifetimeType]
        fun stack(): Obj = checkNotNull(marks.ownStack(player, location))

        private fun inventory(name: String, size: Int) = Inventory(
            checkNotNull(ServerCacheManager.getInventory(name.asRSCM())), arrayOfNulls(size),
        )
    }

    private class FixedRandom(first: Int) : GameRandom {
        val bounds = mutableListOf<Int>()
        private var next = first
        override fun of(maxExclusive: Int): Int {
            bounds += maxExclusive
            val value = next
            next = 0
            check(value in 0 until maxExclusive)
            return value
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = minInclusive
        override fun randomDouble(): Double = 0.0
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

        private fun course() = AgilityCoursesRow.getRow("dbrow.agility_draynor")

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
