package org.rsmod.api.player.hit.modifier

import com.google.inject.ImplementedBy
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder

@ImplementedBy(NoopNpcIncomingDamageReducer::class)
public fun interface NpcIncomingDamageReducer {
    public fun HitBuilder.reduce(target: Player)
}

public class NoopNpcIncomingDamageReducer : NpcIncomingDamageReducer {
    override fun HitBuilder.reduce(target: Player): Unit = Unit
}
