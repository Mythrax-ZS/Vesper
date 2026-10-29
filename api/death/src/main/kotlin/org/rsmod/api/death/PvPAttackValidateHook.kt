package org.rsmod.api.death

import org.rsmod.game.entity.Player

/** [PvPAttackValidateResult.Pass] is an abstention, not a grant of a PvP context. */
public fun interface PvPAttackValidateHook {
    public fun validate(attacker: Player, target: Player): PvPAttackValidateResult
}

public sealed class PvPAttackValidateResult {
    public data object Pass : PvPAttackValidateResult()

    public data class Deny(val message: String) : PvPAttackValidateResult()
}
