package org.rsmod.content.other.vesper

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.script.onCommand
import org.rsmod.api.server.config.ServerConfig
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class VesperHelpScript @Inject constructor(private val config: ServerConfig) : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand("vesper") {
            desc = "Show Vesper help"
            cheat {
                player.mes("${config.name} - Beyond the Old School.")
                player.mes("Type ::ultrawide for RuneLite display setup tips.")
                player.mes("You can adjust game preferences in the Settings tab.")
            }
        }

        onCommand("ultrawide") {
            desc = "Show RuneLite display setup tips"
            cheat {
                player.mes("In game, select Display mode: Resizable - Modern layout.")
                player.mes("Maximize or resize your client to fit your monitor.")
                player.mes("In RuneLite, start with Stretched Mode off for native rendering.")
                player.mes("For larger UI, enable Stretched Mode and adjust Resizable Scaling.")
                player.mes("Camera zoom and GPU draw distance are separate settings.")
            }
        }
    }
}
