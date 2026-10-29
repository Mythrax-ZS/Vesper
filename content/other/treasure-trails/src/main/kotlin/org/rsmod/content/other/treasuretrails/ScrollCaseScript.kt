package org.rsmod.content.other.treasuretrails

import dev.openrune.rscm.RSCM.asRSCM
import org.rsmod.api.invtx.delete
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onOpHeld1
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class ScrollCaseScript : PluginScript() {
    override fun ScriptContext.startup() {
        for (tier in ClueTier.entries) {
            for (size in listOf("minor", "major")) {
                bindCase("scroll_case_${tier.name.lowercase()}_$size")
            }
        }
        bindCase("scroll_case_mimic")
    }

    private fun ScriptContext.bindCase(key: String) {
        onOpHeld1("obj.$key") {
            if (player.vars["varbit.$key"] != 0) {
                mes("You have already increased your scroll capacity with this case.")
            } else if (player.consumeScrollCase(it.slot, key)) {
                mes("Your scroll capacity has increased.")
            }
        }
    }
}

internal fun Player.consumeScrollCase(slot: Int, key: String): Boolean {
    val obj = "obj.$key".asRSCM()
    val variable = "varbit.$key"
    if (vars[variable] != 0 || inv[slot]?.id != obj) return false
    val transaction = invTransaction(inv) {
        delete(select(inv), obj, 1, slot = slot)
    }
    if (transaction.failure) return false
    VarPlayerIntMapSetter.set(this, variable, 1)
    return true
}
