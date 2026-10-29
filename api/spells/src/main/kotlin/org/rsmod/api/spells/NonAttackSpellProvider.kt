package org.rsmod.api.spells

/** Spells whose content plugin owns interaction bindings outside the attack dispatcher. */
public interface NonAttackSpellProvider {
    public val spells: Set<String>
}
