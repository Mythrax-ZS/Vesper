package org.rsmod.content.drops

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.toml.TomlFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.openrune.ServerCacheManager
import dev.openrune.util.Wearpos
import dtx.core.ArgMap
import dtx.core.RollResult
import dtx.core.with
import dtx.impl.chance.ChanceRollable
import dtx.impl.chance.RateBoosts
import dtx.rs.RSDropTable
import dtx.rs.RSPreRollTable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.random.Random
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.droptable.DropRateBoosts
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.KillRollContext
import org.rsmod.api.droptable.rsPlayerTertiaryTable
import org.rsmod.api.droptable.toml.DropTableTomlParser
import org.rsmod.api.droptable.toml.DropTableTomlTextFixer
import org.rsmod.api.droptable.toml.TomlDropTableDef
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.drops.tables.monsters.aviansieDropTable
import org.rsmod.content.drops.tables.monsters.bloodveldDropTable
import org.rsmod.content.drops.tables.monsters.chaosFanaticDropTable
import org.rsmod.content.drops.tables.monsters.crazyArchaeologistDropTable
import org.rsmod.content.drops.tables.monsters.elderChaosDruidDropTable
import org.rsmod.content.drops.tables.monsters.scorpiaDropTable
import org.rsmod.content.drops.toml.ContentDropTableTomlResolver
import org.rsmod.content.quest.manager.QuestRequirementMode
import org.rsmod.content.quest.manager.QuestRequirementPolicy
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.map.CoordGrid

/** Deterministic runtime checks over the shipped Kotlin and TOML clue tables. */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
@ResourceLock("kotlin.random.Random.Default")
@ResourceLock("QuestRequirements")
@ResourceLock("DropRateBoosts")
@ResourceLock("RateBoosts")
class ClueScrollRollTest {
    private val previousClueBoost = DropRateBoosts.clueScrollBoostPercent
    private val previousWorldBoost = RateBoosts.multiplier
    private val previousQuestPolicy = QuestRequirements.activePolicy()
    private val areas = mockk<AreaChecker>()
    private val mapper = ObjectMapper(TomlFactory()).registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    @BeforeEach
    fun neutralRates() {
        DropRateBoosts.clueScrollBoostPercent = { _, _ -> 0 }
        RateBoosts.multiplier = { _, _ -> 1.0 }
        QuestRequirements.install(QuestRequirementPolicy(QuestRequirementMode.RespectProgress))
        every { areas.inArea(any<String>(), any<CoordGrid>()) } returns false
        every { areas.inArea("area.wilderness", WILD) } returns true
    }

    @AfterEach
    fun restoreGlobals() {
        DropRateBoosts.clueScrollBoostPercent = previousClueBoost
        RateBoosts.multiplier = previousWorldBoost
        QuestRequirements.install(previousQuestPolicy)
    }

    @Test
    fun `only claimed corresponding combat tier changes the runtime clue rate`() {
        for (tier in ClueScrollTier.entries) {
            val player = player()
            val roll = clueRoll(tier, 128)
            for (status in 0..3) {
                if (tier != ClueScrollTier.Beginner) {
                    VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_${tier.name.lowercase()}", status)
                }
                val denominator = if (tier != ClueScrollTier.Beginner && status == 2) 121 else 128
                assertEquals(100.0 / denominator, roll.chanceFor(player, args()), 1e-12, "$tier state $status")
            }
        }
        val player = player()
        VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_easy", 2)
        assertEquals(100.0 / 121, clueRoll(ClueScrollTier.Easy, 128).chanceFor(player, args()), 1e-12)
        assertEquals(100.0 / 128, clueRoll(ClueScrollTier.Hard, 128).chanceFor(player, args()), 1e-12)
    }

    @Test
    fun `all twelve wealth rings preserve the distinction between ordinary and imbued`() {
        val roll = clueRoll(ClueScrollTier.Hard, 128)
        for (ring in ORDINARY_RINGS + IMBUED_RINGS) {
            val player = player().apply { worn[Wearpos.Ring.slot] = InvObj(ring) }
            val denominator = if (ring in IMBUED_RINGS) 64 else 128
            assertEquals(100.0 / denominator, roll.chanceFor(player, args(WILD)), 1e-12, ring)
            assertEquals(100.0 / 128, roll.chanceFor(player, args(SAFE)), 1e-12, ring)
        }
        val carried = player().apply {
            inv[0] = InvObj(IMBUED_RINGS.last())
            worn[Wearpos.Front.slot] = InvObj(IMBUED_RINGS.first())
        }
        assertEquals(100.0 / 128, roll.chanceFor(carried, args(WILD)), 1e-12)
    }

