package org.rsmod.content.areas.wilderness

import jakarta.inject.Inject
import org.rsmod.api.player.hook.PlayerPostTickHook
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.spells.runes.BlightedRunePackPolicy
import org.rsmod.game.entity.Player

public class BlightedRunePackSync
@Inject
constructor(private val policy: BlightedRunePackPolicy) : PlayerPostTickHook {
    override fun onPostTick(player: Player): Unit = synchronize(player)

    public fun synchronize(player: Player) {
        val allowed = if (policy.permits(player)) 1 else 0
        if (player.vars["varbit.blighted_items_allowed"] != allowed) {
            VarPlayerIntMapSetter.set(player, "varbit.blighted_items_allowed", allowed)
        }
    }
}
