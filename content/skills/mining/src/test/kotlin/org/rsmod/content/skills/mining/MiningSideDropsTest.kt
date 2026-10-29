package org.rsmod.content.skills.mining

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.util.Wearpos
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.player.events.skilling.SkillingActionCompleteEvent
import org.rsmod.api.player.events.skilling.SkillingActionContext
import org.rsmod.api.player.events.skilling.SkillingProductSource
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.table.mining.MiningClueGeodesRow
import org.rsmod.api.table.mining.MiningRocksRow
import org.rsmod.content.other.treasuretrails.ClueOwnership
import org.rsmod.content.other.treasuretrails.ClueTier
import org.rsmod.content.quest.area.lumbridge.xmarksthespot.XMarksTheSpotQuest
import org.rsmod.content.quest.manager.Quest
import org.rsmod.content.quest.manager.QuestRequirementMode
import org.rsmod.content.quest.manager.QuestRequirementPolicy
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.content.skills.mining.scripts.MiningSideDropsScript
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class MiningSideDropsTest {
    @AfterEach
    fun restoreQuestMode() = QuestRequirements.install(QuestRequirementPolicy())

    @Test
    fun `rate arithmetic preserves both intermediate floors and the final tier floor`() {
        assertEquals(1027, denominator(741600, 1, 2))
        assertEquals(8737, denominator(741600, 1, 17))
        assertEquals(45140, denominator(741600, 15, 100))
        assertEquals(521, denominator(741600, 99, 2))
        assertEquals(4434, denominator(741600, 99, 17))
        assertEquals(4212, denominator(741600, 99, 17, ca = true))
        assertEquals(8178, denominator(741600, 99, 33, ca = true))
        assertEquals(24782, denominator(741600, 99, 100, ca = true))
        assertEquals(10594, denominator(211886, 40, 100))
        assertEquals(7080, denominator(211886, 99, 100, ca = true))
        assertEquals(1603, denominator(42377, 85, 100))
        assertEquals(1523, denominator(42377, 85, 100, ca = true))
        assertEquals(761, denominator(42377, 85, 100, ca = true, wilderness = true))
        // The calculation is exact rational arithmetic, not a floating-point calculator artifact.
        assertEquals(51398, denominator(741600, 1, 100))
    }

    @Test
    fun `drained and boosted visible levels apply beyond 99 without overflowing arithmetic`() {
        assertEquals(1038, denominator(741600, 0, 2))
        assertEquals(504, denominator(741600, 106, 2))
        assertEquals(23940, denominator(741600, 106, 100, ca = true))
        assertEquals(150323855, denominator(Int.MAX_VALUE, 0, 100))
        assertEquals(1, denominator(1, Int.MAX_VALUE, 100))
    }

    @Test
    fun `cache supplies five distinct tiers and the verified rock base rates`() {
        assertEquals(5, rows().size)
        assertEquals(setOf(2, 17, 20, 33, 100), rows().map { it.multiplierTenths }.toSet())
        assertEquals(5, rows().map { it.geode.id }.toSet().size)
        assertEquals(5, rows().map { it.box.id }.toSet().size)
        val beginner = rows().single { it.geode.internalName.endsWith("_beginner") }
        assertEquals("", beginner.combatReward)
        assertFalse(beginner.wildernessBonus)
        assertEquals(211886, rock("gemrock").clueBaseChance)
        assertEquals(741600, rock("sandstonerock").clueBaseChance)
        assertEquals(741600, rock("graniterock").clueBaseChance)
        assertEquals(710000, rock("volcanicsulphur").clueBaseChance)
    }

    @Test
    fun `all five eligible tiers roll independently and multiple successes preserve the ore`() {
        val f = Fixture()
        f.player.inv[0] = InvObj("obj.iron_ore")
        f.roll()
        assertEquals(listOf(521, 4434, 5217, 8608, 26086), f.random.bounds.sorted())
        for (row in rows()) assertEquals(1, f.count(row.geode.internalName))
        assertEquals(1, f.count("obj.iron_ore"))
        assertTrue(f.ground().isEmpty())
        f.random.bounds.clear()
        f.roll()
        assertTrue(f.random.bounds.isEmpty(), "Owning each tier prevents further eligible rolls")
    }

    @Test
    fun `failing every independent roll gives no reward`() {
        val f = Fixture()
        f.random.choose = { it - 1 }
        f.roll()
        assertEquals(5, f.random.bounds.size)
        assertTrue(f.player.inv.isEmpty())
        assertTrue(f.ground().isEmpty())
    }

    @Test
    fun `owning lower tiers never improves or suppresses the elite probability`() {
        val f = Fixture()
        f.blockOtherTiers("elite")
        f.random.choose = { it - 1 }
        f.roll()
        assertEquals(listOf(26086), f.random.bounds.sorted())
        f.random.choose = { 0 }
        f.roll()
        assertEquals(1, f.count("obj.mining_clue_geode_elite"))
    }

    @Test
    fun `enumerating every random result gives exactly one winning outcome per tier denominator`() {
        for ((tier, denominator) in listOf("beginner" to 149, "easy" to 1267, "elite" to 7453)) {
            val f = Fixture()
            f.blockOtherTiers(tier)
            var successes = 0
            for (draw in 0 until denominator) {
                f.player.inv.fillNulls()
                f.random.choose = { bound -> assertEquals(denominator, bound); draw }
                f.roll("gemrock")
                successes += f.count("obj.mining_clue_geode_$tier")
            }
            assertEquals(denominator, f.random.bounds.size)
            assertEquals(1, successes, tier)
            assertTrue(f.ground().isEmpty())
        }
    }

    @Test
    fun `only claimed matching combat rewards improve their tiers and never beginner`() {
        for (status in 0..2) {
            val f = Fixture()
            f.random.choose = { it - 1 }
            for (tier in listOf("easy", "medium", "hard", "elite")) {
                f.set("varbit.ca_tier_status_$tier", status)
            }
            f.roll()
            val expected = if (status == 2) listOf(521, 4212, 4956, 8178, 24782)
                else listOf(521, 4434, 5217, 8608, 26086)
            assertEquals(expected, f.random.bounds.sorted(), "CA status $status")
        }
        val f = Fixture()
        f.set("varbit.ca_tier_status_easy", 2)
        f.roll()
        assertEquals(listOf(521, 4212, 5217, 8608, 26086), f.random.bounds.sorted())
    }

    @Test
    fun `production rolls use the visible level rather than the base or invisible equipment boost`() {
        val f = Fixture()
        f.player.statMap.setBaseLevel("stat.mining", 1)
        f.player.statMap.setCurrentLevel("stat.mining", 106)
        f.player.worn[Wearpos.Ring.slot] = InvObj("obj.celestial_ring")
        f.roll()
        assertEquals(listOf(504, 4284, 5040, 8316, 25200), f.random.bounds.sorted())
    }

    @Test
    fun `only imbued rings in the Wilderness double easy through elite including all charge states`() {
        val normal = listOf("obj.ring_of_wealth") + (1..5).map { "obj.ring_of_wealth_$it" }
        val imbued = listOf("obj.ring_of_wealth_i") + (1..5).map { "obj.ring_of_wealth_i$it" }
        for (wild in listOf(false, true)) for (symbol in normal + imbued) {
            val f = Fixture(wilderness = wild)
            f.player.worn[Wearpos.Ring.slot] = InvObj(symbol)
            f.roll()
            val expected = if (wild && symbol in imbued) listOf(521, 2217, 2608, 4304, 13043)
                else listOf(521, 4434, 5217, 8608, 26086)
            assertEquals(expected, f.random.bounds.sorted(), "$symbol wilderness=$wild")
        }
    }

    @Test
    fun `Arthur opt out suppresses geode rolls but not crystal shards`() {
        val f = Fixture()
        f.set("varbit.vesseled_clues_disabled", 1)
        f.complete(coords = TRAHAEARN)
        assertEquals(listOf(65), f.random.bounds.sorted())
        assertEquals(1, f.count(SHARD))
        for (row in rows()) assertEquals(0, f.count(row.geode.internalName))
    }

    @Test
    fun `clue ownership in carried bank worn gravestone and death storage prevents same tier rolls`() {
        for (storage in listOf("inv.inv", "inv.worn", "inv.bank", "inv.gravestone", "inv.death_permanent")) {
            val f = Fixture()
            val container = when (storage) {
                "inv.inv" -> f.player.inv
                "inv.worn" -> f.player.worn
                else -> f.storage(storage)
            }
            container[0] = InvObj("obj.mining_clue_geode_beginner")
            f.roll()
            assertEquals(listOf(4434, 5217, 8608, 26086), f.random.bounds.sorted(), storage)
            assertEquals(1L, ClueOwnership.count(f.player, ClueTier.Beginner))
        }
    }

    @Test
    fun `actual quest progress and explicit world quest policies select geodes or scroll boxes`() {
        for (stage in listOf(0, 7, 8)) {
            val f = Fixture()
            f.set("varbit.cluequest", stage)
            f.roll()
            for (row in rows()) {
                assertEquals(if (stage == 8) 1 else 0, f.count(row.box.internalName))
                assertEquals(if (stage == 8) 0 else 1, f.count(row.geode.internalName))
            }
        }
        for (policy in listOf(
            QuestRequirementPolicy(QuestRequirementMode.AssumeCompleted),
            QuestRequirementPolicy(QuestRequirementMode.VirtualCompletions,
                virtualCompletions = setOf("quest_xmarksthespot")),
        )) {
            QuestRequirements.install(policy)
            val f = Fixture()
            f.roll()
            for (row in rows()) assertEquals(1, f.count(row.box.internalName))
        }
    }

    @Test
    fun `boxes respect shared capacity and disabled reminders send surplus only to personal ground`() {
        for (reminder in 0..1) {
            val f = Fixture()
            f.set("varbit.cluequest", 8)
            for (row in rows()) f.storage("inv.bank").let { bank ->
                bank[rows().indexOf(row)] = InvObj(row.box.internalName, 2)
                val tier = checkNotNull(ClueOwnership.tier(row.box)).name.lowercase()
                f.set("varbit.option_trail_reminder_$tier", reminder)
            }
            f.roll()
            assertTrue(f.player.inv.isEmpty())
            assertEquals(if (reminder == 0) 5 else 0, f.random.bounds.size)
            assertEquals(if (reminder == 0) 5 else 0, f.ground().sumOf { it.count })
            for (tier in ClueTier.entries.take(5)) assertEquals(2L, ClueOwnership.count(f.player, tier))
        }
    }

    @Test
    fun `scroll cases permit extra capacity while legacy vessels cannot bypass occupied box capacity`() {
        val f = Fixture()
        f.set("varbit.cluequest", 8)
        f.set("varbit.scroll_case_beginner_minor", 1)
        f.player.inv[0] = InvObj("obj.league_clue_box_beginner", 2)
        f.roll()
        assertEquals(3, f.count("obj.league_clue_box_beginner"))
        assertTrue(f.ground().isEmpty())
        // A realm reverting quest completion must not invent room for a legacy vessel.
        f.set("varbit.cluequest", 0)
        f.random.bounds.clear()
        f.roll()
        assertFalse(521 in f.random.bounds)
        assertEquals(0, f.count("obj.mining_clue_geode_beginner"))
    }

    @Test
    fun `full inventory delivers each clue once and keeps it private from group members and public`() {
        val f = Fixture()
        f.fill()
        val before = f.player.inv.objs.toList()
        f.roll()
        assertEquals(before, f.player.inv.objs.toList())
        assertEquals(5, f.ground().size)
        val other = Fixture().player.apply { uuid = 999L }
        for (obj in f.ground()) {
            assertEquals(1, obj.count)
            assertEquals(f.player.uuid, obj.personalReceiverId)
            assertTrue(obj.isVisibleTo(f.player))
            assertFalse(obj.isVisibleTo(other), "Matching group observer must not share personal clues")
            assertFalse(obj.isVisibleTo(null))
            f.registry.reveal(obj, 200)
            assertFalse(obj.isPublic)
        }
    }

    @Test
    fun `primary and enhancer resources each roll shards at one in 65 in the mine`() {
        val f = Fixture()
        f.set("varbit.vesseled_clues_disabled", 1)
        f.complete(coords = TRAHAEARN)
        f.complete(coords = TRAHAEARN, count = 2, bonus = true)
        assertEquals(listOf(65, 65, 65), f.random.bounds.sorted())
        assertEquals(3, f.count(SHARD))
        assertTrue(f.ground().isEmpty())
        f.complete(coords = CoordGrid(3222, 3218, 0), bonus = true)
        assertEquals(3, f.random.bounds.size)
    }

    @Test
    fun `every shard random outcome conserves exactly one winning reward`() {
        val f = Fixture()
        f.set("varbit.vesseled_clues_disabled", 1)
        for (draw in 0 until 65) {
            f.random.choose = { bound -> assertEquals(65, bound); draw }
            f.complete(coords = TRAHAEARN, bonus = true)
        }
        assertEquals(65, f.random.bounds.size)
        assertEquals(1, f.count(SHARD))
        assertTrue(f.ground().isEmpty())
    }

    @Test
    fun `full and overflowing inventories drop shards without deleting ores or existing stacks`() {
        for (overflow in listOf(false, true)) {
            val f = Fixture()
            f.fill()
            if (overflow) f.player.inv[0] = InvObj(SHARD, Int.MAX_VALUE)
            f.set("varbit.vesseled_clues_disabled", 1)
            val before = f.player.inv.objs.toList()
            f.complete(coords = TRAHAEARN, count = 2, bonus = true)
            assertEquals(before, f.player.inv.objs.toList())
            assertEquals(2, f.ground().sumOf { it.count })
            assertEquals(SHARD.asRSCM(), f.ground().single().type)
            assertEquals(f.player.uuid, f.ground().single().personalReceiverId)
        }
    }

    @Test
    fun `bonus ore does not add clue rolls and incidental gems are not successful ore actions`() {
        val f = Fixture()
        f.complete(bonus = true)
        f.complete(coords = TRAHAEARN, item = "obj.uncut_sapphire")
        f.complete(skill = "stat.fishing")
        assertTrue(f.random.bounds.isEmpty())
        f.complete(rockKey = "gemrock", item = "obj.uncut_sapphire")
        assertEquals(listOf(149, 1267, 1490, 2459, 7453), f.random.bounds.sorted())
    }

    @Test
    fun `a rock with no geode base chance consumes no clue random rolls`() {
        val f = Fixture()
        f.roll("lunarore")
        assertTrue(f.random.bounds.isEmpty())
        assertTrue(f.player.inv.isEmpty())
    }

    @OptIn(InternalApi::class)
    private class Fixture(wilderness: Boolean = false) {
        val clock = MapClock(100)
        val registry = ObjRegistry(ZoneUpdateMap())
        val objects = ObjRepository(clock, registry)
        val random = RecordedRandom()
        private val areas = mockk<AreaChecker>().apply {
            every { inArea("area.wilderness", any()) } returns wilderness
        }
        val script = MiningSideDropsScript(random, objects, areas)
        private val events = EventBus()
        val player = Player().apply {
            worldType = WorldType.MAIN
            client = SilentClient()
            uuid = 927L
            observerUUID = 5555L
            slotId = 1
            assignUid()
            coords = CoordGrid(3222, 3218, 0)
            currentMapClock = clock.cycle
            processedMapClock = clock.cycle
            inv = inventory("inv.inv", 28)
            worn = inventory("inv.worn", 14)
            statMap.setBaseLevel("stat.mining", 99)
            statMap.setCurrentLevel("stat.mining", 99)
        }

        init {
            with(script) { ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup() }
        }

        fun roll(key: String = "ironrock") = script.rollClueGeodes(player, rock(key))
        fun count(symbol: String) = player.inv.physicalCount(symbol)
        fun ground() = objects.findAll(player.coords).toList()
        fun set(symbol: String, value: Int) = VarPlayerIntMapSetter.set(player, symbol, value)
        fun fill() { for (slot in player.inv.indices) player.inv[slot] = InvObj("obj.bronze_dagger") }

        fun storage(symbol: String): Inventory {
            player.invMap[symbol]?.let { return it }
            val inv = inventory(symbol, 28)
            player.invMap[symbol] = inv
            return inv
        }

        fun blockOtherTiers(tier: String) {
            rows().filterNot { it.geode.internalName.endsWith("_$tier") }.forEachIndexed { index, row ->
                storage("inv.bank")[index] = InvObj(row.geode.internalName)
            }
        }

        fun complete(
            coords: CoordGrid = player.coords,
            rockKey: String = "ironrock",
            item: String = "obj.iron_ore",
            count: Int = 1,
            bonus: Boolean = false,
            skill: String = "stat.mining",
        ) {
            val row = rock(rockKey)
            val type = row.rockObject.first()
            val loc = BoundLocInfo(LocInfo(0, coords, LocEntity(type.id, 10, 0)), type)
            val product = SkillingActionContext.Product(skill, item, count, 35.0,
                SkillingProductSource.Mining(loc, row), bonus)
            events.publish(SkillingActionCompleteEvent(player, product))
        }

        private fun inventory(symbol: String, size: Int) = Inventory(
            checkNotNull(ServerCacheManager.getInventory(symbol.asRSCM())), arrayOfNulls(size),
        )
    }

    private class RecordedRandom : GameRandom {
        val bounds = mutableListOf<Int>()
        var choose: (Int) -> Int = { 0 }
        override fun of(maxExclusive: Int): Int {
            bounds += maxExclusive
            return choose(maxExclusive).also { check(it in 0 until maxExclusive) }
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unexpected range roll")
        override fun randomDouble(): Double = error("Unexpected floating-point roll")
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
        private const val SHARD = "obj.prif_crystal_shard"
        private val TRAHAEARN = CoordGrid(3300, 12440, 0)
        private val RESTORATIONS = mutableListOf<() -> Unit>()
        private fun rows() = MiningClueGeodesRow.all()
        private fun rock(key: String) = MiningRocksRow.getRow("dbrow.mining_$key")
        private fun denominator(base: Int, level: Int, tenths: Int, ca: Boolean = false, wilderness: Boolean = false) =
            miningGeodeDenominator(base, level, tenths, ca, wilderness)

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            ServerCacheManager.init(241).close()
            val previousPolicy = QuestRequirements.activePolicy()
            RESTORATIONS += { QuestRequirements.install(previousPolicy) }
            QuestRequirements.install(QuestRequirementPolicy())
            val registryField = Quest::class.java.getDeclaredField("questsByKey").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val registry = registryField.get(null) as MutableMap<String, Quest>
            val previousQuests = registry.toMap()
            RESTORATIONS += { registry.clear(); registry.putAll(previousQuests) }
            XMarksTheSpotQuest(ObjRepository(MapClock(), ObjRegistry(ZoneUpdateMap())))
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
