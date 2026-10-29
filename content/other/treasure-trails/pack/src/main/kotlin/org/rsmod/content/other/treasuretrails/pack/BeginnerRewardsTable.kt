package org.rsmod.content.other.treasuretrails.pack

import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType
import dev.openrune.rscm.RSCM.asRSCM

/** Full published beginner casket table; probabilities are per reward roll.
 * Weights use denominator 44,280: unique 1/360, black 11/8,856,
 * ordinary 11/492, cabbage 1/24. See the module README for evidence.
 */
object BeginnerRewardsTable {
    fun rewards() = dbTable("dbtable.treasure_trails_beginner_rewards", serverOnly = true) {
        column("item", 0, VarType.OBJ)
        column("pool", 1, VarType.STRING)
        column("weight", 2, VarType.INT)
        column("minimum", 3, VarType.INT)
        column("maximum", 4, VarType.INT)
        column("noted", 5, VarType.BOOLEAN)

        reward("mole_slippers", "UNIQUE", 123, 1, 1, false)
        reward("frog_slippers", "UNIQUE", 123, 1, 1, false)
        reward("bear_slippers", "UNIQUE", 123, 1, 1, false)
        reward("demon_slippers", "UNIQUE", 123, 1, 1, false)
        reward("jester_cape", "UNIQUE", 123, 1, 1, false)
        reward("shoulder_parrot", "UNIQUE", 123, 1, 1, false)
        reward("monk_robetop_t", "UNIQUE", 123, 1, 1, false)
        reward("monk_robebottom_t", "UNIQUE", 123, 1, 1, false)
        reward("amulet_of_defence_t", "UNIQUE", 123, 1, 1, false)
        reward("sandwich_lady_hat", "UNIQUE", 123, 1, 1, false)
        reward("sandwich_lady_top", "UNIQUE", 123, 1, 1, false)
        reward("sandwich_lady_bottom", "UNIQUE", 123, 1, 1, false)
        reward("rune_scimitar_ornament_kit_guthix", "UNIQUE", 123, 1, 1, false)
        reward("rune_scimitar_ornament_kit_saradomin", "UNIQUE", 123, 1, 1, false)
        reward("rune_scimitar_ornament_kit_zamorak", "UNIQUE", 123, 1, 1, false)
        reward("black_2h_sword", "BLACK", 55, 1, 1, false)
        reward("black_axe", "BLACK", 55, 1, 1, false)
        reward("black_battleaxe", "BLACK", 55, 1, 1, false)
        reward("black_chainbody", "BLACK", 55, 1, 1, false)
        reward("black_dagger", "BLACK", 55, 1, 1, false)
        reward("black_full_helm", "BLACK", 55, 1, 1, false)
        reward("black_kiteshield", "BLACK", 55, 1, 1, false)
        reward("black_longsword", "BLACK", 55, 1, 1, false)
        reward("black_mace", "BLACK", 55, 1, 1, false)
        reward("black_med_helm", "BLACK", 55, 1, 1, false)
        reward("black_pickaxe", "BLACK", 55, 1, 1, false)
        reward("black_platebody", "BLACK", 55, 1, 1, false)
        reward("black_plateskirt", "BLACK", 55, 1, 1, false)
        reward("black_platelegs", "BLACK", 55, 1, 1, false)
        reward("black_sq_shield", "BLACK", 55, 1, 1, false)
        reward("black_scimitar", "BLACK", 55, 1, 1, false)
        reward("black_sword", "BLACK", 55, 1, 1, false)
        reward("black_warhammer", "BLACK", 55, 1, 1, false)
        reward("shortbow", "COMMON", 990, 1, 1, false)
        reward("longbow", "COMMON", 990, 1, 1, false)
        reward("oak_shortbow", "COMMON", 990, 1, 1, false)
        reward("oak_longbow", "COMMON", 990, 1, 1, false)
        reward("iron_pickaxe", "COMMON", 990, 1, 1, false)
        reward("staff_of_air", "COMMON", 990, 1, 1, false)
        reward("staff_of_water", "COMMON", 990, 1, 1, false)
        reward("staff_of_earth", "COMMON", 990, 1, 1, false)
        reward("staff_of_fire", "COMMON", 990, 1, 1, false)
        reward("steel_full_helm", "COMMON", 990, 1, 1, false)
        reward("steel_platebody", "COMMON", 990, 1, 1, false)
        reward("steel_platelegs", "COMMON", 990, 1, 1, false)
        reward("steel_plateskirt", "COMMON", 990, 1, 1, false)
        reward("steel_longsword", "COMMON", 990, 1, 1, false)
        reward("steel_dagger", "COMMON", 990, 1, 1, false)
        reward("steel_axe", "COMMON", 990, 1, 1, false)
        reward("steel_battleaxe", "COMMON", 990, 1, 1, false)
        reward("leather_cowl", "COMMON", 990, 1, 1, false)
        reward("leather_armour", "COMMON", 990, 1, 1, false)
        reward("leather_chaps", "COMMON", 990, 1, 1, false)
        reward("leather_vambraces", "COMMON", 990, 1, 1, false)
        reward("hardleather_body", "COMMON", 990, 1, 1, false)
        reward("bluewizhat", "COMMON", 990, 1, 1, false)
        reward("wizards_robe", "COMMON", 990, 1, 1, false)
        reward("blackwizhat", "COMMON", 990, 1, 1, false)
        reward("black_robe", "COMMON", 990, 1, 1, false)
        reward("airrune", "COMMON", 990, 15, 35, false)
        reward("mindrune", "COMMON", 990, 15, 35, false)
        reward("waterrune", "COMMON", 990, 15, 35, false)
        reward("earthrune", "COMMON", 990, 15, 35, false)
        reward("firerune", "COMMON", 990, 15, 35, false)
        reward("bodyrune", "COMMON", 990, 15, 35, false)
        reward("chaosrune", "COMMON", 990, 2, 7, false)
        reward("naturerune", "COMMON", 990, 2, 7, false)
        reward("lawrune", "COMMON", 990, 2, 7, false)
        reward("bronze_arrow", "COMMON", 990, 15, 30, false)
        reward("iron_arrow", "COMMON", 990, 7, 15, false)
        reward("shrimp", "COMMON", 990, 5, 14, true)
        reward("sardine", "COMMON", 990, 5, 12, true)
        reward("herring", "COMMON", 990, 5, 9, true)
        reward("cabbage", "CABBAGE", 1845, 5, 9, true)
    }

    private fun DBTableBuilder.reward(
        item: String, pool: String, weight: Int, minimum: Int, maximum: Int, noted: Boolean,
    ) {
        row("dbrow.beginner_reward_$item") {
            column(0, "obj.$item".asRSCM())
            column(1, pool)
            column(2, weight)
            column(3, minimum)
            column(4, maximum)
            column(5, noted)
        }
    }
}
