package org.rsmod.content.other.treasuretrails

import dev.openrune.types.ItemServerType
import org.rsmod.api.player.hook.PlayerObjTakeValidateHook
import org.rsmod.game.entity.Player
import org.rsmod.game.obj.Obj

class CluePickupCheck : PlayerObjTakeValidateHook {
    override fun validateTake(player: Player, obj: Obj, objType: ItemServerType): String? {
        val tier = ClueOwnership.tier(objType) ?: return null
        if (!ClueOwnership.isBox(objType.internalName) &&
            ClueOwnership.count(player, tier, activeOnly = true) > 0
        ) {
            return "You already have a clue of this tier."
        }
        if (ClueOwnership.count(player, tier) + obj.count > ClueOwnership.capacity(player, tier)) {
            return "You cannot carry any more scrolls of this tier."
        }
        return null
    }
}
