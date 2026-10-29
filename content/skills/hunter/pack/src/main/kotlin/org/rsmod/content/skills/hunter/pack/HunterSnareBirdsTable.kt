package org.rsmod.content.skills.hunter.pack

import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

object HunterSnareBirdsTable {
    fun create() = dbTable("dbtable.hunter_snare_birds", serverOnly = true) {
        column("npc", 0, VarType.NPC)
        column("guide", 1, VarType.DBROW)
        column("trapping_loc", 2, VarType.LOC)
        column("caught_loc", 3, VarType.LOC)
        column("feather", 4, VarType.OBJ)
        column("xp_tenths", 5, VarType.INT)
        column("success_low", 6, VarType.INT)
        column("success_high", 7, VarType.INT)
        column("feathers_min", 8, VarType.INT)
        column("feathers_max", 9, VarType.INT)
        bird("crimson_swift", "hunting_bird_jungle", "jungle_bird", "jungle", "jungle", 340, 100, 420)
        bird("golden_warbler", "hunting_bird_desert", "desert_bird", "desert", "desert", 470, 92, 400)
        bird("copper_longtail", "hunting_bird_woodland", "wood_bird", "woodland", "woodland", 612, 85, 390)
        bird("cerulean_twitch", "hunting_bird_polar", "polar_bird", "polar", "polar", 645, 82, 380)
        bird("tropical_wagtail", "multicoloured_bird", "coloured_bird", "coloured", "stripy_bird", 952, 75, 370)
    }

    private fun DBTableBuilder.bird(
        key: String,
        npc: String,
        guide: String,
        locSuffix: String,
        feather: String,
        xpTenths: Int,
        low: Int,
        high: Int,
    ) {
        row("dbrow.hunter_snare_$key") {
            columnRSCM(0, "npc.$npc")
            columnRSCM(1, "dbrow.skill_feature_hunter_$guide")
            columnRSCM(2, "loc.hunting_ojibway_trap_trapping_$locSuffix")
            columnRSCM(3, "loc.hunting_ojibway_trap_full_$locSuffix")
            columnRSCM(4, "obj.hunting_${feather}_feather")
            column(5, xpTenths)
            column(6, low)
            column(7, high)
            column(8, 5)
            column(9, 10)
        }
    }
}
