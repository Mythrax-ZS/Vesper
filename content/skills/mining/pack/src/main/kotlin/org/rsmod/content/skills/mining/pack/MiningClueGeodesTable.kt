package org.rsmod.content.skills.mining.pack

import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

/** Tier modifiers confirmed by Mod Ash, 6 March 2023; current 0.7 base factor is applied at runtime. */
object MiningClueGeodesTable {
    private const val GEODE = 0
    private const val BOX = 1
    private const val MULTIPLIER_TENTHS = 2
    private const val COMBAT_REWARD = 3
    private const val WILDERNESS_BONUS = 4

    fun create() = dbTable("dbtable.mining_clue_geodes", serverOnly = true) {
        column("geode", GEODE, VarType.OBJ)
        column("box", BOX, VarType.OBJ)
        column("multiplier_tenths", MULTIPLIER_TENTHS, VarType.INT)
        column("combat_reward", COMBAT_REWARD, VarType.STRING)
        column("wilderness_bonus", WILDERNESS_BONUS, VarType.BOOLEAN)
        tier("beginner", 2, false)
        tier("easy", 17)
        tier("medium", 20)
        tier("hard", 33)
        tier("elite", 100)
    }

    private fun DBTableBuilder.tier(name: String, multiplierTenths: Int, hasBonus: Boolean = true) {
        row("dbrow.mining_clue_geode_$name") {
            columnRSCM(GEODE, "obj.mining_clue_geode_$name")
            columnRSCM(BOX, "obj.league_clue_box_$name")
            column(MULTIPLIER_TENTHS, multiplierTenths)
            column(COMBAT_REWARD, if (hasBonus) "varbit.ca_tier_status_$name" else "")
            column(WILDERNESS_BONUS, hasBonus)
        }
    }
}
