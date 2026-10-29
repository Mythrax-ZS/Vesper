package org.rsmod.content.other.consumables

import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.map.area.MapAreaDecoder
import dev.openrune.map.area.MapAreaDefinitions
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.types.ItemServerType
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.stat
import org.rsmod.api.registry.loc.LocRegistryNormal
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.table.FoodRow
import org.rsmod.api.table.PotionRow
import org.rsmod.content.other.consumables.food.ConsumeFoodScript
import org.rsmod.content.other.consumables.food.FoodEffectService
import org.rsmod.content.other.consumables.food.FoodSpecialEffectService
import org.rsmod.content.other.consumables.food.kebab.KebabEffect
import org.rsmod.content.other.consumables.food.stew.SpicyStewEffect
import org.rsmod.content.other.consumables.heart.HeartEffectService
import org.rsmod.content.other.consumables.potion.ConsumePotionScript
import org.rsmod.content.other.consumables.potion.PotionEffectService
import org.rsmod.content.other.consumables.potion.PotionSpecialEffectService
import org.rsmod.content.other.consumables.potion.RelicymEffect
import org.rsmod.content.other.consumables.potion.RingOfEndurance
import org.rsmod.content.other.consumables.potion.cox.CoxPotionEffect
import org.rsmod.content.other.consumables.potion.moons.MoonlightPotionEffect
import org.rsmod.content.other.consumables.potion.nmz.NightmareZonePotionEffect
import org.rsmod.content.other.consumables.potion.toa.ToaPotionEffect
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.region.RegionListLarge
import org.rsmod.game.region.RegionListSmall
import org.rsmod.game.region.RegionListWorldEntity
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/** Registered held-item operations with packed areas; does not execute routing or a client. */
@Isolated("Installs the real inventory transaction service and loads ServerCacheManager")
class BlightedFeroxConsumptionTest {
    @Test
    fun `all blighted foods heal once in ground town and its reachable upper floors`() {
        val foods = listOf(
            Triple("dbrow.blighted_manta_ray", 22, 3),
            Triple("dbrow.blighted_karambwan", 18, 2),
            Triple("dbrow.blighted_anglerfish", 22, 3),
        )
        for (tile in FEROX_TILES) {
            for ((row, heal, combatDelay) in foods) {
                val f = Fixture(tile)
                assertTrue(f.areas.inArea("area.ferox_enclave", tile))
                assertFalse(f.areas.inArea("area.wilderness", tile))
                val type = FoodRow.getRow(row).items.single()
                f.player.inv[0] = InvObj(type)
                val otherItems = f.player.inv.objs.drop(1)
                f.consume(type)
                assertNull(f.player.inv[0], "$row at $tile")
                assertEquals(otherItems, f.player.inv.objs.drop(1))
                assertEquals(40 + heal, f.player.hitpoints, "$row at $tile")
                assertEquals(40, f.player.stat("stat.prayer"))
                assertEquals(100 + combatDelay, f.player.actionDelay)
                val category = if (row == "dbrow.blighted_karambwan") ConsumableType.COMBO_FOOD else ConsumableType.FOOD
                assertFalse(ConsumableDelayState.canConsume(f.access, category))
            }
        }
    }

    @Test
    fun `all blighted restore doses work on each Ferox floor with exact replacement and real stat effects`() {
        val potion = PotionRow.getRow("dbrow.blighted_super_restore")
        for (tile in FEROX_TILES) {
            for ((index, type) in potion.items.withIndex()) {
                val f = Fixture(tile)
                assertTrue(f.areas.inArea("area.ferox_enclave", tile))
                assertFalse(f.areas.inArea("area.wilderness", tile))
                f.player.inv[0] = InvObj(type)
                val otherItems = f.player.inv.objs.drop(1)
                f.consume(type)
                assertEquals(InvObj(potion.items.getOrNull(index + 1) ?: potion.empty), f.player.inv[0])
                assertEquals(otherItems, f.player.inv.objs.drop(1))
                assertEquals(72, f.player.stat("stat.attack"), "8 + floor(99 * 25 / 100) restored at $tile")
                assertEquals(72, f.player.stat("stat.prayer"))
                assertEquals(40, f.player.hitpoints, "Super restore must not heal hitpoints")
                assertEquals(100, f.player.actionDelay, "Ordinary potion has no additional attack delay")
                assertFalse(ConsumableDelayState.canConsume(f.access, ConsumableType.POTION))
            }
        }
    }

