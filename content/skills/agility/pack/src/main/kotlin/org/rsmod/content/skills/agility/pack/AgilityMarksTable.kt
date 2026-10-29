package org.rsmod.content.skills.agility.pack

import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType
import dev.openrune.pack.columnCoord
import org.rsmod.map.CoordGrid

object AgilityMarksTable {
    fun spawns() = dbTable("dbtable.agility_mark_spawns", serverOnly = true) {
        column("course", 0, VarType.DBROW)
        column("coord", 1, VarType.COORDGRID)
        column("count_var", 2, VarType.VARP)
        column("lifetime_var", 3, VarType.VARP)
        spawn("draynor_start", CoordGrid(3099, 3279, 3))
        spawn("draynor_diango", CoordGrid(3088, 3274, 3))
        spawn("draynor_slayer", CoordGrid(3092, 3266, 3))
        spawn("draynor_ned", CoordGrid(3099, 3259, 3))
    }

    private fun DBTableBuilder.spawn(key: String, coord: CoordGrid) {
        row("dbrow.agility_mark_$key") {
            columnRSCM(0, "dbrow.agility_draynor")
            columnCoord(1, coord)
            columnRSCM(2, "varp.agility_mark_${key}_count")
            columnRSCM(3, "varp.agility_mark_${key}_lifetime")
        }
    }
}
