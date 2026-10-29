// Hand-maintained drop table: player location and quest-gated shared-table access.
package org.rsmod.content.drops.tables.shared

import dtx.core.RollResult
import dtx.rs.RSWeightedTable
import dtx.rs.rsWeightedTable
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.ringNothing
import org.rsmod.content.drops.hasCompletedLegendsQuest
import org.rsmod.content.drops.isChaosTalismanArea
import org.rsmod.game.entity.Player

private val LEGENDS_MEGA_RARE_TABLE: RSWeightedTable<Player, DropRollItem> = rsWeightedTable {
    name("Legends' Quest gem-table access")
    shouldInclude { player, _ -> player.hasCompletedLegendsQuest() }
    1 weight megaRareDropTable
}

public val gemDropTable: RSWeightedTable<Player, DropRollItem> = rsWeightedTable {
    name("Gem drop table")
    63 weight ringNothing()
    32 weight DropRollItem("obj.uncut_sapphire", 1)
    16 weight DropRollItem("obj.uncut_emerald", 1)
    8 weight DropRollItem("obj.uncut_ruby", 1)
    3 weightBy { player, _ -> RollResult.Single(player.talismanDrop()) }
    2 weight DropRollItem("obj.uncut_diamond", 1)
    1 weight DropRollItem("obj.rune_javelin", 5)
    1 weight DropRollItem("obj.keyhalf2", 1)
    1 weight DropRollItem("obj.keyhalf1", 1)
    1 weight LEGENDS_MEGA_RARE_TABLE
    1 weight {
        shouldInclude { player, _ -> !player.hasCompletedLegendsQuest() }
        selectResult { player, _ -> RollResult.Single(player.talismanDrop()) }
    }
}

private fun Player.talismanDrop(): DropRollItem =
    DropRollItem(if (coords.isChaosTalismanArea()) "obj.chaos_talisman" else "obj.nature_talisman", 1)
