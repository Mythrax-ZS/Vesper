package org.rsmod.api.spells.runes

import org.rsmod.game.entity.Player

/** Content-owned permission to use blighted rune sacks in the player's current context. */
public fun interface BlightedRunePackPermission {
    public fun permits(player: Player): Boolean
}
