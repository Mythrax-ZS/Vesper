package org.rsmod.tools.wiki.dumping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.api.droptable.toml.DropTableTomlWriter
import org.rsmod.tools.wiki.dumping.wiki.DropsLineClueNotes
import org.rsmod.tools.wiki.dumping.wiki.WikiDropNoteClassifier
import org.rsmod.tools.wiki.dumping.wiki.WikiDropNotes
import org.rsmod.tools.wiki.dumping.wiki.WikiDropParser
import org.rsmod.tools.wiki.dumping.wiki.WikiQuestDropMode
import org.rsmod.tools.wiki.dumping.wiki.WikiQuestDropRequirement

class ClueRateExportTest {
    @Test
    fun `wiki template base rate survives injected combat reward footnotes in both exports`() {
        val parsed = WikiDropParser.parseDrops("""
            ==Drops==
            ===Tertiary===
            {{DropsLineClue|type=elite|rarity=1/65}}
        """.trimIndent()).single()
        assertEquals("1/65", parsed.rarity)
        assertTrue(parsed.wikiNotes.transformRate.any { "1/61" in it })
        val entry = clue("elite", denominator = 65, notes = parsed.wikiNotes)
        val table = DropTableTomlExporter.exportTable(spec(entry))
        assertEquals(65, table.tertiary.single().denominator)
        assertTrue(table.tertiary.single().clueRateModifiers)
        assertTrue(table.tertiary.single().clueScrollBox)
        val code = DropTableCodeGenerator.generate(spec(entry))
        assertTrue(code.contains("onBuilder { clueScrollRoll(\"obj.trail_clue_elite\", 65) }"))
        assertFalse(code.contains("outOf 61"))
        assertFalse(code.contains("clueScrollRoll(\"obj.trail_clue_elite\", 61"))
    }

    @Test
    fun `ring and combat footnotes produce one base-rate entry without legacy ring requirements`() {
        for (note in listOf(
            "The easy clue scroll drop rate increases to 1/64 when wearing a ring of wealth (i) in the Wilderness.",
            "The ring of wealth (i) doubles your chance of receiving clue scrolls in the Wilderness.",
        )) {
            val notes = DropsLineClueNotes.build("Clue scroll (easy)", "easy", "1/128")
                .merge(WikiDropNoteClassifier.classify(note, "Clue scroll (easy)"))
            val entry = clue("easy", 128, notes)
            val table = DropTableTomlExporter.exportTable(spec(entry))
            val chance = table.tertiary.single()
            assertEquals(128, chance.denominator)
            assertTrue(chance.clueRateModifiers)
            assertFalse(chance.requireRingOfWealth)
            assertFalse(chance.excludeRingOfWealth)
            assertFalse(chance.requireWilderness)
            val toml = DropTableTomlWriter.write(table)
            assertEquals(1, Regex("clue_rate_modifiers = true").findAll(toml).count())
            assertFalse(toml.contains("require_ring_of_wealth"))
            val code = DropTableCodeGenerator.generate(spec(entry))
            assertEquals(1, Regex("onBuilder \\{ clueScrollRoll").findAll(code).count())
            assertTrue(code.contains("clueScrollRoll(\"obj.trail_clue_easy\", 128)"))
            assertFalse(code.contains("wearingRingOfWealth"))
        }
    }

    @Test
    fun `ordinary and beginner clues without footnotes still retain their base denominators`() {
        for (tier in listOf("beginner", "easy", "medium", "hard", "elite", "master")) {
            val entry = clue(tier, 256)
            val chance = DropTableTomlExporter.exportTable(spec(entry)).tertiary.single()
            assertEquals(256, chance.denominator, tier)
            assertTrue(chance.clueRateModifiers, tier)
            assertTrue(chance.clueScrollBox, tier)
            assertTrue(DropTableCodeGenerator.generate(spec(entry))
                .contains("clueScrollRoll(\"obj.trail_clue_$tier\", 256)"))
        }
    }