    @Test
    fun `existing Wilderness food and potion permission remains independent of the Ferox exception`() {
        for (tile in listOf(CoordGrid(3201, 3554), CoordGrid(3183, 3609, 1), CoordGrid(3183, 3609, 2))) {
            val f = Fixture(tile)
            assertTrue(f.areas.inArea("area.wilderness", tile))
            assertFalse(f.areas.inArea("area.ferox_enclave", tile))
            val food = FoodRow.getRow("dbrow.blighted_manta_ray").items.single()
            val potion = PotionRow.getRow("dbrow.blighted_super_restore")
            f.player.inv[0] = InvObj(food)
            f.player.inv[1] = InvObj(potion.items.first())
            f.consume(food)
            f.consume(potion.items.first(), slot = 1)
            assertNull(f.player.inv[0])
            assertEquals(InvObj(potion.items[1]), f.player.inv[1])
            assertEquals(62, f.player.hitpoints)
            assertEquals(72, f.player.stat("stat.prayer"))
            assertEquals(103, f.player.actionDelay)
        }
    }

    @Test
    fun `packed Mage Bank and its linked cape chamber allow all blighted food and restore doses`() {
        // Mage Bank permission is documented. The linked chamber uses the approved isolated
        // native region extent; these coordinates are not a live OSRS supply acceptance test.
        for (tile in listOf(CoordGrid(2538, 4715), CoordGrid(2508, 4696))) {
            val foods = listOf(
                "dbrow.blighted_manta_ray" to 22,
                "dbrow.blighted_karambwan" to 18,
                "dbrow.blighted_anglerfish" to 22,
            )
            for ((row, heal) in foods) {
                val f = Fixture(tile)
                assertTrue(f.areas.inArea("area.mage_arena_bank", tile))
                assertFalse(f.areas.inArea("area.wilderness", tile))
                assertFalse(f.areas.inArea("area.ferox_enclave", tile))
                val type = FoodRow.getRow(row).items.single()
                f.player.inv[0] = InvObj(type)
                val unrelated = f.player.inv.objs.drop(1)
                f.consume(type)
                assertNull(f.player.inv[0])
                assertEquals(unrelated, f.player.inv.objs.drop(1))
                assertEquals(40 + heal, f.player.hitpoints)
                val consumed = f.snapshot()
                f.consume(type)
                assertEquals(consumed, f.snapshot(), "Replayed Mage Bank food operation")
            }
            val potion = PotionRow.getRow("dbrow.blighted_super_restore")
            for ((index, type) in potion.items.withIndex()) {
                val f = Fixture(tile)
                f.player.inv[0] = InvObj(type)
                val unrelated = f.player.inv.objs.drop(1)
                f.consume(type)
                assertEquals(InvObj(potion.items.getOrNull(index + 1) ?: potion.empty), f.player.inv[0])
                assertEquals(unrelated, f.player.inv.objs.drop(1))
                assertEquals(72, f.player.stat("stat.prayer"))
                assertEquals(72, f.player.stat("stat.attack"))
                assertEquals(40, f.player.hitpoints)
                val consumed = f.snapshot()
                f.consume(type)
                assertEquals(consumed, f.snapshot(), "Replayed Mage Bank potion operation")
            }
        }
    }

    @Test
    fun `ordinary outside locations deny blighted supplies without consuming or changing stats and delays`() {
        for (tile in listOf(
            CoordGrid(3222, 3218), CoordGrid(3200, 3524),
            CoordGrid(2495, 4715), CoordGrid(2560, 4715),
            CoordGrid(2538, 4671), CoordGrid(2538, 4736), CoordGrid(2538, 4715, 1),
        )) {
            assertDeniedWithoutMutation(tile)
        }
    }

    @Test
    fun `current local basement policy denies supplies without inventing verified OSRS basement eligibility`() {
        // The current sources establish surface Ferox use, but do not settle the basement rule.
        for (tile in listOf(CoordGrid(3150, 10015), CoordGrid(3156, 10024))) {
            val f = Fixture(tile)
            assertTrue(f.areas.inArea("area.ferox_safe_zone", tile))
            assertFalse(f.areas.inArea("area.ferox_enclave", tile))
            assertFalse(f.areas.inArea("area.mage_arena_bank", tile))
            assertDeniedWithoutMutation(tile)
        }
    }

