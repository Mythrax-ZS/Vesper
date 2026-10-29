package org.rsmod.content.other.treasuretrails

import dev.openrune.rscm.RSCM.asRSCM
import org.rsmod.api.invtx.add
import org.rsmod.api.invtx.delete
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.game.entity.Player

internal enum class ClueFailure { QUEST, DUPLICATE, INPUT, SPACE, STALE, TASK_ITEM }

internal fun Player.openBeginnerBox(slot: Int, firstRow: Int, length: Int): ClueFailure? {
    if (!QuestRequirements.hasCompleted(this, "quest_xmarksthespot")) return ClueFailure.QUEST
    if (ClueOwnership.count(this, ClueTier.Beginner, activeOnly = true) != 0L) {
        return ClueFailure.DUPLICATE
    }
    val state = BeginnerClueState(firstRow, length, 0, nextClueGeneration(beginnerGeneration))
    require(state.valid)
    if (inv[slot]?.id != BEGINNER_BOX.asRSCM()) return ClueFailure.INPUT
    val transaction = invTransaction(inv) {
        val items = select(inv)
        delete(items, BEGINNER_BOX.asRSCM(), 1, slot = slot)
        add(items, BEGINNER_SCROLL.asRSCM(), 1)
    }
    if (transaction.failure) return ClueFailure.SPACE
    storeBeginnerState(state)
    return null
}

internal fun Player.initializeBeginnerScroll(firstRow: Int, length: Int): ClueFailure? {
    if (inv.physicalCount(BEGINNER_SCROLL) != 1) return ClueFailure.INPUT
    if (ClueOwnership.count(this, ClueTier.Beginner, activeOnly = true) != 1L) {
        return ClueFailure.DUPLICATE
    }
    if (beginnerState().valid) return null
    val state = BeginnerClueState(firstRow, length, 0, nextClueGeneration(beginnerGeneration))
    require(state.valid)
    storeBeginnerState(state)
    return null
}

internal fun Player.completeBeginnerStep(
    expected: BeginnerClueState,
    nextRow: Int,
): ClueFailure? {
    // Charlie's task cannot be completed through the ordinary talk/dig hand-off.
    if (expected.row == CHARLIE_ROW.asRSCM() || expected.row == RELDO_ROW.asRSCM()) return ClueFailure.STALE
    return completeBeginnerStepTransaction(expected, nextRow, task = null)
}

internal fun Player.completeBeginnerHotColdStep(
    expected: BeginnerClueState,
    expectedTarget: Int,
    nextRow: Int,
): ClueFailure? {
    if (expected.row != RELDO_ROW.asRSCM() || beginnerHotColdTarget != expectedTarget ||
        !atBeginnerHotColdTarget(expectedTarget)) return ClueFailure.STALE
    return completeBeginnerStepTransaction(expected, nextRow, task = null)
}

internal fun Player.completeBeginnerCharlieStep(
    expected: BeginnerClueState,
    expectedTask: Int,
    nextRow: Int,
): ClueFailure? {
    if (expected.row != CHARLIE_ROW.asRSCM() || beginnerCharlieTask != expectedTask) {
        return ClueFailure.STALE
    }
    val task = BeginnerCharlieTasks.get(expectedTask) ?: return ClueFailure.STALE
    return completeBeginnerStepTransaction(expected, nextRow, task)
}

private fun Player.completeBeginnerStepTransaction(
    expected: BeginnerClueState,
    nextRow: Int,
    task: BeginnerCharlieTask?,
): ClueFailure? {
    if (!expected.valid || beginnerState() != expected) return ClueFailure.STALE
    if (inv.physicalCount(BEGINNER_SCROLL) != 1) return ClueFailure.INPUT
    if (ClueOwnership.count(this, ClueTier.Beginner, activeOnly = true) != 1L) {
        return ClueFailure.DUPLICATE
    }
    require(expected.finalStep || nextRow > 0)
    // Match the exact unnoted type in the carried inventory; no bank/equipment substitution.
    val taskSlot = if (task == null) -1 else inv.objs.indexOfFirst { it?.id == task.item.id }
    if (task != null && taskSlot < 0) return ClueFailure.TASK_ITEM
    val slot = inv.objs.indexOfFirst { it?.id == BEGINNER_SCROLL.asRSCM() }
    val output = if (expected.finalStep) BEGINNER_CASKET else BEGINNER_SCROLL
    val transaction = invTransaction(inv) {
        val items = select(inv)
        delete(items, BEGINNER_SCROLL.asRSCM(), 1, slot = slot)
        if (task != null) delete(items, task.item.id, 1, slot = taskSlot)
        add(items, output.asRSCM(), 1)
    }
    if (transaction.failure) return ClueFailure.SPACE
    storeBeginnerState(
        if (expected.finalStep) BeginnerClueState(0, 0, 0, expected.generation)
        else expected.copy(row = nextRow, completed = expected.completed + 1),
    )
    return null
}
