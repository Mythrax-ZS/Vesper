package org.rsmod.api.combat.commons.types

import org.rsmod.game.hit.HitDefenceType

public enum class MeleeAttackType {
    Stab,
    Slash,
    Crush;

    public fun toHitDefenceType(): HitDefenceType = when (this) {
        Stab -> HitDefenceType.Stab
        Slash -> HitDefenceType.Slash
        Crush -> HitDefenceType.Crush
    }

    public companion object {
        public fun from(type: AttackType?): MeleeAttackType? =
            when (type) {
                AttackType.Stab -> Stab
                AttackType.Slash -> Slash
                AttackType.Crush -> Crush
                else -> null
            }
    }
}
