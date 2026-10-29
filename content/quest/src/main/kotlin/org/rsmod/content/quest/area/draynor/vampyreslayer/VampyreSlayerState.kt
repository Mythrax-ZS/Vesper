package org.rsmod.content.quest.area.draynor.vampyreslayer

import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Player

internal object VampyreSlayerStage {
    const val NotStarted = 0
    const val Started = 1
    const val HarlowAsked = 2
    const val Complete = 3
}

internal var Player.vampyreBeerGiven by intVarBit("varbit.harlow_given_beer")
internal var Player.vampyreStakeGiven by intVarBit("varbit.harlow_given_stake")
internal var Player.vampyreMorganThanked by intVarBit("varbit.morgan_postquest_dialogue")
internal var Player.vampyreCompletionPending by intVarp("varp.vampyre_slayer_pending_completion")

internal const val VampyreProgress = "varbit.vampyre_slayer_progress"
internal const val VampyreSettlementTimer = "timer.vampyre_slayer_settlement"
internal const val CountDraynor = "npc.count_draynor"
internal const val Stake = "obj.stake"
internal const val Garlic = "obj.garlic"
internal const val Hammer = "obj.hammer"
