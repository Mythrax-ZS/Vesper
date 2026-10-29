package org.rsmod.content.other.treasuretrails

import dev.openrune.definition.type.widget.IfEvent
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.aconverted.interf.IfButtonOp
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.WeakHashMap
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

internal const val CLUE_HELPER = "interface.helper_generic"
internal const val CLUE_HELPER_TARGET = "component.toplevel_osrs_stretch:helper_content"
internal const val CLUE_HELPER_CONTENT = "component.helper_generic:content"
internal const val CLUE_HELPER_INIT = "clientscript.[clientscript,cluehelper_infobox_init]"
internal const val CLUE_HELPER_RESET = "clientscript.[clientscript,cluehelper_infobox_reset_data]"

// Revision 241 retains this native script's older gameval name; four integer arguments.
internal const val CLUE_HELPER_HOTCOLD = "clientscript.[clientscript,script6473]"
internal var Player.nativeClueHelperRow by intVarp("varp.cluehelper_infobox_clue")
internal var Player.nativeClueInfoboxEnabled by boolVarBit("varbit.option_cluehelper_infobox_enabled")

/** Native clue-helper presentation only. Nothing in this class grants or progresses a clue. */
@Singleton
class BeginnerClueHelper @Inject constructor(private val events: EventBus) : PluginScript() {
    private data class Session(
        var row: Int = -1,
        var displayed: Int = -1,
        var closed: Boolean = false,
        var generation: Int = -1,
        var completed: Int = -1,
    )
    private val sessions = WeakHashMap<Player, Session>()

    override fun ScriptContext.startup() {
        onPlayerLogin {
            if (player.nativeClueHelperRow == 0) player.nativeClueHelperRow = -1
            show(player)
        }
        onPlayerLogout { clear(player) }
        onEvent<GameLifecycle.LateCycle> { synchronizeAll() }
        onIfOverlayButton(CLUE_HELPER_CONTENT) {
            when (it.op) {
                IfButtonOp.Op6 -> reset(player)
                IfButtonOp.Op7 -> close(player)
                IfButtonOp.Op8 -> disable(player)
                else -> Unit
            }
        }
    }

    internal fun show(player: Player) {
        val session = sessions.getOrPut(player) { Session() }
        session.closed = false
        synchronize(player, session, explicit = true)
    }

    internal fun synchronizeAll() {
        // Only players who have opened a supported clue are observed, never the whole player list.
        for (player in sessions.keys.toList()) sessions[player]?.let { synchronize(player, it) }
    }

    private fun synchronize(player: Player, session: Session, replay: Boolean = true, explicit: Boolean = false) {
        val current = player.vars["varp.cluehelper_infobox_clue"]
        if (!explicit && session.row >= 0 && current != session.row && current != -1) {
            // A different helper has claimed the shared native presentation; leave it alone.
            sessions.remove(player)
            return
        }
        val row = player.beginnerHelperRow()
        if (row == null) {
            clear(player)
            return
        }
        if (session.generation != player.beginnerGeneration || session.completed != player.beginnerCompletedSteps) {
            session.displayed = -1
            session.closed = false
            session.generation = player.beginnerGeneration
            session.completed = player.beginnerCompletedSteps
        }
        if (session.row != row) {
            session.row = row
            session.closed = false
        }
        val enabled = player.vars["varbit.option_cluehelper_enabled"] != 0
        val nativeRow = if (enabled) row else -1
        if (player.vars["varp.cluehelper_infobox_clue"] != nativeRow) {
            player.nativeClueHelperRow = nativeRow
        }
        val infobox = enabled && player.vars["varbit.option_cluehelper_infobox_enabled"] != 0
        if (!infobox || session.closed) {
            closeInfobox(player, session)
            return
        }
        if (session.displayed == row && player.ui.containsOverlay(CLUE_HELPER)) return
        player.ifOpenOverlay(CLUE_HELPER, CLUE_HELPER_TARGET, events)
        player.ifSetEvents(CLUE_HELPER_CONTENT, -1..-1, IfEvent.Op6, IfEvent.Op7, IfEvent.Op8)
        player.runClientScript(CLUE_HELPER_INIT.asRSCM(RSCMType.CLIENTSCRIPT), row)
        session.displayed = row
        if (replay) replayReading(player)
    }

