package org.rsmod.api.weapons

import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.death.PvPAttackValidateResult
import org.rsmod.game.entity.Player

/**
 * Optional preflight for a built-in staff attack, before its combat clock or effects change.
 * Custom PvP dispatchers must honor this check before invoking the weapon's attack handler.
 */
public interface MagicWeaponPvPValidator {
    public fun validatePvPAttack(
        attacker: Player,
        target: Player,
        attack: CombatAttack.Staff,
    ): PvPAttackValidateResult
}
