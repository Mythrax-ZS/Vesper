package org.rsmod.content.skills.agility

import org.rsmod.api.player.hook.PlayerObjTakeValidateHook
import org.rsmod.api.player.hook.PlayerRunRecoveryHook
import org.rsmod.plugin.module.PluginModule

class AgilityModule : PluginModule() {
    override fun bind() {
        addSetBinding<PlayerObjTakeValidateHook>(AgilityMarkTakeHook::class.java)
        addSetBinding<PlayerRunRecoveryHook>(AgilityRunRecoveryHook::class.java)
    }
}
