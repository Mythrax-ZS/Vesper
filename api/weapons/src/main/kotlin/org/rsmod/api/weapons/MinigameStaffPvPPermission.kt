package org.rsmod.api.weapons

import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.game.entity.Player

/**
 * A minigame's permission for this restricted built-in spell against this opponent, checked on
 * every attempted attack. Implementations must consult current match membership, combat phase,
 * opponent eligibility and accepted weapon/magic rules. Location or client UI variables alone
 * cannot grant permission. This does not bypass general PvP validation.
 */
public fun interface MinigameStaffPvPPermission {
    public fun permits(attacker: Player, target: Player, attack: CombatAttack.Staff): Boolean
}
