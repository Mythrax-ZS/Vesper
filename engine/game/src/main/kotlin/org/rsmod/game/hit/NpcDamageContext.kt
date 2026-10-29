package org.rsmod.game.hit

public enum class HitDefenceType {
    Stab,
    Slash,
    Crush,
    Ranged,
    Magic,
}

public data class NpcDamageContext(
    public val sourceType: Int,
    public val defenceType: HitDefenceType?,
    public val equipment: Boolean = true,
    public val demonWard: Boolean = true,
)
