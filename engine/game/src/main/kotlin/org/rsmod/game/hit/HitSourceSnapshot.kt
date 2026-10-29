package org.rsmod.game.hit

import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList

public sealed class HitSourceSnapshot {
    public class FromPlayer internal constructor(private val source: Player) : HitSourceSnapshot() {
        private val slot = source.slotId
        private val uid = source.uid
        private val uuid = source.uuid
        private val client = source.client
        private val generation = source.hitGeneration

        public fun resolve(players: PlayerList): Player? = source.takeIf {
            players[slot] === it && it.uid == uid && it.uuid == uuid && it.client === client &&
                it.hitGeneration == generation && it.canProcess
        }
    }

    public class FromNpc internal constructor(private val source: Npc) : HitSourceSnapshot() {
        private val slot = source.slotId
        private val generation = source.hitGeneration

        public fun resolve(npcs: NpcList): Npc? = source.takeIf {
            npcs[slot] === it && it.slotId == slot && it.hitGeneration == generation && it.isVisible
        }
    }

    public companion object {
        public fun capture(source: Player): FromPlayer = FromPlayer(source)
        public fun capture(source: Npc): FromNpc = FromNpc(source)
    }
}