    @Test
    fun `numerator quantity quest and boosted scope are preserved in dynamic clue output`() {
        val requirement = WikiQuestDropRequirement("quest_dragonslayer2", WikiQuestDropMode.RequiresCompleted)
        val entry = clue("elite", 128, WikiDropNotes(questRequirements = listOf(requirement)))
            .copy(weight = 2, quantity = "2-3", boosted = true)
        val chance = DropTableTomlExporter.exportTable(spec(entry)).tertiary.single()
        assertEquals(2, chance.numerator)
        assertEquals(128, chance.denominator)
        assertEquals("2..3", chance.count)
        assertEquals("quest_dragonslayer2", chance.quest)
        assertEquals("completed", chance.questMode)
        assertTrue(chance.boosted)
        val code = DropTableCodeGenerator.generate(spec(entry))
        assertTrue(code.contains("boosted {"))
        assertTrue(code.contains("clueScrollRoll(\"obj.trail_clue_elite\", 128, numerator = 2, item ="))
        assertTrue(code.contains("DropRollItem(\"obj.trail_clue_elite\", 2..3"))
        assertTrue(code.contains("player.hasCompletedQuest(\"quest_dragonslayer2\")"))
        assertTrue(code.contains("player.clueScrollTransformObj(\"obj.trail_clue_elite\")"))
        assertTrue(code.contains("import org.rsmod.content.drops.clueScrollTransformObj"))
    }

    @Test
    fun `multiple quest conditions and companion items survive Kotlin fallback`() {
        val entry = clue("hard", 512, WikiDropNotes(questRequirements = listOf(
            WikiQuestDropRequirement("quest_dragonslayer2", WikiQuestDropMode.RequiresCompleted),
            WikiQuestDropRequirement("quest_legends", WikiQuestDropMode.RequiresDuring),
        ))).copy(bonusDrops = listOf(ResolvedBonusDrop("obj.coins", count = 4)))
        assertFalse(DropTableTomlExporter.isSimpleForTomlExport(spec(entry)))
        val code = DropTableCodeGenerator.generate(spec(entry))
        assertTrue(code.contains("player.hasCompletedQuest(\"quest_dragonslayer2\") && player.isOnQuest(\"quest_legends\")"))
        assertTrue(code.contains("bonusDrops = listOf("))
        assertTrue(code.contains("DropRollItem(\"obj.coins\", 4)"))
        assertTrue(code.contains("clueScrollRoll(\"obj.trail_clue_hard\", 512, item ="))
    }

    @Test
    fun `pre-roll clue exports use the same dynamic modifier without baking CA rates`() {
        val entry = clue("hard", 100, DropsLineClueNotes.build("Clue scroll (hard)", "hard", "1/100"))
        val tableSpec = spec(entry).copy(preRoll = listOf(entry), tertiary = emptyList())
        val chance = DropTableTomlExporter.exportTable(tableSpec).preRoll.single()
        assertEquals(100, chance.denominator)
        assertTrue(chance.clueRateModifiers)
        assertTrue(DropTableCodeGenerator.generate(tableSpec).contains("clueScrollRoll(\"obj.trail_clue_hard\", 100)"))
    }

    @Test
    fun `singleton pre-roll clues normalize to base rate modifiers and import the Kotlin helper`() {
        val entry = clue("easy", 999).copy(weight = 7)
        val tableSpec = spec(entry).copy(
            tertiary = emptyList(),
            preRollSeparateRolls = listOf(SeparateRollSpec("Clues", 2, 128, listOf(entry), boosted = true)),
        )
        val toml = DropTableTomlExporter.exportTable(tableSpec)
        val chance = toml.preRoll.single()
        assertTrue(toml.preRollSeparateRolls.isEmpty())
        assertEquals(2, chance.numerator)
        assertEquals(128, chance.denominator)
        assertTrue(chance.clueRateModifiers)
        assertTrue(chance.boosted)
        val code = DropTableCodeGenerator.generate(tableSpec)
        assertTrue(code.contains("import org.rsmod.content.drops.clueScrollRoll"))
        assertTrue(code.contains("onBuilder { clueScrollRoll(\"obj.trail_clue_easy\", 128, numerator = 2) }"))
    }

