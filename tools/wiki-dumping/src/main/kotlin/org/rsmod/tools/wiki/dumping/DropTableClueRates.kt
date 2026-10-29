package org.rsmod.tools.wiki.dumping

internal data class RingOfWealthClueRate(
    val wealthDenominator: Int,
    val requiresWilderness: Boolean,
)

private val RING_OF_WEALTH_NOTE = Regex("""ring\s*of\s*wealth""", RegexOption.IGNORE_CASE)
private val WEALTH_CLUE_RATE =
    Regex(
        """(?:increases?\s*to|(?:is\s*)?(?:increased|changed)\s*to|(?:drop\s*rate\s*(?:of|is(?:\s*increased\s*to)?)|rarity\s*changes?\s*to)|have\s+a\s+drop\s+rate\s+of)\s*(?:1\s*/\s*)?(\d+)""",
        RegexOption.IGNORE_CASE,
    )

internal fun parseRingOfWealthClueRate(note: String): RingOfWealthClueRate? {
    if (!RING_OF_WEALTH_NOTE.containsMatchIn(note)) {
        return null
    }
    val denominator = WEALTH_CLUE_RATE.find(note)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return RingOfWealthClueRate(
        wealthDenominator = denominator,
        requiresWilderness = note.contains("wilderness", ignoreCase = true),
    )
}

private val EXACT_RARITY_NOTE = Regex("""exact\s+rarity\s+is""", RegexOption.IGNORE_CASE)

internal fun isHandledTransformRateNote(note: String): Boolean =
    note.contains("Combat Achievements", ignoreCase = true) ||
        parseRingOfWealthClueRate(note) != null ||
        (RING_OF_WEALTH_NOTE.containsMatchIn(note) &&
            Regex("""doubl(?:e|es|ed|ing)""", RegexOption.IGNORE_CASE).containsMatchIn(note)) ||
        EXACT_RARITY_NOTE.containsMatchIn(note)

/** Only ordinary clue scroll rewards receive the shared runtime rate modifiers. */
internal fun ResolvedDropEntry.isClueScrollRate(): Boolean =
    !obj.contains("casket") && (
        Regex("""^clue scroll \((beginner|easy|medium|hard|elite|master)\)$""", RegexOption.IGNORE_CASE)
            .matches(wikiName.trim()) || obj.removePrefix("obj.").startsWith("trail_clue_")
    )

/** No placeholder can safely encode an unfinished kill-step or other clue eligibility rule. */
internal fun ResolvedDropEntry.unresolvedClueConditions(): List<String> {
    val clueReward = isClueScrollRate() ||
        (obj.contains("casket") && wikiNotes.condition.any { it.contains("clue", ignoreCase = true) })
    if (!clueReward) return emptyList()
    return wikiNotes.condition.filterNot(::isHandledTransformRateNote)
}

/** Transform-rate wiki footnotes that still need manual implementation in Kotlin output. */
internal fun ResolvedDropEntry.unhandledTransformRateNotes(): List<String> =
    wikiNotes.transformRate.filterNot(::isHandledTransformRateNote)
