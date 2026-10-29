package org.rsmod.api.script

import jakarta.inject.Inject
import org.rsmod.api.player.events.interact.NpcOverrideEvent
import org.rsmod.api.player.events.interact.OpDefaultEvent
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

public class NpcOverrideScript @Inject constructor(private val interactions: NpcInteractions) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onProtectedEvent<NpcOverrideEvent>(OpDefaultEvent.ID) {
            interactions.dispatchOverride(this, it)
        }
    }
}
