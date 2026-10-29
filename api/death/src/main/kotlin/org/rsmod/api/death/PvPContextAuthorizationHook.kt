package org.rsmod.api.death

import jakarta.inject.Inject
import org.rsmod.api.player.isValidTarget
import org.rsmod.game.entity.Player

public class PvPContextAuthorizationHook
@Inject
constructor(private val permissions: Set<PvPContextPermission>) : PvPAttackValidateHook {
    override fun validate(attacker: Player, target: Player): PvPAttackValidateResult {
        if (attacker === target || !attacker.isValidTarget() || !target.isValidTarget()) {
            return PvPAttackValidateResult.Deny("You can't attack this player.")
        }
        if (attacker.worldType != target.worldType) {
            return PvPAttackValidateResult.Deny("You can't attack players in a different game mode.")
        }
        if (attacker.coords.level != target.coords.level) {
            return PvPAttackValidateResult.Deny("You can't reach that player.")
        }
        return if (permissions.any { it.permits(attacker, target) }) {
            PvPAttackValidateResult.Pass
        } else {
            PvPAttackValidateResult.Deny("You can't attack players here.")
        }
    }
}
