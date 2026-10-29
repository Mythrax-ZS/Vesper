package org.rsmod.content.areas.wilderness

import jakarta.inject.Inject
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

public class BlightedRunePackScript @Inject constructor(private val sync: BlightedRunePackSync) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { sync.synchronize(player) }
    }
}