    internal fun measured(player: Player, previousCoord: Int, previousDistance: Int) {
        val session = sessions.getOrPut(player) { Session() }
        synchronize(player, session, replay = false)
        if (sessions[player] !== session) return
        if (session.displayed != player.beginnerHotColdTarget || !player.ui.containsOverlay(CLUE_HELPER)) return
        if (player.beginnerHotColdCoord == 0 || player.beginnerHotColdDistance <= 0) return
        player.runClientScript(CLUE_HELPER_HOTCOLD.asRSCM(RSCMType.CLIENTSCRIPT),
            player.beginnerHotColdCoord, if (previousCoord == 0) -1 else previousCoord,
            player.beginnerHotColdDistance - 1, if (previousCoord == 0) -1 else previousDistance - 1)
    }

    private fun replayReading(player: Player) {
        if (player.beginnerRow != RELDO_ROW.asRSCM() || player.beginnerHotColdCoord == 0 ||
            player.beginnerHotColdDistance <= 0) return
        // Reconnect can reconstruct the last observation, not the client's discarded full history.
        player.runClientScript(CLUE_HELPER_HOTCOLD.asRSCM(RSCMType.CLIENTSCRIPT),
            player.beginnerHotColdCoord, -1, player.beginnerHotColdDistance - 1, -1)
    }

    internal fun reset(player: Player) {
        val session = sessions[player] ?: return
        if (session.displayed < 0 || !player.ui.containsOverlay(CLUE_HELPER) || player.nativeClueHelperRow != session.row) return
        player.runClientScript(CLUE_HELPER_INIT.asRSCM(RSCMType.CLIENTSCRIPT), session.displayed)
        replayReading(player)
    }

    internal fun close(player: Player) {
        val session = sessions[player] ?: return
        session.closed = true
        closeInfobox(player, session)
    }

    internal fun disable(player: Player) {
        val session = sessions[player] ?: return
        if (player.nativeClueHelperRow != session.row) return
        player.nativeClueInfoboxEnabled = false
        synchronize(player, session)
    }

    internal fun clear(player: Player) {
        val session = sessions.remove(player) ?: return
        if (session.displayed >= 0 && player.ui.containsOverlay(CLUE_HELPER) &&
            player.vars["varp.cluehelper_infobox_clue"] == session.row) {
            player.runClientScript(CLUE_HELPER_RESET.asRSCM(RSCMType.CLIENTSCRIPT))
        }
        closeInfobox(player, session)
        // Do not clear another feature's helper row after it takes ownership of the native slot.
        if (player.vars["varp.cluehelper_infobox_clue"] == session.row) {
            player.nativeClueHelperRow = -1
        }
    }

    private fun closeInfobox(player: Player, session: Session) {
        if (session.displayed >= 0 && player.ui.containsOverlay(CLUE_HELPER) &&
            player.vars["varp.cluehelper_infobox_clue"] in listOf(session.row, -1)) {
            player.ifCloseOverlay(CLUE_HELPER, events)
        }
        session.displayed = -1
    }
}

internal fun Player.beginnerHelperRow(): Int? {
    if (!beginnerState().valid || inv.physicalCount(BEGINNER_SCROLL) != 1) return null
    return when (val clue = BeginnerClues.get(beginnerRow)) {
        is BeginnerClue.Map, is BeginnerClue.Anagram -> clue.rowId
        is BeginnerClue.Cryptic -> when {
            clue.charlie -> BeginnerCharlieTasks.get(beginnerCharlieTask)?.rowId ?: clue.rowId
            clue.hotCold && beginnerHotColdDistance > 0 ->
                BeginnerHotColdTargets.get(beginnerHotColdTarget)?.rowId ?: clue.rowId
            else -> clue.rowId
        }
        null -> null
    }
}
