package org.rsmod.content.drops

import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.game.entity.Npc

public fun Npc.inIorwerthDungeon(areaChecker: AreaChecker): Boolean =
    areaChecker.inArea("area.iorwerth_dungeon", coords)
