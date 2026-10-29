package org.rsmod.api.combat

import com.google.inject.multibindings.Multibinder
import org.rsmod.api.combat.commons.npc.NpcAttackHook
import org.rsmod.api.combat.commons.npc.NpcAttackOverrideHook
import org.rsmod.plugin.module.PluginModule

internal class CombatModule : PluginModule() {
    override fun bind() {
        Multibinder.newSetBinder(binder(), NpcAttackHook::class.java)
        Multibinder.newSetBinder(binder(), NpcAttackOverrideHook::class.java)

        bind(org.rsmod.api.player.hit.modifier.NpcIncomingDamageReducer::class.java)
            .to(org.rsmod.api.combat.formulas.NpcIncomingDamageReducer::class.java)

        bind(org.rsmod.api.player.hit.modifier.PvpIncomingDamageReducer::class.java)
            .to(org.rsmod.api.combat.formulas.PvpIncomingDamageReducer::class.java)

        bindInstance<NvPCombat>()
        bindInstance<PvNCombat>()
        bindInstance<PvPCombat>()
    }
}
