package org.rsmod.content.other.treasuretrails

import org.rsmod.game.entity.Player

object ClueDropPolicy {
    fun mayDrop(player: Player, symbol: String): Boolean {
        val tier = ClueOwnership.tier(symbol) ?: return true
        if (!ClueOwnership.isBox(symbol)) {
            return ClueOwnership.count(player, tier, activeOnly = true) == 0L
        }
        if (ClueOwnership.count(player, tier) < ClueOwnership.capacity(player, tier)) return true
        return player.vars["varbit.option_trail_reminder_${tier.name.lowercase()}"] == 0
    }
}
