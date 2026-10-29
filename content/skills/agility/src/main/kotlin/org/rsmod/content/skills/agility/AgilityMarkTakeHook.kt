package org.rsmod.content.skills.agility

import dev.openrune.types.ItemServerType
import jakarta.inject.Inject
import org.rsmod.api.player.hook.PlayerObjTakeValidateHook
import org.rsmod.game.entity.Player
import org.rsmod.game.obj.Obj

class AgilityMarkTakeHook @Inject constructor(private val marks: AgilityMarks) : PlayerObjTakeValidateHook {
    override fun validateTake(player: Player, obj: Obj, objType: ItemServerType): String? =
        if (objType.isType(AgilityMarks.MARK)) marks.validateTake(player, obj) else null

    override fun onTake(player: Player, obj: Obj, objType: ItemServerType) {
        if (objType.isType(AgilityMarks.MARK)) marks.taken(player, obj)
    }
}
