package org.rsmod.content.skills.magic.lunar

import dev.openrune.types.aconverted.interf.IfButtonOp
import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.script.onApPlayerT
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onWorldTypeChanged
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class VengeanceScript @Inject constructor(
    private val spells: MagicSpellRegistry,
    private val vengeance: VengeanceSpells,
    private val state: VengeanceState,
) : PluginScript() {
    override fun ScriptContext.startup() {
        val self = spells.allSpells().first { it.obj.isType(VengeanceSpells.SELF) }
        val other = spells.allSpells().first { it.obj.isType(VengeanceSpells.OTHER) }
        onIfOverlayButton(self.component) {
            if (it.op == IfButtonOp.Op1) vengeance.castSelf(this)
        }
        onApPlayerT(other.component) {
            vengeance.castOther(this, it.target)
            clearPendingAction()
        }
        onEvent<GameLifecycle.StartCycle> { state.tick() }
        onPlayerLogin { state.cleanup(player) }
        onPlayerLogout { state.cleanup(player) }
        onEvent<SessionStateEvent.Delete> { state.cleanup(player) }
        onWorldTypeChanged { state.cleanup(player) }
    }
}
