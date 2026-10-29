package org.rsmod.api.bosses.spec

import org.rsmod.game.hit.HitDefenceType

public data class HitMitigation(
    public val defenceType: HitDefenceType? = null,
    public val equipment: Boolean = true,
    public val demonWard: Boolean = true,
) {
    public companion object {
        public val NONE: HitMitigation = HitMitigation(equipment = false, demonWard = false)
    }
}
