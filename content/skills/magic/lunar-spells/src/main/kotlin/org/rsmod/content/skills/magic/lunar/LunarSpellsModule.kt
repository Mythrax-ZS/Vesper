package org.rsmod.content.skills.magic.lunar

import org.rsmod.api.death.PlayerDeathCleanupHook
import org.rsmod.api.spells.NonAttackSpellProvider
import org.rsmod.plugin.module.PluginModule

class LunarSpellsModule : PluginModule() {
    override fun bind() {
        bindInstance<VengeanceState>()
        newSetBinding<VengeanceRestrictedArea>()
        newSetBinding<VengeanceAidPermission>()
        addSetBinding<PlayerDeathCleanupHook>(VengeanceState::class.java)
        addSetBinding<NonAttackSpellProvider>(VengeanceSpellProvider::class.java)
    }
}

class VengeanceSpellProvider : NonAttackSpellProvider {
    override val spells: Set<String> = setOf(VengeanceSpells.SELF, VengeanceSpells.OTHER)
}
