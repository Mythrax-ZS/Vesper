package org.rsmod.content.skills.agility

import org.rsmod.api.player.hook.PlayerRunRecoveryHook
import org.rsmod.game.entity.Player

class AgilityRunRecoveryHook : PlayerRunRecoveryHook {
    override fun canRecover(player: Player): Boolean = player.agilityRecoveryStart == 0
}
