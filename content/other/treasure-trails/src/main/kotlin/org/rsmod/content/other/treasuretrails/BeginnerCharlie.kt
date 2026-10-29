package org.rsmod.content.other.treasuretrails

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import org.rsmod.api.enums.TreasureTrailsBeginnerEnums
import org.rsmod.api.table.cluehelper.CluehelperClueSkillchallengeRow
import org.rsmod.game.entity.Player

internal data class BeginnerCharlieTask(
    val data: CluehelperClueSkillchallengeRow,
    val item: ItemServerType,
) {
    val rowId: Int get() = data.rowId
    val requestText: String get() = "I really need " + data.clueText.removePrefix("I need to give Charlie ")
}

internal object BeginnerCharlieTasks {
    val ALL: List<BeginnerCharlieTask> by lazy {
        TreasureTrailsBeginnerEnums.treasure_trails_beginner_charlie.map { (row, item) ->
            val data = CluehelperClueSkillchallengeRow.getRow(row.id)
            val target = checkNotNull(data.target) { "Charlie task ${row.id} has no NPC target" }
            val requiredItem = checkNotNull(item) { "Charlie task ${row.id} has no requested item" }
            check(data.difficulty == 0 && data.requirements.isEmpty() &&
                target.npc.id == "npc.tramppg".asRSCM() &&
                data.clueText.startsWith("I need to give Charlie ") &&
                requiredItem.certtemplate == 0 && !requiredItem.isDummyItem) {
                "Unsupported native Charlie task ${row.id}"
            }
            BeginnerCharlieTask(data, requiredItem)
        }.also { tasks ->
            val native = CluehelperClueSkillchallengeRow.all().filter { it.difficulty == 0 }
            check(tasks.map { it.rowId }.toSet() == native.map { it.rowId }.toSet()) {
                "Charlie task mapping must cover every native beginner skill challenge"
            }
        }
    }

    fun get(rowId: Int): BeginnerCharlieTask? = ALL.firstOrNull { it.rowId == rowId }
}

/** A chosen request survives closing dialogue and reconnecting, and cannot be rerolled by talking. */
internal fun Player.assignBeginnerCharlieTask(
    expected: BeginnerClueState,
    choose: () -> Int,
): ClueFailure? {
    if (!expected.valid || beginnerState() != expected || expected.row != CHARLIE_ROW.asRSCM()) {
        return ClueFailure.STALE
    }
    if (inv.physicalCount(BEGINNER_SCROLL) != 1) return ClueFailure.INPUT
    if (ClueOwnership.count(this, ClueTier.Beginner, activeOnly = true) != 1L) {
        return ClueFailure.DUPLICATE
    }
    if (beginnerCharlieTask != 0) {
        return if (BeginnerCharlieTasks.get(beginnerCharlieTask) != null) null else ClueFailure.STALE
    }
    val task = checkNotNull(BeginnerCharlieTasks.get(choose())) { "Unknown Charlie task selected" }
    beginnerCharlieTask = task.rowId
    return null
}
