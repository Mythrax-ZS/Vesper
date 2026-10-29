package org.rsmod.api.death

import org.rsmod.game.entity.Player

/**
 * Grants a current PvP context shared by both participants. Providers must use authoritative
 * world/area or active-match state, including instance identity; client options and setup variables
 * are insufficient. A grant never bypasses the other [PvPAttackValidateHook] restrictions.
 */
public fun interface PvPContextPermission {
    public fun permits(attacker: Player, target: Player): Boolean
}
