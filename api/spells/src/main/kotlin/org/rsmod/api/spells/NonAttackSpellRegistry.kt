package org.rsmod.api.spells

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.combat.commons.magic.MagicSpell

@Singleton
public class NonAttackSpellRegistry
@Inject
constructor(providers: Set<@JvmSuppressWildcards NonAttackSpellProvider>) {
    private val symbols: Set<String>

    init {
        val claims = providers.flatMap { it.spells }
        require(claims.all { it.startsWith("obj.") }) { "Spell claims must use obj gameval symbols." }
        val duplicates = claims.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Multiple non-attack spell providers claim: $duplicates" }
        symbols = claims.toSet()
    }

    public operator fun contains(spell: MagicSpell): Boolean =
        symbols.any { spell.obj.isType(it) }
}
