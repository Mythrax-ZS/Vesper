package org.rsmod.content.skills.magic.lunar

import org.rsmod.game.entity.Player

/** Supplied by an actual restricted activity, such as a future player-owned-house session. */
public fun interface VengeanceRestrictedArea {
    public fun contains(player: Player): Boolean
}

/** A group activity must establish membership; a client-side team or aid flag is insufficient. */
public fun interface VengeanceAidPermission {
    public fun permits(caster: Player, target: Player): Boolean
}