    @Test
    fun `Wilderness doubling follows the drop tile even when player and NPC tiles disagree`() {
        val player = player().apply { worn[Wearpos.Ring.slot] = InvObj(IMBUED_RINGS.first()) }
        val roll = clueRoll(ClueScrollTier.Hard, 128)
        player.coords = SAFE
        assertEquals(100.0 / 64, roll.chanceFor(player, args(WILD, npcCoords = SAFE)), 1e-12)
        player.coords = WILD
        assertEquals(100.0 / 128, roll.chanceFor(player, args(SAFE, npcCoords = WILD)), 1e-12)
    }

    @Test
    fun `missing kill context cannot grant Wilderness doubling or pass an NPC filter`() {
        val player = player().apply {
            coords = WILD
            worn[Wearpos.Ring.slot] = InvObj(IMBUED_RINGS.first())
        }
        val roll = clueRoll(ClueScrollTier.Hard, 128)
        for (missing in listOf(
            ArgMap.Empty,
            ArgMap(KillRollContext.dropCoords with WILD),
            ArgMap(KillRollContext.areaChecker with areas),
            ArgMap(KillRollContext.npc with Npc("npc.slayer_jelly_1", WILD)),
        )) {
            assertEquals(100.0 / 128, roll.chanceFor(player, missing), 1e-12)
        }
        val jelly = parse("jelly").clues()
        assertTrue(jelly.tableEntries.none { it.includeInRoll(player, ArgMap.Empty) })
        assertTrue(jelly.tableEntries.none { it.includeInRoll(player, args(WILD, "npc.vorkath")) })
    }

    @Test
    fun `every shipped Jelly variant has one exclusive clue roll at its native base rate`() {
        val definition = definition("jelly")
        val table = parse(definition).clues()
        assertEquals(2, table.tableEntries.size, "one normal and one Wilderness variant roll")
        val player = player()
        for (npc in definition.npcs) {
            val context = args(SAFE, npc)
            val eligible = table.tableEntries.filter { it.includeInRoll(player, context) }
            assertEquals(1, eligible.size, npc)
            val base = if (npc.startsWith("npc.wild_cave_")) 64 else 128
            assertEquals(100.0 / base, eligible.single().chanceFor(player, context), 1e-12, npc)
            withChance(0.0) {
                val drops = table.roll(player, context).items()
                assertEquals(listOf(HARD), drops.map { it.obj }, npc)
                assertEquals(1..1, drops.single().count, npc)
            }
            withChance(99.999) { assertTrue(table.roll(player, context).items().isEmpty(), npc) }
        }
    }

    @Test
    fun `migrated Kotlin tables retain one hard clue roll and their source base rates`() {
        val sources = listOf(
            aviansieDropTable to 128, bloodveldDropTable to 256,
            chaosFanaticDropTable to 128, crazyArchaeologistDropTable to 128,
            elderChaosDruidDropTable to 128, scorpiaDropTable to 100,
        )
        for ((source, base) in sources) {
            val clues = source.clues()
            assertEquals(1, clues.tableEntries.size, source.tableIdentifier)
            assertRateStates(clues.tableEntries.single(), player(), base, ClueScrollTier.Hard)
            withChance(0.0) {
                assertEquals(listOf(HARD), clues.roll(player(), args()).items().map { it.obj }, source.tableIdentifier)
            }
        }
    }

