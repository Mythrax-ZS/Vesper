package org.rsmod.content.skills.magic.spell.attacks.standard

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onWorldTypeChanged
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class ControlSpellStateScript @Inject constructor(private val state: ControlSpellState) : PluginScript() {
    override fun ScriptContext.startup() {
        onEvent<NpcStateEvents.Delete> { state.invalidateNpc(npc) }
        onEvent<NpcStateEvents.Respawn> { state.invalidateNpc(npc) }
        onEvent<GameLifecycle.StartCycle> { state.tick() }
        onPlayerLogin { state.cleanup(player) }
        onPlayerLogout { state.cleanup(player) }
        onWorldTypeChanged { state.cleanup(player) }
    }
}
