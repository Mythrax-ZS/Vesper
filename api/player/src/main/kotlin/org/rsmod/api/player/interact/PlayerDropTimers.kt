package org.rsmod.api.player.interact

import dev.openrune.types.ItemServerType
import org.rsmod.api.player.hook.GroundItemDropParams
import org.rsmod.game.type.uncert

public object PlayerDropTimers {
    internal const val TRADEABLE_REVEAL: Int = 100
    internal const val DESPAWN: Int = 300
    internal const val CLUE_DESPAWN: Int = 6100
    internal const val NEVER_REVEAL: Int = Int.MAX_VALUE

    public fun resolve(type: ItemServerType): GroundItemDropParams {
        val uncert = uncert(type)
        return when {
            isClueItem(uncert) -> GroundItemDropParams(CLUE_DESPAWN, NEVER_REVEAL)
            uncert.stockmarket -> GroundItemDropParams(DESPAWN, TRADEABLE_REVEAL)
            else -> GroundItemDropParams(DESPAWN, NEVER_REVEAL)
        }
    }

    private val clueScrollName =
        Regex("(Clue|Challenge) scroll \\((beginner|easy|medium|hard|elite|master)\\)")

    private fun isClueItem(type: ItemServerType): Boolean =
        type.name == "Mimic" || clueScrollName.matches(type.name)
}
