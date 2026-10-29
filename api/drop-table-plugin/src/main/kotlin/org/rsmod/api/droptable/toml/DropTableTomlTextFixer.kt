package org.rsmod.api.droptable.toml

/**
 * Fixes TOML layout bugs where table-level keys were emitted after `[[main.*]]` headers and
 * therefore parsed as fields on the last main/separate-roll entry instead of the drop table root.
 */
public object DropTableTomlTextFixer {
    private val BRIMSTONE_KEY_ROLL = Regex("""^\s*brimstone_key_roll\s*=\s*true\s*$""")
    private val BRIMSTONE_KONAR = Regex("""^\s*brimstone_key_roll_konar_bonus\s*=\s*true\s*$""")

    public fun hoistTableLevelKeys(content: String): String {
        var brimstoneKeyRoll = false
        var brimstoneKonarBonus = false
        val cleaned =
            content.lineSequence().filter { line ->
                when {
                    BRIMSTONE_KEY_ROLL.matches(line) -> {
                        brimstoneKeyRoll = true
                        false
                    }
                    BRIMSTONE_KONAR.matches(line) -> {
                        brimstoneKonarBonus = true
                        false
                    }
                    else -> true
                }
            }.dropWhile { it.isBlank() }.toMutableList()

        if (!brimstoneKeyRoll && !brimstoneKonarBonus) {
            return content
        }

        val hoisted = buildList {
            if (brimstoneKeyRoll) {
                add("brimstone_key_roll = true")
            }
            if (brimstoneKonarBonus) {
                add("brimstone_key_roll_konar_bonus = true")
            }
        }
        // Entry-scoped NPC filters must never become the insertion point for root settings.
        cleaned.addAll(0, hoisted + "")
        return cleaned.joinToString("\n").trimEnd() + "\n"
    }
}
