package org.rsmod.content.other.treasuretrails

import org.rsmod.api.player.hook.NpcOpOverrideHook
import org.rsmod.api.player.hook.PlayerObjTakeValidateHook
import org.rsmod.api.player.hook.SpadeDigHook
import org.rsmod.plugin.module.PluginModule

class TreasureTrailsModule : PluginModule() {
    override fun bind() {
        addSetBinding<SpadeDigHook>(BeginnerTrailScript::class.java)
        addSetBinding<NpcOpOverrideHook>(BeginnerTrailScript::class.java)
        addSetBinding<PlayerObjTakeValidateHook>(CluePickupCheck::class.java)
    }
}