    @Test
    fun `migrated resource tables have one dynamic base roll per clue tier without paired ring rolls`() {
        for ((name, expected) in TOML_RATES) {
            val def = definition(name)
            val sourceEntries = def.tertiary.filter { clueScrollTierForObj(it.obj) != null }
            assertEquals(expected.size, sourceEntries.size, name)
            assertEquals(expected, sourceEntries.associate { clueScrollTierForObj(it.obj)!! to it.denominator }, name)
            assertTrue(sourceEntries.all {
                it.numerator == 1 && it.clueRateModifiers && !it.requireRingOfWealth &&
                    !it.excludeRingOfWealth && !it.requireWilderness
            }, name)
            val clues = parse(def).clues()
            assertEquals(expected.size, clues.tableEntries.size, name)
            val player = player()
            for (entry in clues.tableEntries) {
                val item = entry.rollable.roll(player).items().single()
                val tier = checkNotNull(clueScrollTierForObj(item.obj))
                assertRateStates(entry, player, expected.getValue(tier), tier)
            }
            withChance(0.0) {
                val drops = clues.roll(player(), args(WILD)).items()
                assertEquals(expected.size, drops.size, name)
                assertEquals(drops.size, drops.map { it.obj }.distinct().size, name)
            }
        }
    }

    @Test
    fun `claimed reward ring clue bonus and opted-in world boost compose at runtime`() {
        val player = player().apply { worn[Wearpos.Ring.slot] = InvObj(IMBUED_RINGS.first()) }
        VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_hard", 2)
        val context = args(WILD)
        DropRateBoosts.clueScrollBoostPercent = { target, supplied ->
            assertTrue(target === player && supplied === context)
            50
        }
        RateBoosts.multiplier = { target, supplied ->
            assertTrue(target === player && supplied === context)
            2.0
        }
        // floor(256 * .95) = 243; Wilderness halves to 121; floor(121 / 1.5 / 2) = 40.
        assertEquals(100.0 / 40, clueRoll(ClueScrollTier.Hard, 256, true).chanceFor(player, context), 1e-12)
        assertEquals(100.0 / 80, clueRoll(ClueScrollTier.Hard, 256).chanceFor(player, context), 1e-12)
        DropRateBoosts.clueScrollBoostPercent = { _, _ -> -50 }
        assertEquals(100.0 / 121, clueRoll(ClueScrollTier.Hard, 256).chanceFor(player, context), 1e-12)
        DropRateBoosts.clueScrollBoostPercent = { _, _ -> Int.MAX_VALUE }
        assertEquals(100.0, clueRoll(ClueScrollTier.Hard, 256, true).chanceFor(player, context))
    }

    @Test
    fun `actual chance gate uses changed player rewards without rebuilding the table`() {
        val player = player()
        val table = rsPlayerTertiaryTable { onBuilder { clueScrollRoll(HARD, 128) } }
        // Between the unclaimed 1/128 and claimed 1/121 probabilities.
        withChance(0.8) {
            assertTrue(table.roll(player, args()).items().isEmpty())
            VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_hard", 1)
            assertTrue(table.roll(player, args()).items().isEmpty())
            VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_hard", 2)
            assertEquals(listOf(HARD), table.roll(player, args()).items().map { it.obj })
        }
        withChance(1.0) {
            assertTrue(table.roll(player, args(WILD)).items().isEmpty())
            player.worn[Wearpos.Ring.slot] = InvObj(IMBUED_RINGS.first())
            assertEquals(listOf(HARD), table.roll(player, args(WILD)).items().map { it.obj })
            assertTrue(table.roll(player, args(SAFE)).items().isEmpty())
        }
    }

    @Test
    fun `TOML preroll clues load and apply claimed rewards and Wilderness ring modifiers`() {
        val def = mapper.readValue<TomlDropTableDef>(
            """
            id = "Preroll clue modifiers"
            npcs = ["npc.slayer_jelly_1"]

            [[pre_roll]]
            numerator = 1
            denominator = 128
            obj = "$HARD"
            count = 1
            clue_rate_modifiers = true
            clue_scroll_box = true
            """.trimIndent(),
        )
        val table = parse(def)
        val preroll = table.tableEntries.elementAt(1) as RSPreRollTable<Player, DropRollItem>
        assertEquals(1, preroll.tableEntries.size)
        val player = player()
        withChance(0.8) {
            for (state in 0..2) {
                VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_hard", state)
                val expected = if (state == 2) listOf(HARD) else emptyList()
                assertEquals(expected, table.roll(player, args()).items().map { it.obj }, "CA state $state")
            }
        }
        withChance(1.0) {
            assertTrue(table.roll(player, args(WILD)).items().isEmpty())
            player.worn[Wearpos.Ring.slot] = InvObj(IMBUED_RINGS.first())
            assertEquals(listOf(HARD), table.roll(player, args(WILD)).items().map { it.obj })
            assertTrue(table.roll(player, args(SAFE)).items().isEmpty())
        }
    }

