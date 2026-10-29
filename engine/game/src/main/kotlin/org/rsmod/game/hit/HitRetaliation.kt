package org.rsmod.game.hit

/** Damage after mitigation but before health truncation, and its original source lifetime. */
public data class HitRetaliation(
    public val damage: Int,
    public val reflected: Boolean,
    public val source: HitSourceSnapshot?,
)
