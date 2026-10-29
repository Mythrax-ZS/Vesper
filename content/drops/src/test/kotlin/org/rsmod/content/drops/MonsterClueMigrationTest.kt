package org.rsmod.content.drops

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
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
import org.rsmod.api.droptable.toml.DropTableTomlParser
import org.rsmod.api.droptable.toml.DropTableTomlTextFixer
import org.rsmod.api.droptable.toml.TomlDropTableDef
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.drops.toml.ContentDropTableTomlResolver
import org.rsmod.content.quest.manager.QuestRequirementMode
import org.rsmod.content.quest.manager.QuestRequirementPolicy
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.map.CoordGrid

/** Tests actual shipped tables against the retained, revision-pinned source audit receipt. */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
@ResourceLock("kotlin.random.Random.Default")
@ResourceLock("QuestRequirements")
@ResourceLock("DropRateBoosts")
@ResourceLock("RateBoosts")
class MonsterClueMigrationTest {
    private val receipt = ObjectMapper().readTree(resource("/parity/monster-clue-rates.json"))
    private val entries = receipt["entries"].toList()
    private val mapper = ObjectMapper(TomlFactory()).registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    private val areas = mockk<AreaChecker>()
    private val previousClueBoost = DropRateBoosts.clueScrollBoostPercent
    private val previousWorldBoost = RateBoosts.multiplier
    private val previousQuestPolicy = QuestRequirements.activePolicy()
    private val tables = mutableMapOf<String, RSDropTable<Player, DropRollItem>>()

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
    fun `receipt pins every migrated base fraction to a current source revision`() {
        assertEquals(317, entries.size)
        assertEquals(273, entries.map { it.text("file") }.distinct().size)
        assertEquals(89, receipt["unresolved"].size())
        for (entry in entries) {
            val source = receipt["sources"][entry.text("source_revision")]
            assertTrue(source["revision_id"].asLong() > 0)
            assertEquals(64, source["sha256"].asText().length)
            assertTrue(source["revision_url"].asText().endsWith(entry.text("source_revision")))
            assertTrue(entry["source_lines"].any { it.asInt() > 0 })
            assertEquals(listOf("${entry.int("numerator")}/${entry.int("denominator")}"),
                entry["source_rates"].map { it.asText() }, entry.text("table"))
        }
    }

    @Test
    fun `migrated TOML passes production repair strict decoding and runtime construction`() {
        for (entry in entries.filter { it.text("format") == "toml" }) {
            val definition = definition(entry)
            val chance = definition.tertiary[entry.int("tertiary_index")]
            assertEquals(entry.text("obj"), chance.obj)
            assertEquals(entry.int("numerator"), chance.numerator)
            assertEquals(entry.int("denominator"), chance.denominator)
            assertTrue(chance.clueRateModifiers)
            assertFalse(chance.requireRingOfWealth || chance.excludeRingOfWealth || chance.requireWilderness)
            val table = table(entry)
            assertEquals(definition.npcs, table.npcs)
            assertEquals(definition.areas, table.areas)
            assertEquals(definition.id, table.tableIdentifier)
            assertEquals(1, matchingRolls(entry).size, entry.text("file"))
        }
    }

    @Test
    fun `all migrated rolls apply only claimed matching CA rewards and equipped imbued Wilderness rings`() {
        for (entry in entries) {
            val player = player()
            val roll = matchingRolls(entry).single()
            val numerator = entry.int("numerator")
            val base = entry.int("denominator")
            val tier = entry.text("tier")
            assertEquals(100.0 * numerator / base, roll.chance, 1e-12, entry.text("table"))
            for (status in 0..3) {
                if (tier != "beginner") {
                    VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_$tier", status)
                }
                val claimed = tier != "beginner" && status == 2
                val denominator = if (claimed) (base * 95 / 100).coerceAtLeast(1) else base
                for (ring in listOf(null, "obj.ring_of_wealth_5", "obj.ring_of_wealth_i5")) {
                    player.worn[Wearpos.Ring.slot] = ring?.let { InvObj(it) }
                    val wildDenominator = if (ring == "obj.ring_of_wealth_i5") (denominator / 2).coerceAtLeast(1) else denominator
                    assertEquals((100.0 * numerator / denominator).coerceAtMost(100.0),
                        roll.chanceFor(player, args(entry, SAFE)), 1e-12, "${entry.text("table")} status=$status ring=$ring safe")
                    assertEquals((100.0 * numerator / wildDenominator).coerceAtMost(100.0),
                        roll.chanceFor(player, args(entry, WILD)), 1e-12, "${entry.text("table")} status=$status ring=$ring Wilderness")
                }
            }
        }
    }