    @Test
    fun `TOML clue transformations and ordinary condition hooks survive the dynamic rate wrapper`() {
        val player = player()
        val item = parse("thug").clues().tableEntries.single().rollable.roll(player).items().single()
        assertNull(item.transformObj(player))
        QuestRequirements.install(QuestRequirementPolicy(
            QuestRequirementMode.VirtualCompletions, setOf(X_MARKS_THE_SPOT_QUEST),
        ))
        assertEquals("obj.league_clue_box_easy", item.transformObj(player))

        val playerOnly = rsPlayerTertiaryTable {
            onBuilder { clueScrollRoll(HARD, 128, item = DropRollItem(HARD, 1, condition = { false })) }
        }
        assertFalse(playerOnly.tableEntries.single().includeInRoll(player, args()))
        withChance(0.0) { assertTrue(playerOnly.roll(player, args()).items().isEmpty()) }

        val context = args()
        val original = DropRollItem(HARD, 1,
            condition = { it.hasUnlockedHardCombat() },
            killCondition = { _, npc, checker -> npc.type.id == context[KillRollContext.npc]!!.type.id && checker === areas },
            transformObj = { "obj.league_clue_box_hard" },
            bonusDrops = listOf(DropRollItem("obj.coins", 5)),
        )
        val roll = rsPlayerTertiaryTable {
            onBuilder { clueScrollRoll(HARD, 128, item = original) }
        }.tableEntries.single()
        assertFalse(roll.includeInRoll(player, context))
        VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_hard", 2)
        assertFalse(roll.includeInRoll(player, ArgMap.Empty))
        assertTrue(roll.includeInRoll(player, context))
        val retained = roll.rollable.roll(player, context).items().single()
        assertTrue(retained === original, "the dynamic chance must retain existing reward hooks")
        assertEquals("obj.league_clue_box_hard", retained.transformObj(player))
        assertEquals(listOf(DropRollItem("obj.coins", 5).obj), retained.bonusDrops.map { it.obj })
        assertEquals(5..5, retained.bonusDrops.single().count)
    }

    private fun assertRateStates(roll: ChanceRollable<Player, DropRollItem>, player: Player, base: Int, tier: ClueScrollTier) {
        for (status in 0..2) {
            VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_${tier.name.lowercase()}", status)
            val adjusted = if (status == 2) base * 95 / 100 else base
            player.worn[Wearpos.Ring.slot] = null
            assertEquals(100.0 / adjusted, roll.chanceFor(player, args(WILD)), 1e-12)
            player.worn[Wearpos.Ring.slot] = InvObj(IMBUED_RINGS.first())
            assertEquals(100.0 / (adjusted / 2), roll.chanceFor(player, args(WILD)), 1e-12)
            assertEquals(100.0 / adjusted, roll.chanceFor(player, args(SAFE)), 1e-12)
        }
    }

    private fun clueRoll(tier: ClueScrollTier, denominator: Int, boosted: Boolean = false): ChanceRollable<Player, DropRollItem> =
        rsPlayerTertiaryTable {
            if (boosted) boosted { onBuilder { clueScrollRoll(CLUES.getValue(tier), denominator) } }
            else onBuilder { clueScrollRoll(CLUES.getValue(tier), denominator) }
        }.tableEntries.single()

    private fun definition(name: String): TomlDropTableDef =
        checkNotNull(javaClass.getResourceAsStream("/drops/tables/monsters/$name.toml")) { name }
            .bufferedReader().use {
                mapper.readValue(DropTableTomlTextFixer.hoistTableLevelKeys(it.readText()))
            }

    private fun parse(name: String) = parse(definition(name))

    private fun parse(def: TomlDropTableDef) = DropTableTomlParser.parse(
        def, ContentDropTableTomlResolver(areas, mockk()),
    )