    @Test
    fun `replayed or stale food operations never consume another slot or heal a second time`() {
        val f = Fixture(FEROX_TILES.last())
        val food = FoodRow.getRow("dbrow.blighted_manta_ray").items.single()
        f.player.inv[0] = InvObj(food)
        f.player.inv[1] = InvObj(food)
        f.consume(food)
        assertEquals(62, f.player.hitpoints)
        val committed = f.snapshot()
        repeat(2) { f.consume(food) }
        assertEquals(committed, f.snapshot())

        f.advance(10)
        val expired = f.snapshot()
        repeat(2) { f.consume(food) }
        assertEquals(expired, f.snapshot(), "Missing slot must not take the matching food in slot 1")
        f.player.inv[0] = InvObj("obj.bronze_dagger")
        val replaced = f.snapshot()
        repeat(2) { f.consume(food) }
        assertEquals(replaced, f.snapshot(), "Stale food type must not remove a replacement item")
        f.consume(food, slot = 1)
        assertNull(f.player.inv[1])
        assertEquals(InvObj("obj.bronze_dagger"), f.player.inv[0])
        assertEquals(84, f.player.hitpoints)
    }

    @Test
    fun `replayed or stale potion operations preserve remaining doses and never repeat restoration`() {
        val f = Fixture(FEROX_TILES.first())
        val potion = PotionRow.getRow("dbrow.blighted_super_restore")
        val first = potion.items.first()
        f.player.inv[0] = InvObj(first)
        f.player.inv[1] = InvObj(first)
        f.consume(first)
        assertEquals(InvObj(potion.items[1]), f.player.inv[0])
        assertEquals(72, f.player.stat("stat.prayer"))
        val committed = f.snapshot()
        repeat(2) { f.consume(first) }
        assertEquals(committed, f.snapshot())
        f.advance(10)
        val expired = f.snapshot()
        repeat(2) { f.consume(first) }
        assertEquals(expired, f.snapshot(), "Old four-dose operation must not consume three-dose slot or other four-dose slot")
        for (replacement in listOf(null, InvObj("obj.bronze_dagger"))) {
            f.player.inv[0] = replacement
            val before = f.snapshot()
            repeat(2) { f.consume(first) }
            assertEquals(before, f.snapshot())
        }
        f.player.inv[0] = InvObj(potion.items[1])
        f.consume(potion.items[1])
        assertEquals(InvObj(potion.items[2]), f.player.inv[0])
        assertEquals(InvObj(first), f.player.inv[1])
        assertEquals(99, f.player.stat("stat.prayer"), "A valid second dose restores only to the base level")
        assertEquals(40, f.player.hitpoints)
    }

    @Test
    fun `full inventories consume food and replace the final potion dose without losing unrelated items`() {
        val food = FoodRow.getRow("dbrow.blighted_manta_ray").items.single()
        val potion = PotionRow.getRow("dbrow.blighted_super_restore")
        for (type in listOf(food, potion.items.last())) {
            val f = Fixture(CoordGrid(3134, 3641, 1))
            for (slot in f.player.inv.indices) f.player.inv[slot] = InvObj("obj.bronze_dagger")
            f.player.inv[0] = InvObj(type)
            val unrelated = f.player.inv.objs.drop(1)
            f.consume(type)
            assertEquals(unrelated, f.player.inv.objs.drop(1))
            if (type == food) {
                assertNull(f.player.inv[0])
                assertEquals(1, f.player.inv.freeSpace())
                assertEquals(62, f.player.hitpoints)
            } else {
                assertEquals(InvObj(potion.empty), f.player.inv[0])
                assertEquals(0, f.player.inv.freeSpace())
                assertEquals(72, f.player.stat("stat.prayer"))
            }
        }
    }

    private fun assertDeniedWithoutMutation(tile: CoordGrid) {
        val types = listOf("dbrow.blighted_manta_ray", "dbrow.blighted_karambwan", "dbrow.blighted_anglerfish")
            .map { FoodRow.getRow(it).items.single() } + PotionRow.getRow("dbrow.blighted_super_restore").items
        for (type in types) {
            val f = Fixture(tile)
            assertFalse(f.areas.inArea("area.wilderness", tile))
            assertFalse(f.areas.inArea("area.ferox_enclave", tile))
            assertFalse(f.areas.inArea("area.mage_arena_bank", tile))
            f.player.inv[0] = InvObj(type)
            f.player.actionDelay = 147
            val before = f.snapshot()
            repeat(2) { f.consume(type) }
            assertEquals(before, f.snapshot(), "${type.internalName} at $tile")
        }
    }