    @Test
    fun `migrated chance outcomes give exactly one clue item and preserve quest box transforms`() {
        mockkObject(Random.Default)
        try {
            for (entry in entries) {
                val player = player()
                val context = args(entry, SAFE)
                val roll = matchingRolls(entry).single()
                val table = RSPreRollTable("migrated clue", listOf(roll))
                every { Random.Default.nextDouble(any<Double>(), any<Double>()) } returns 0.0
                val reward = table.roll(player, context).items().single()
                assertEquals(entry.text("obj"), reward.obj)
                assertEquals(1..1, reward.count)
                assertNull(reward.transformObj(player))
                val transforms = entry.text("format") == "kotlin" ||
                    definition(entry).tertiary[entry.int("tertiary_index")].clueScrollBox
                QuestRequirements.install(QuestRequirementPolicy(
                    QuestRequirementMode.VirtualCompletions, setOf(X_MARKS_THE_SPOT_QUEST),
                ))
                assertEquals(if (transforms) "obj.league_clue_box_${entry.text("tier")}" else null,
                    reward.transformObj(player), entry.text("table"))
                QuestRequirements.install(QuestRequirementPolicy(QuestRequirementMode.RespectProgress))
                every { Random.Default.nextDouble(any<Double>(), any<Double>()) } returns 99.999
                val expected = if (entry.int("numerator") == entry.int("denominator")) 1 else 0
                assertEquals(expected, table.roll(player, context).items().size, entry.text("table"))
            }
        } finally {
            unmockkObject(Random.Default)
        }
    }

    @Test
    fun `actual success thresholds change after claiming rewards including tenfold superior rolls`() {
        mockkObject(Random.Default)
        try {
            for ((name, numerator) in listOf("Dark beast Drops" to 1, "Dagannoth Prime Drops" to 1, "Abhorrent spectre Drops" to 10)) {
                val entry = entries.single { it.text("table") == name && it.text("tier") == "hard" }
                assertEquals(numerator, entry.int("numerator"))
                val base = entry.int("denominator")
                val between = (100.0 * numerator / base + 100.0 * numerator / (base * 95 / 100)) / 2
                every { Random.Default.nextDouble(any<Double>(), any<Double>()) } returns between
                val player = player()
                val table = RSPreRollTable("changed claim state", matchingRolls(entry))
                for (status in 0..2) {
                    VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_hard", status)
                    val expected = if (status == 2) listOf(entry.text("obj")) else emptyList()
                    assertEquals(expected, table.roll(player, args(entry, SAFE)).items().map { it.obj }, "$name status=$status")
                }
            }
        } finally {
            unmockkObject(Random.Default)
        }
    }

    private fun definition(entry: JsonNode): TomlDropTableDef {
        val path = "/" + entry.text("file").substringAfter("src/main/resources/")
        val content = resource(path)
        val repaired = DropTableTomlTextFixer.hoistTableLevelKeys(content)
        assertEquals(repaired, DropTableTomlTextFixer.hoistTableLevelKeys(repaired), path)
        return mapper.readValue(repaired)
    }

    @Suppress("UNCHECKED_CAST")
    private fun table(entry: JsonNode): RSDropTable<Player, DropRollItem> =
        tables.getOrPut(entry.text("file") + entry.text("table")) {
            if (entry.text("format") == "toml") {
                DropTableTomlParser.parse(definition(entry), ContentDropTableTomlResolver(areas, mockk()), entry.text("file"))
            } else {
                Class.forName(entry.text("kotlin_class")).getField(entry.text("kotlin_field")).get(null) as RSDropTable<Player, DropRollItem>
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun matchingRolls(entry: JsonNode): List<ClueScrollChanceRollable> {
        val table = table(entry).tableEntries.last() as RSPreRollTable<Player, DropRollItem>
        return table.tableEntries.filterIsInstance<ClueScrollChanceRollable>().filter {
            it.rollable.roll(player(), args(entry, SAFE)).items().singleOrNull()?.obj == entry.text("obj")
        }
    }

    private fun args(entry: JsonNode, drop: CoordGrid) = ArgMap(
        KillRollContext.npc with Npc(table(entry).npcs.first(), drop),
        KillRollContext.dropCoords with drop,
        KillRollContext.areaChecker with areas,
    )

    private fun player() = Player().apply {
        coords = SAFE
        inv = Inventory.create("inv.inv")
        worn = Inventory.create("inv.worn")
    }

    private fun resource(path: String): String = checkNotNull(javaClass.getResourceAsStream(path)) { path }
        .bufferedReader().use { it.readText() }

    private fun JsonNode.text(name: String): String = get(name).asText()
    private fun JsonNode.int(name: String): Int = get(name).asInt()
    private fun RollResult<DropRollItem>.items(): List<DropRollItem> = when (this) {
        is RollResult.Nothing -> emptyList()
        is RollResult.Single -> listOf(result)
        is RollResult.ListOf -> results
    }

    companion object {
        private val SAFE = CoordGrid(3200, 3200)
        private val WILD = CoordGrid(3200, 3600)

        @JvmStatic
        @BeforeAll
        fun loadCache() { ServerCacheManager.init(241).close() }
    }
}
