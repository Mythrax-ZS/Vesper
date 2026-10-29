package org.rsmod.content.drops

import org.rsmod.api.table.QuestRow
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

private val LEGENDS_END_STATE by lazy { QuestRow.getRow("dbrow.quest_legends").endstate }

public fun CoordGrid.isChaosTalismanArea(): Boolean =
    level == 0 && x in 1920..3903 && z in 9474..11263

public fun Player.hasCompletedLegendsQuest(): Boolean =
    hasCompletedQuest("quest_legends") || vars["varp.legendsquest"] >= LEGENDS_END_STATE