    @Test
    fun `normalization retains preceding singleton rolls and never moves a clue before a weighted group`() {
        val entry = clue("easy", 128)
        val ordinary = ResolvedDropEntry("obj.coins", "20", weight = 1, wikiName = "Coins")
        val direct = ordinary.copy(obj = "obj.bones", outOf = 5)
        val tableSpec = spec(entry).copy(
            preRoll = listOf(direct),
            tertiary = emptyList(),
            preRollSeparateRolls = listOf(
                SeparateRollSpec("Coins", 1, 10, listOf(ordinary)),
                SeparateRollSpec("Clues", 1, 128, listOf(entry)),
            ),
        )
        val toml = DropTableTomlExporter.exportTable(tableSpec)
        assertEquals(listOf("obj.bones", "obj.coins", entry.obj), toml.preRoll.map { it.obj })
        assertEquals(listOf(5, 10, 128), toml.preRoll.map { it.denominator })
        assertEquals(listOf(false, false, true), toml.preRoll.map { it.clueRateModifiers })
        val mixed = tableSpec.copy(preRollSeparateRolls = listOf(
            SeparateRollSpec("Two choices", 1, 10, listOf(ordinary, direct)),
            tableSpec.preRollSeparateRolls.last(),
        ))
        assertTrue(DropTableTomlExporter.tomlExportBlockers(mixed).any { "preserve roll order" in it })
        val code = DropTableCodeGenerator.generate(mixed)
        assertTrue(code.indexOf("1 outOf 10 rolls") < code.indexOf("onBuilder { clueScrollRoll"))
    }

    @Test
    fun `ordinary non-clue drops keep ordinary chance semantics`() {
        val entry = ResolvedDropEntry("obj.dragon_spear", "1", weight = 1, outOf = 512, wikiName = "Dragon spear")
        val chance = DropTableTomlExporter.exportTable(spec(entry)).tertiary.single()
        assertEquals(512, chance.denominator)
        assertFalse(chance.clueRateModifiers)
        val code = DropTableCodeGenerator.generate(spec(entry))
        assertFalse(code.contains("clueScrollRoll"))
        assertTrue(code.contains("1 outOf 512"))
    }

    @Test
    fun `unresolved elite or master kill-step rewards cannot become unconditional monster rewards`() {
        val conditions = listOf(
            "The elite clue scroll and casket are only dropped when completing an elite clue scroll asking you to kill a bloodveld.",
            "Master clue scrolls and reward caskets are only dropped when completing a master clue scroll asking you to kill a spiritual mage.",
            "Only if you have an Treasure Trails/Full guide/Elite#Cryptic clues",
        )
        for (condition in conditions) for (casket in listOf(false, true)) {
            val entry = clue("elite", 1, WikiDropNotes(condition = listOf(condition))).let {
                if (casket) it.copy(obj = "obj.trail_reward_casket_elite", wikiName = "Reward casket (elite)") else it
            }
            val table = spec(entry).copy(guaranteed = listOf(entry), tertiary = emptyList())
            assertTrue(DropTableTomlExporter.tomlExportBlockers(table).any { "not TOML-simple" in it })
            val failure = assertThrows(IllegalArgumentException::class.java) { DropTableCodeGenerator.generate(table) }
            assertTrue(failure.message.orEmpty().contains("unresolved clue eligibility"))
            assertTrue(failure.message.orEmpty().contains(condition))
        }
    }

    @Test
    fun `unrecognised clue rate requirements fail explicitly rather than silently losing the restriction`() {
        val entry = clue("elite", 512, WikiDropNotes(transformRate = listOf(
            "The drop rate increases to 1/256 after an unsupported encounter-specific milestone.",
        )))
        assertFalse(DropTableTomlExporter.isSimpleForTomlExport(spec(entry)))
        val failure = assertThrows(IllegalArgumentException::class.java) { DropTableCodeGenerator.generate(spec(entry)) }
        assertTrue(failure.message.orEmpty().contains("unsupported clue rate condition"))
    }

    private fun clue(tier: String, denominator: Int, notes: WikiDropNotes = WikiDropNotes()) =
        ResolvedDropEntry("obj.trail_clue_$tier", "1", weight = 1, outOf = denominator,
            wikiName = "Clue scroll ($tier)", wikiNotes = notes)

    private fun spec(entry: ResolvedDropEntry) = GeneratedDropTableSpec(
        tableVarName = "testDropTable", tableIdentifier = "Test Drops", npcRscmKeys = listOf("npc.man"),
        guaranteed = emptyList(), main = emptyList(), tertiary = listOf(entry),
    )
}
