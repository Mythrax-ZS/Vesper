package org.rsmod.content.other.treasuretrails

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.NpcServerType
import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.player.hook.NpcOpOverrideHook
import org.rsmod.api.player.hook.SpadeDigHook
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.script.onOpHeld1
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class BeginnerTrailScript @Inject constructor(
    private val random: GameRandom,
    private val helper: BeginnerClueHelper,
) :
    PluginScript(), SpadeDigHook, NpcOpOverrideHook {
    private data class PendingDig(val state: BeginnerClueState, val hotColdTarget: Int)
    private val pendingDigs = WeakHashMap<GameCoroutine, PendingDig>()
    override fun ScriptContext.startup() {
        BeginnerClues.ALL
        BeginnerCharlieTasks.ALL
        BeginnerHotColdTargets.ALL
        onOpHeld1(BEGINNER_BOX) { event ->
            val result = player.openBeginnerBox(event.slot, nextRow(), rollLength())
            if (result == null) readClue() else report(result)
        }
        onOpHeld1(BEGINNER_SCROLL) {
            val result = player.initializeBeginnerScroll(nextRow(), rollLength())
            if (result == null) readClue() else report(result)
        }
        onOpHeld1(BEGINNER_DEVICE) { event ->
            if (player.inv[event.slot] !== event.obj) return@onOpHeld1
            if (player.carriesActiveClue() && player.beginnerRow == RELDO_ROW.asRSCM()) {
                val result = prepareHotCold()
                if (result != null) {
                    report(result)
                    return@onOpHeld1
                }
            }
            val previousCoord = player.beginnerHotColdCoord
            val previousDistance = player.beginnerHotColdDistance
            player.feelBeginnerDevice(event.slot, event.obj)?.let { message ->
                mes(message)
                if (player.beginnerHelperRow() == player.beginnerHotColdTarget) {
                    helper.measured(player, previousCoord, previousDistance)
                }
            }
        }
    }

    override fun claims(player: Player): Boolean {
        if (!player.carriesActiveClue()) return false
        return when (val clue = BeginnerClues.get(player.beginnerRow)) {
            is BeginnerClue.Map -> clue.matches(player.coords)
            is BeginnerClue.Cryptic -> clue.hotCold && player.atBeginnerHotColdTarget()
            else -> false
        }
    }

    override suspend fun ProtectedAccess.dig() {
        val pending = pendingDigs.remove(coroutine) ?: return
        if (player.beginnerState() != pending.state || player.beginnerHotColdTarget != pending.hotColdTarget) return
        if (!claims(player)) return
        completeStep()
    }

    override suspend fun ProtectedAccess.beforeDig(): Boolean {
        if (!claims(player)) return false
        pendingDigs[coroutine] = PendingDig(player.beginnerState(), player.beginnerHotColdTarget)
        return true
    }

    override fun claims(player: Player, npc: Npc, type: NpcServerType): Boolean {
        if (!player.carriesActiveClue()) return false
        return when (val clue = BeginnerClues.get(player.beginnerRow)) {
            is BeginnerClue.Anagram -> clue.matches(type)
            is BeginnerClue.Cryptic -> clue.matches(type)
            else -> false
        }
    }

    override suspend fun ProtectedAccess.talk(npc: Npc, type: NpcServerType) {
        if (!claims(player, npc, type)) return
        val clue = BeginnerClues.get(player.beginnerRow)
        when {
            clue is BeginnerClue.Cryptic && clue.charlie -> talkCharlie(npc)
            clue is BeginnerClue.Cryptic && clue.hotCold -> talkReldo(npc)
            else -> completeStep()
        }
    }

    private fun Player.carriesActiveClue(): Boolean =
        beginnerState().valid && inv.physicalCount(BEGINNER_SCROLL) == 1

    private fun ProtectedAccess.readClue() {
        when (val clue = BeginnerClues.get(player.beginnerRow)) {
            is BeginnerClue.Map -> ifOpenMainModal(clue.mapInterface)
            is BeginnerClue.Anagram -> {
                ifOpenMainModal("interface.trail_cluetext")
                ifSetText("component.trail_cluetext:text", clue.data.clueText)
            }
            is BeginnerClue.Cryptic -> {
                if (clue.hotCold) {
                    val result = prepareHotCold()
                    if (result != null) {
                        report(result)
                        return
                    }
                }
                ifOpenMainModal("interface.trail_cluetext")
                val task = if (clue.charlie) BeginnerCharlieTasks.get(player.beginnerCharlieTask) else null
                ifSetText("component.trail_cluetext:text", task?.data?.clueText ?: clue.data.clueText)
            }
            null -> mes("This clue step is not available. Your scroll has been kept.")
        }
        helper.show(player)
    }

    private suspend fun ProtectedAccess.talkCharlie(npc: Npc) {
        val expected = player.beginnerState()
        val firstRequest = player.beginnerCharlieTask == 0
        val assigned = player.assignBeginnerCharlieTask(expected) {
            val tasks = BeginnerCharlieTasks.ALL
            tasks[random.of(tasks.size)].rowId
        }
        if (assigned != null) {
            report(assigned)
            return
        }
        val task = checkNotNull(BeginnerCharlieTasks.get(player.beginnerCharlieTask))
        helper.show(player)
        if (firstRequest) {
            startDialogue(npc) {
                chatNpc(neutral, "Please, can you help me?")
                chatPlayer(neutral, "What can I do for you?")
                chatNpc(neutral, task.requestText)
                chatPlayer(neutral, "I'll bring that back for you.")
            }
            return
        }
        val next = if (expected.finalStep) 0 else nextRow(expected.row)
        val result = player.completeBeginnerCharlieStep(expected, task.rowId, next)
        if (result == ClueFailure.TASK_ITEM) {
            startDialogue(npc) { chatNpc(neutral, task.requestText) }
        } else if (result != null) {
            report(result)
        } else {
            helper.show(player)
            ifClose()
            mes("You give Charlie the ${task.item.name.lowercase()}.")
            mes(if (expected.finalStep) "Charlie gives you a casket!" else "Charlie gives you a new clue!")
            startDialogue(npc) { chatNpc(happy, "Thank you so much.") }
        }
    }

    private fun ProtectedAccess.prepareHotCold(): ClueFailure? =
        player.assignBeginnerHotColdTarget(player.beginnerState()) {
            val targets = BeginnerHotColdTargets.ALL
            targets[random.of(targets.size)].rowId
        }

    private suspend fun ProtectedAccess.talkReldo(npc: Npc) {
        val prepared = prepareHotCold()
        if (prepared != null) {
            report(prepared)
            return
        }
        val expected = player.beginnerState()
        if (player.ownsBeginnerDevice()) {
            startDialogue(npc) {
                chatNpc(neutral, "Keep using your strange device. It will lead you to the buried treasure.")
            }
            return
        }
        startDialogue(npc) {
            chatNpc(neutral, "That is a hot and cold clue. Do you know how the device works?")
            val explain = choice2("Yes, I do.", false, "No, please explain.", true)
            if (explain) chatNpc(neutral,
                "Feel the device as you travel. It grows warmer as you approach the treasure. " +
                    "When it starts shaking, use a spade nearby.")
        }
        val received = player.receiveBeginnerDevice(expected)
        if (received != null) report(received)
        else mes("Reldo gives you a strange device. You can keep it for future clues.")
    }

    private fun ProtectedAccess.completeStep() {
        val expected = player.beginnerState()
        val next = if (expected.finalStep) 0 else nextRow(expected.row)
        val clue = BeginnerClues.get(expected.row)
        val result = if (clue is BeginnerClue.Cryptic && clue.hotCold) {
            player.completeBeginnerHotColdStep(expected, player.beginnerHotColdTarget, next)
        } else player.completeBeginnerStep(expected, next)
        if (result != null) {
            report(result)
        } else {
            helper.show(player)
            ifClose()
            mes(if (expected.finalStep) "You have found a reward casket!" else "You have found another clue!")
        }
    }

    private fun nextRow(previous: Int = 0): Int {
        val options = BeginnerClues.ALL.filter { it.rowId != previous }
        return options[random.of(options.size)].rowId
    }

    private fun rollLength(): Int = beginnerTrailLength(random.of(100))

    private fun ProtectedAccess.report(failure: ClueFailure) {
        mes(when (failure) {
            ClueFailure.QUEST -> "You need to complete X Marks the Spot before opening scroll boxes."
            ClueFailure.DUPLICATE -> "The clue box refuses to open - you already have a clue."
            ClueFailure.SPACE -> "You need more inventory space."
            ClueFailure.INPUT, ClueFailure.STALE -> "You no longer have that clue."
            ClueFailure.TASK_ITEM -> "You need to bring Charlie the requested unnoted item."
        })
    }
}