    private fun RSDropTable<Player, DropRollItem>.clues(): RSPreRollTable<Player, DropRollItem> {
        val tertiary = tableEntries.last() as RSPreRollTable<Player, DropRollItem>
        val entries = tertiary.tableEntries.filter { entry ->
            entry.rollable.roll(player()).items().any { clueScrollTierForObj(it.obj) != null }
        }
        return RSPreRollTable("$tableIdentifier clues", entries)
    }

    private fun player(): Player = Player().apply {
        coords = SAFE
        inv = Inventory.create("inv.inv")
        worn = Inventory.create("inv.worn")
    }

    private fun args(drop: CoordGrid = SAFE, npc: String = "npc.slayer_jelly_1", npcCoords: CoordGrid = drop): ArgMap = ArgMap(
        KillRollContext.dropCoords with drop,
        KillRollContext.areaChecker with areas,
        KillRollContext.npc with Npc(npc, npcCoords),
    )

    private fun withChance(chance: Double, action: () -> Unit) {
        mockkObject(Random.Default)
        try {
            every { Random.Default.nextDouble(any<Double>(), any<Double>()) } returns chance
            action()
        } finally {
            unmockkObject(Random.Default)
        }
    }

    private fun RollResult<DropRollItem>.items(): List<DropRollItem> = when (this) {
        is RollResult.Nothing -> emptyList()
        is RollResult.Single -> listOf(result)
        is RollResult.ListOf -> results
    }

    companion object {
        private val SAFE = CoordGrid(3200, 3200)
        private val WILD = CoordGrid(3200, 3600)
        private const val HARD = "obj.trail_clue_hard_map001"
        private val CLUES = mapOf(
            ClueScrollTier.Beginner to "obj.trail_clue_beginner",
            ClueScrollTier.Easy to "obj.trail_clue_easy_simple001",
            ClueScrollTier.Medium to "obj.trail_medium_emote_exp1",
            ClueScrollTier.Hard to HARD,
            ClueScrollTier.Elite to "obj.trail_elite_emote_exp1",
            ClueScrollTier.Master to "obj.trail_clue_master",
        )
        private val ORDINARY_RINGS = listOf(
            "obj.ring_of_wealth", "obj.ring_of_wealth_1", "obj.ring_of_wealth_2",
            "obj.ring_of_wealth_3", "obj.ring_of_wealth_4", "obj.ring_of_wealth_5",
        )
        private val IMBUED_RINGS = listOf(
            "obj.ring_of_wealth_i", "obj.ring_of_wealth_i1", "obj.ring_of_wealth_i2",
            "obj.ring_of_wealth_i3", "obj.ring_of_wealth_i4", "obj.ring_of_wealth_i5",
        )
        private val TOML_RATES = mapOf(
            "artio" to mapOf(ClueScrollTier.Elite to 100),
            "black_dragon" to mapOf(ClueScrollTier.Hard to 128, ClueScrollTier.Elite to 500),
            "callisto" to mapOf(ClueScrollTier.Elite to 100),
            "calvarion" to mapOf(ClueScrollTier.Elite to 100),
            "chaos_elemental" to mapOf(ClueScrollTier.Elite to 200),
            "feral_vampyre" to mapOf(ClueScrollTier.Medium to 128),
            "greater_nechryael_drop_table_wilderness" to mapOf(ClueScrollTier.Hard to 128),
            "knight_of_saradomin" to mapOf(ClueScrollTier.Hard to 128),
            "lava_dragon" to mapOf(ClueScrollTier.Elite to 250),
            "mammoth" to mapOf(ClueScrollTier.Medium to 128),
            "ork" to mapOf(ClueScrollTier.Easy to 128, ClueScrollTier.Hard to 128),
            "spindel" to mapOf(ClueScrollTier.Elite to 100),
            "spiritual_ranger" to mapOf(ClueScrollTier.Hard to 128),
            "spiritual_warrior" to mapOf(ClueScrollTier.Hard to 128),
            "thug" to mapOf(ClueScrollTier.Easy to 128),
            "venenatis" to mapOf(ClueScrollTier.Elite to 100),
            "vetion" to mapOf(ClueScrollTier.Elite to 100),
            "werewolf" to mapOf(ClueScrollTier.Easy to 128, ClueScrollTier.Medium to 512),
        )

        @JvmStatic @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