    private class Fixture(tile: CoordGrid) {
        val clock = MapClock(100)
        private val collision = CollisionFlagMap()
        private val locs = LocZoneStorage()
        private val regions = RegionRegistry(
            RegionListSmall(), RegionListLarge(), RegionListWorldEntity(),
            LocRegistryNormal(ZoneUpdateMap(), collision, locs), collision, locs,
            mockk(), mockk(), ZonePlayerActivityBitSet(),
        )
        val areas = AreaChecker(regions, areaIndex)
        val player = Player().apply {
            client = SilentClient()
            worldType = WorldType.MAIN
            coords = tile
            currentMapClock = clock.cycle
            processedMapClock = clock.cycle
            actionDelay = clock.cycle
            inv = Inventory.create("inv.inv")
            worn = Inventory.create("inv.worn")
            inv.owner = this
            worn.owner = this
            for (stat in ServerCacheManager.getStats().values) {
                statMap.setBaseLevel(stat.internalName, 99)
                statMap.setCurrentLevel(stat.internalName, 40)
            }
            inv[1] = InvObj("obj.bronze_dagger")
        }
        private val events = EventBus()
        val access = ProtectedAccess(player, GameCoroutine(), ProtectedAccessContextFactory.empty().copy(
            getEventBus = { events }, getAreaChecker = { areas },
        ))

        init {
            val food = ConsumeFoodScript(areas, FoodEffectService(), FoodSpecialEffectService(SpicyStewEffect(), KebabEffect()))
            // Only unrelated raid/NMZ effect implementations are mocks. Registration support and
            // the tested restore effect, area predicates, item transaction and stat changes are real.
            val special = PotionSpecialEffectService(
                CoxPotionEffect(mockk(), mockk()), ToaPotionEffect(mockk(), mockk(), mockk()),
                NightmareZonePotionEffect(mockk(), mockk()), MoonlightPotionEffect(), RelicymEffect(),
                HeartEffectService(clock), clock,
            )
            val potion = ConsumePotionScript(areas, PotionEffectService(special, clock), ConsumableActivityAccess(), RingOfEndurance(ObjChargeManager()))
            val context = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
            with(food) { context.startup() }
            with(potion) { context.startup() }
        }

        fun consume(type: ItemServerType, slot: Int = 0) {
            assertTrue(type.interfaceOptions.first().equals("Eat", true) || type.interfaceOptions.first().equals("Drink", true))
            val event = HeldObjEvents.Op1(slot, InvObj(type), type, player.inv)
            var completed: Result<Boolean>? = null
            val block: suspend () -> Boolean = { events.publish(access, event) }
            block.startCoroutine(object : Continuation<Boolean> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Boolean>) { completed = result }
            })
            assertTrue(checkNotNull(completed) { "Consumption unexpectedly suspended" }.getOrThrow(), "Native item operation must be registered")
        }

        fun advance(ticks: Int) {
            clock.cycle += ticks
            player.currentMapClock = clock.cycle
            player.processedMapClock = clock.cycle
        }

        fun snapshot() = Snapshot(
            player.inv.objs.toList(), player.worn.objs.toList(),
            ServerCacheManager.getStats().values.associate { it.internalName to player.stat(it.internalName) },
            ServerCacheManager.getStats().values.associate { it.internalName to player.statMap.getFineXP(it.internalName) },
            player.actionDelay, ConsumableType.entries.map { ConsumableDelayState.canConsume(access, it) },
        )
    }

    private data class Snapshot(
        val inv: List<InvObj?>,
        val worn: List<InvObj?>,
        val stats: Map<String, Int>,
        val xp: Map<String, Int>,
        val actionDelay: Int,
        val available: List<Boolean>,
    )

    private class SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private val FEROX_TILES = listOf(
            CoordGrid(3130, 3630), CoordGrid(3132, 3621, 1), CoordGrid(3137, 3621, 1),
            CoordGrid(3134, 3641, 1), CoordGrid(3137, 3644, 1), CoordGrid(3141, 3637, 2),
        )
        private lateinit var areaIndex: AreaIndex
        private val restorations = mutableListOf<() -> Unit>()

        @JvmStatic @BeforeAll
        fun cache() {
            val cache = ServerCacheManager.init(241)
            try {
                areaIndex = AreaIndex()
                for (region in cache.archives(MAPS)) {
                    if (region > 25286 || 7 !in cache.files(MAPS, region)) continue
                    if (cache.data(MAPS, region, 0) == null || cache.data(MAPS, region, 1) == null) continue
                    val data = checkNotNull(cache.data(MAPS, region, 7))
                    MapAreaDefinitions.putAreas(areaIndex, MapSquareKey(region), MapAreaDecoder.decode(InlineByteBuf(data)))
                }
            } finally {
                cache.close()
            }
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val previous = field.get(null)
                restorations += { field.set(null, previous) }
            }
            val storage = InvVirtualStorageHolder.instance
            restorations += { InvVirtualStorageHolder.instance = storage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic @AfterAll
        fun restore() {
            restorations.asReversed().forEach { it() }
            restorations.clear()
        }
    }
}
