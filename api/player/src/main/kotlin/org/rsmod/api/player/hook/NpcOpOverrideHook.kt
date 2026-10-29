package org.rsmod.api.player.hook

import dev.openrune.types.NpcServerType
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player

public interface NpcOpOverrideHook {
    public fun claims(player: Player, npc: Npc, type: NpcServerType): Boolean

    public suspend fun ProtectedAccess.talk(npc: Npc, type: NpcServerType)
}
