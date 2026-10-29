package org.rsmod.content.other.treasuretrails

import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Player

internal var Player.beginnerRow by intVarp("varp.vesper_beginner_clue_row")
internal var Player.beginnerLength by intVarp("varp.vesper_beginner_clue_length")
internal var Player.beginnerCompletedSteps by intVarp("varp.vesper_beginner_clue_completed_steps")
internal var Player.beginnerGeneration by intVarp("varp.vesper_beginner_clue_generation")
internal var Player.beginnerCharlieTask by intVarp("varp.vesper_beginner_charlie_task")
internal var Player.beginnerHotColdTarget by intVarp("varp.vesper_beginner_hotcold_target")

// Zero means no previous reading; measured distance is stored plus one.
internal var Player.beginnerHotColdDistance by intVarp("varp.vesper_beginner_hotcold_distance")
internal var Player.beginnerHotColdCoord by intVarp("varp.vesper_beginner_hotcold_coord")

internal data class BeginnerClueState(
    val row: Int,
    val length: Int,
    val completed: Int,
    val generation: Int,
) {
    val valid: Boolean get() = row > 0 && length in 1..3 && completed in 0 until length
    val finalStep: Boolean get() = completed + 1 == length
}

internal fun Player.beginnerState() = BeginnerClueState(
    beginnerRow, beginnerLength, beginnerCompletedSteps, beginnerGeneration,
)

internal fun Player.storeBeginnerState(state: BeginnerClueState) {
    if (beginnerState() != state) {
        beginnerCharlieTask = 0
        beginnerHotColdTarget = 0
        beginnerHotColdDistance = 0
        beginnerHotColdCoord = 0
    }
    beginnerRow = state.row
    beginnerLength = state.length
    beginnerCompletedSteps = state.completed
    beginnerGeneration = state.generation
}

internal fun nextClueGeneration(current: Int): Int = if (current == Int.MAX_VALUE) 1 else current + 1

internal fun beginnerTrailLength(roll: Int): Int {
    require(roll in 0 until 100)
    return when (roll) {
        in 0..9 -> 1
        in 10..54 -> 2
        else -> 3
    }
}

internal const val BEGINNER_SCROLL = "obj.trail_clue_beginner"
internal const val BEGINNER_BOX = "obj.league_clue_box_beginner"
internal const val BEGINNER_CASKET = "obj.trail_reward_casket_beginner"
