package org.rsmod.api.player.events.interact

import dev.openrune.types.NpcServerType
import org.rsmod.api.player.hook.NpcOpOverrideHook
import org.rsmod.game.entity.Npc
import org.rsmod.game.interact.InteractionOp

public class NpcOverrideEvent(
    public val npc: Npc,
    public val type: NpcServerType,
    public val op: InteractionOp,
    public val hook: NpcOpOverrideHook,
) : OpDefaultEvent()
