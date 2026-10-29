package org.rsmod.content.skills.agility.pack

import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType
import dev.openrune.pack.columnCoord
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.map.CoordGrid

object AgilityTables {
    fun courses() = dbTable("dbtable.agility_courses", serverOnly = true) {
        column("key", 0, VarType.INT)
        column("name", 1, VarType.STRING)
        column("level", 2, VarType.INT)
        column("steps", 3, VarType.INT)
        column("bonus_tenths", 4, VarType.INT)
        column("mark_numerator", 5, VarType.INT)
        column("mark_denominator", 6, VarType.INT)
        row("dbrow.agility_gnome") {
            column(0, 1)
            column(1, "Gnome Stronghold")
            column(2, 1)
            column(3, 7)
            column(4, 500)
        }
        row("dbrow.agility_draynor") {
            column(0, 2)
            column(1, "Draynor Village")
            column(2, 1)
            column(3, 7)
            column(4, 790)
            column(5, 2)
            column(6, 6)
        }
    }

    fun obstacles() = dbTable("dbtable.agility_obstacles", serverOnly = true) {
        column("course", 0, VarType.INT)
        column("step", 1, VarType.INT)
        column("loc", 2, VarType.LOC)
        column("loc_coord", 3, VarType.COORDGRID)
        column("entry", 4, VarType.COORDGRID)
        column("exit", 5, VarType.COORDGRID)
        column("xp_tenths", 6, VarType.INT)
        column("kind", 7, VarType.STRING)
        column("animation", 8, VarType.SEQ)
        column("entry_span", 9, VarType.INT)
        column("enter_animation", 10, VarType.SEQ)
        column("leave_animation", 11, VarType.SEQ)

        obstacle("gnome_log", 1, 1, "gnome_log_balance1",
            CoordGrid(2474, 3435), CoordGrid(2474, 3436), CoordGrid(2474, 3429),
            100, "BALANCE", "human_walk_logbalance_loop")
        for (x in listOf(2471, 2473, 2475)) {
            obstacle("gnome_net_up_$x", 1, 2, "obstical_net2",
                CoordGrid(x, 3425), CoordGrid(x, 3426), CoordGrid(x, 3424, 1),
                100, "CLIMB", "human_climbing", entrySpan = 1)
        }
        obstacle("gnome_branch_up", 1, 3, "climbing_branch",
            CoordGrid(2473, 3422, 1), CoordGrid(2473, 3423, 1), CoordGrid(2473, 3420, 2),
            65, "CLIMB", "human_climbing")
        obstacle("gnome_rope", 1, 4, "balancing_rope",
            CoordGrid(2478, 3420, 2), CoordGrid(2477, 3420, 2), CoordGrid(2483, 3420, 2),
            100, "BALANCE", "human_walk_logbalance_loop")
        obstacle("gnome_branch_down_west", 1, 5, "climbing_tree",
            CoordGrid(2486, 3419, 2), CoordGrid(2485, 3419, 2), CoordGrid(2487, 3420),
            65, "CLIMB", "human_climbing_down")
        obstacle("gnome_branch_down_south", 1, 5, "climbing_tree2",
            CoordGrid(2487, 3418, 2), CoordGrid(2486, 3418, 2), CoordGrid(2487, 3420),
            65, "CLIMB", "human_climbing_down")
        obstacle("gnome_branch_down_north", 1, 5, "climbing_tree2",
            CoordGrid(2487, 3420, 2), CoordGrid(2487, 3421, 2), CoordGrid(2487, 3420),
            65, "CLIMB", "human_climbing_down")
        for (x in listOf(2483, 2485, 2487)) {
            obstacle("gnome_net_over_$x", 1, 6, "obstical_net3",
                CoordGrid(x, 3426), CoordGrid(x, 3425), CoordGrid(x, 3427),
                100, "CLIMB", "human_climbing", entrySpan = 1)
        }
        obstacle("gnome_pipe_west", 1, 7, "obstical_pipe3_1",
            CoordGrid(2484, 3431), CoordGrid(2484, 3430), CoordGrid(2484, 3437),
            75, "CRAWL", "human_crawling",
            enterAnimation = "human_pipesqueeze", leaveAnimation = "human_pipeunsqueeze")
        obstacle("gnome_pipe_east", 1, 7, "obstical_pipe3_2",
            CoordGrid(2487, 3431), CoordGrid(2487, 3430), CoordGrid(2487, 3437),
            75, "CRAWL", "human_crawling",
            enterAnimation = "human_pipesqueeze", leaveAnimation = "human_pipeunsqueeze")

        obstacle("draynor_wall_up", 2, 1, "rooftops_draynor_wallclimb",
            CoordGrid(3103, 3279), CoordGrid(3104, 3279), CoordGrid(3102, 3279, 3),
            50, "CLIMB", "human_climbing")
        obstacle("draynor_rope_west", 2, 2, "rooftops_draynor_tightrope_1",
            CoordGrid(3098, 3277, 3), CoordGrid(3099, 3277, 3), CoordGrid(3090, 3276, 3),
            80, "BALANCE", "human_walk_logbalance_loop")
        obstacle("draynor_rope_south", 2, 3, "rooftops_draynor_tightrope_2",
            CoordGrid(3092, 3276, 3), CoordGrid(3091, 3276, 3), CoordGrid(3092, 3266, 3),
            70, "BALANCE", "human_walk_logbalance_loop")
        obstacle("draynor_narrow_wall", 2, 4, "rooftops_draynor_wallcrossing",
            CoordGrid(3089, 3264, 3), CoordGrid(3089, 3265, 3), CoordGrid(3088, 3257, 3),
            70, "BALANCE", "human_walk_logbalance_loop")
        obstacle("draynor_wall_over", 2, 5, "rooftops_draynor_wallscramble",
            CoordGrid(3088, 3256, 3), CoordGrid(3088, 3257, 3), CoordGrid(3088, 3255, 3),
            100, "JUMP", "agility_shortcut_wall_jump2")
        obstacle("draynor_gap", 2, 6, "rooftops_draynor_leapdown",
            CoordGrid(3095, 3255, 3), CoordGrid(3094, 3255, 3), CoordGrid(3096, 3256, 3),
            40, "JUMP", "human_longjump")
        obstacle("draynor_crate", 2, 7, "rooftops_draynor_crate",
            CoordGrid(3102, 3261, 3), CoordGrid(3101, 3261, 3), CoordGrid(3103, 3261),
            0, "CLIMB", "human_climbing_down")
    }

    private fun DBTableBuilder.obstacle(
        key: String, course: Int, step: Int, loc: String,
        locCoord: CoordGrid, entry: CoordGrid, exit: CoordGrid,
        xpTenths: Int, kind: String, animation: String, entrySpan: Int = 0,
        enterAnimation: String? = null, leaveAnimation: String? = null,
    ) {
        row("dbrow.agility_$key") {
            column(0, course)
            column(1, step)
            column(2, "loc.$loc".asRSCM(RSCMType.LOC))
            columnCoord(3, locCoord)
            columnCoord(4, entry)
            columnCoord(5, exit)
            column(6, xpTenths)
            column(7, kind)
            column(8, "seq.$animation".asRSCM(RSCMType.SEQ))
            column(9, entrySpan)
            enterAnimation?.let { column(10, "seq.$it".asRSCM(RSCMType.SEQ)) }
            leaveAnimation?.let { column(11, "seq.$it".asRSCM(RSCMType.SEQ)) }
        }
    }
}
