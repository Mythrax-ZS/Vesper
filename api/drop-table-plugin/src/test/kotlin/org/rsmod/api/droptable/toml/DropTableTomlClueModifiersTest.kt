package org.rsmod.api.droptable.toml

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.dataformat.toml.TomlFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DropTableTomlClueModifiersTest {
    private val mapper =
        ObjectMapper(TomlFactory())
            .registerKotlinModule()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private val toml =
        """
        id = "Scoped clue rates"
        npcs = ["npc.vorkath", "npc.vorkath_quest"]

        [[pre_roll]]
        numerator = 2
        denominator = 128
        obj = "obj.trail_clue_easy"
        count = "2..3"
        clue_rate_modifiers = true
        clue_scroll_box = true
        npcs = ["npc.vorkath"]
        boosted = true
        quest = "quest_dragonslayer2"
        quest_mode = "completed"

        [[tertiary]]
        numerator = 1
        denominator = 65
        obj = "obj.trail_clue_elite"
        clue_rate_modifiers = true
        clue_scroll_box = true
        npcs = ["npc.vorkath_quest", "npc.vorkath"]

        [[tertiary]]
        numerator = 1
        denominator = 5000
        obj = "obj.pet"
        """.trimIndent()

    @Test
    fun `reader keeps clue rate flags and NPC filters local to their chance entries`() {
        val def = mapper.readValue<TomlDropTableDef>(toml)

        assertEquals(listOf("npc.vorkath", "npc.vorkath_quest"), def.npcs)
        assertTrue(def.preRoll.single().clueRateModifiers)
        assertEquals(listOf("npc.vorkath"), def.preRoll.single().npcs)
        assertTrue(def.tertiary.first().clueRateModifiers)
        assertEquals(listOf("npc.vorkath_quest", "npc.vorkath"), def.tertiary.first().npcs)
        assertFalse(def.tertiary.last().clueRateModifiers)
        assertTrue(def.tertiary.last().npcs.isEmpty())
    }

    @Test
    fun `writer roundtrips base rates filters and existing chance hooks without expanding entries`() {
        val def = mapper.readValue<TomlDropTableDef>(toml)
        val written = DropTableTomlWriter.write(def)
        val reread = mapper.readValue<TomlDropTableDef>(written)

        assertEquals(def, reread)
        assertEquals(128, reread.preRoll.single().denominator)
        assertEquals(65, reread.tertiary.first().denominator)
        assertEquals(1, reread.preRoll.size)
        assertEquals(2, reread.tertiary.size)
        assertEquals(2, Regex("^clue_rate_modifiers = true$", RegexOption.MULTILINE).findAll(written).count())
        assertEquals(3, Regex("^npcs = ", RegexOption.MULTILINE).findAll(written).count())
        assertFalse("require_ring_of_wealth" in written)
        assertFalse("require_wilderness" in written)
    }

    @Test
    fun `missing and explicit disabled flags roundtrip without adding filters or modifier keys`() {
        val def = mapper.readValue<TomlDropTableDef>("""
            id = "Ordinary rates"
            npcs = ["npc.vorkath"]

            [[pre_roll]]
            numerator = 1
            denominator = 128
            obj = "obj.trail_clue_easy"
            clue_rate_modifiers = false
            npcs = []

            [[tertiary]]
            numerator = 1
            denominator = 65
            obj = "obj.trail_clue_elite"
        """.trimIndent())
        val written = DropTableTomlWriter.write(def)

        assertEquals(def, mapper.readValue<TomlDropTableDef>(written))
        assertTrue((def.preRoll + def.tertiary).all { !it.clueRateModifiers && it.npcs.isEmpty() })
        assertFalse("clue_rate_modifiers" in written)
        assertEquals(1, Regex("^npcs = ", RegexOption.MULTILINE).findAll(written).count())
    }

    @Test
    fun `hoisting keeps existing Brimstone flags at root despite later entry NPC filters`() {
        val expected = mapper.readValue<TomlDropTableDef>(toml).copy(
            brimstoneKeyRoll = true,
            brimstoneKeyRollKonarBonus = true,
        )
        val written = DropTableTomlWriter.write(expected)
        val hoisted = DropTableTomlTextFixer.hoistTableLevelKeys(written)

        assertEquals(expected, mapper.readValue<TomlDropTableDef>(hoisted))
        val tree = mapper.readTree(hoisted)
        assertTrue(tree["brimstone_key_roll"].booleanValue())
        assertTrue(tree["brimstone_key_roll_konar_bonus"].booleanValue())
        for (section in listOf("pre_roll", "tertiary")) {
            for (entry in tree[section]) {
                assertFalse(entry.has("brimstone_key_roll"))
                assertFalse(entry.has("brimstone_key_roll_konar_bonus"))
            }
        }
        assertEquals(hoisted, DropTableTomlTextFixer.hoistTableLevelKeys(hoisted))
    }

    @Test
    fun `legacy misplaced Brimstone flags are hoisted without changing entry NPC filters`() {
        val legacy = toml + "\nbrimstone_key_roll = true\nbrimstone_key_roll_konar_bonus = true\n"
        assertThrows(UnrecognizedPropertyException::class.java) {
            mapper.readValue<TomlDropTableDef>(legacy)
        }
        val expected = mapper.readValue<TomlDropTableDef>(toml).copy(
            brimstoneKeyRoll = true,
            brimstoneKeyRollKonarBonus = true,
        )
        val hoisted = DropTableTomlTextFixer.hoistTableLevelKeys(legacy)

        assertEquals(expected, mapper.readValue<TomlDropTableDef>(hoisted))
        assertEquals(hoisted, DropTableTomlTextFixer.hoistTableLevelKeys(hoisted))
        assertEquals(1, Regex("^brimstone_key_roll = true$", RegexOption.MULTILINE).findAll(hoisted).count())
        assertEquals(1, Regex("^brimstone_key_roll_konar_bonus = true$", RegexOption.MULTILINE).findAll(hoisted).count())
    }
}
