package org.rsmod.api.player.hit.modifier

import com.google.inject.ImplementedBy
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder

@ImplementedBy(NoopPvpIncomingDamageReducer::class)
public fun interface PvpIncomingDamageReducer {
    public fun HitBuilder.reduce(target: Player)
}

public class NoopPvpIncomingDamageReducer : PvpIncomingDamageReducer {
    override fun HitBuilder.reduce(target: Player): Unit = Unit
}
