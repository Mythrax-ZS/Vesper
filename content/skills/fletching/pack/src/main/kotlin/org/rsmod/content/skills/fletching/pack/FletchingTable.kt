package org.rsmod.content.skills.fletching.pack

import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType

object FletchingTable {
    private const val INPUT = 0
    private const val SECONDARY = 1
    private const val TOOL = 2
    private const val PRODUCT = 3
    private const val PRODUCT_COUNT = 4
    private const val LEVEL = 5
    private const val XP_TENTHS = 6
    private const val BATCH_SIZE = 7
    private const val ANIMATION = 8
    private const val KIND = 9
    private const val FIRST_TICKS = 10
    private const val REPEAT_TICKS = 11
    private const val INPUT_COUNT = 12
    private const val SECONDARY_COUNT = 13
    private const val SLAYER_UNLOCK = 14
    private const val QUEST = 15
    private const val SECONDARY_ALTERNATIVES = 16

    fun recipes() = dbTable("dbtable.fletching_recipes", serverOnly = true) {
        column("input", INPUT, VarType.OBJ)
        column("secondary", SECONDARY, VarType.OBJ)
        column("tool", TOOL, VarType.OBJ)
        column("product", PRODUCT, VarType.OBJ)
        column("product_count", PRODUCT_COUNT, VarType.INT)
        column("level", LEVEL, VarType.INT)
        column("xp_tenths", XP_TENTHS, VarType.INT)
        column("batch_size", BATCH_SIZE, VarType.INT)
        column("animation", ANIMATION, VarType.SEQ)
        column("kind", KIND, VarType.STRING)
        column("first_ticks", FIRST_TICKS, VarType.INT)
        column("repeat_ticks", REPEAT_TICKS, VarType.INT)
        column("input_count", INPUT_COUNT, VarType.INT)
        column("secondary_count", SECONDARY_COUNT, VarType.INT)
        column("slayer_unlock", SLAYER_UNLOCK, VarType.DBROW)
        column("quest", QUEST, VarType.STRING)
        column("secondary_alternatives", SECONDARY_ALTERNATIVES, VarType.OBJ)

        cut("shafts_logs", "logs", "arrow_shaft", 1, 50, 15)
        cut("shafts_oak", "oak_logs", "arrow_shaft", 15, 100, 30)
        cut("shafts_willow", "willow_logs", "arrow_shaft", 30, 150, 45)
        cut("shafts_maple", "maple_logs", "arrow_shaft", 45, 200, 60)
        cut("shafts_yew", "yew_logs", "arrow_shaft", 60, 250, 75)
        cut("shafts_magic", "magic_logs", "arrow_shaft", 75, 300, 90)
        cut("shafts_redwood", "redwood_logs", "arrow_shaft", 90, 350, 105)
        cut("javelin_shafts", "logs", "javelin_shaft", 3, 50, 15)

        bow("logs", "shortbow", 5, 50)
        bow("logs", "longbow", 10, 100)
        bow("oak_logs", "oak_shortbow", 20, 165)
        bow("oak_logs", "oak_longbow", 25, 250)
        bow("willow_logs", "willow_shortbow", 35, 333, stringXp = 332)
        bow("willow_logs", "willow_longbow", 40, 415)
        bow("maple_logs", "maple_shortbow", 50, 500)
        bow("maple_logs", "maple_longbow", 55, 583, stringXp = 582)
        bow("yew_logs", "yew_shortbow", 65, 675)
        bow("yew_logs", "yew_longbow", 70, 750)
        bow("magic_logs", "magic_shortbow", 80, 833)
        bow("magic_logs", "magic_longbow", 85, 915)

        recipe(
            "headless_arrow", "arrow_shaft", "headless_arrow", 1, 10,
            secondary = "feather", batchSize = 15, kind = "ARROW",
            animation = "human_fletching_add_feather",
        )
        arrow("bronze", 1, 13)
        arrow("iron", 15, 25)
        arrow("steel", 30, 50)
        arrow("mithril", 45, 75)
        arrow("adamant", 60, 100)
        arrow("rune", 75, 125)
        arrow("amethyst", 82, 135)
        arrow("dragon", 90, 150)

        dart("bronze", 10, 18)
        dart("iron", 22, 38)
        dart("steel", 37, 75)
        dart("mithril", 52, 112)
        dart("adamant", 67, 150)
        dart("rune", 81, 188)
        dart("amethyst", 90, 210)
        dart("dragon", 95, 250)

        bolt("bronze", "bolt", 9, 5)
        bolt("blurite", "xbows_crossbow_bolts_blurite", 24, 10)
        bolt("iron", "xbows_crossbow_bolts_iron", 39, 15)
        bolt("silver", "xbows_crossbow_bolts_silver", 43, 25)
        bolt("steel", "xbows_crossbow_bolts_steel", 46, 35)
        bolt("mithril", "xbows_crossbow_bolts_mithril", 54, 50)
        bolt("adamantite", "xbows_crossbow_bolts_adamantite", 61, 70, "adamant")
        bolt("runite", "xbows_crossbow_bolts_runite", 69, 100, "rune")
        recipe(
            "bolt_dragon", "dragon_bolts_unfeathered", "dragon_bolts", 84, 120,
            secondary = "feather", batchSize = 10, kind = "PROJECTILE",
            animation = "human_fletching_add_bolt_feathers_dragon",
        )

        gemTips("opal", "opal", "opal_bolttips", 11, 15, "human_opalcutting")
        gemTips("jade", "jade", "xbows_bolt_tips_jade", 26, 20, "human_jadecutting")
        gemTips("pearl", "smalloysterpearls", "pearl_bolttips", 41, 32, "xbows_fletching_pearl", 6)
        gemTips("pearls", "bigoysterpearls", "pearl_bolttips", 41, 32, "xbows_fletching_pearl", 24)
        gemTips("topaz", "red_topaz", "xbows_bolt_tips_redtopaz", 48, 39, "human_redtopazcutting")
        gemTips("sapphire", "sapphire", "xbows_bolt_tips_sapphire", 56, 40, "human_sapphirecutting")
        gemTips("emerald", "emerald", "xbows_bolt_tips_emerald", 58, 55, "human_emeraldcutting")
        gemTips("ruby", "ruby", "xbows_bolt_tips_ruby", 63, 63, "human_rubycutting")
        gemTips("diamond", "diamond", "xbows_bolt_tips_diamond", 65, 70, "human_diamondcutting")
        gemTips("dragonstone", "dragonstone", "xbows_bolt_tips_dragonstone", 71, 82, "human_dragonstonecutting")
        gemTips("onyx", "onyx", "xbows_bolt_tips_onyx", 73, 94, "human_onyxcutting", 24)

        tippedBolts("opal", "bolt", "opal_bolt", "opal_bolttips", "bronze", 11, 16)
        tippedBolts("jade", "xbows_crossbow_bolts_blurite", "xbows_crossbow_bolts_blurite_tipped_jade", "xbows_bolt_tips_jade", "blurite", 26, 24)
        tippedBolts("pearl", "xbows_crossbow_bolts_iron", "pearl_bolt", "pearl_bolttips", "iron", 41, 32)
        tippedBolts("topaz", "xbows_crossbow_bolts_steel", "xbows_crossbow_bolts_steel_tipped_redtopaz", "xbows_bolt_tips_redtopaz", "steel", 48, 39)
        tippedBolts("sapphire", "xbows_crossbow_bolts_mithril", "xbows_crossbow_bolts_mithril_tipped_sapphire", "xbows_bolt_tips_sapphire", "mithril", 56, 47)
        tippedBolts("emerald", "xbows_crossbow_bolts_mithril", "xbows_crossbow_bolts_mithril_tipped_emerald", "xbows_bolt_tips_emerald", "mithril", 58, 55)
        tippedBolts("ruby", "xbows_crossbow_bolts_adamantite", "xbows_crossbow_bolts_adamantite_tipped_ruby", "xbows_bolt_tips_ruby", "adamant", 63, 63)
        tippedBolts("diamond", "xbows_crossbow_bolts_adamantite", "xbows_crossbow_bolts_adamantite_tipped_diamond", "xbows_bolt_tips_diamond", "adamant", 65, 70)
        tippedBolts("dragonstone", "xbows_crossbow_bolts_runite", "xbows_crossbow_bolts_runite_tipped_dragonstone", "xbows_bolt_tips_dragonstone", "rune", 71, 82)
        tippedBolts("onyx", "xbows_crossbow_bolts_runite", "xbows_crossbow_bolts_runite_tipped_onyx", "xbows_bolt_tips_onyx", "rune", 73, 94)
        recipe(
            "tip_bolt_barbed", "bolt", "barbed_bolt", 51, 95,
            secondary = "barbed_bolttips", batchSize = 10, kind = "ARROW",
            animation = "human_fletching_add_bolt_tips_bronze",
        )

        javelin("bronze", 3, 10)
        javelin("iron", 17, 20)
        javelin("steel", 32, 50)
        javelin("mithril", 47, 80)
        javelin("adamant", 62, 100)
        javelin("rune", 77, 124)
        javelin("amethyst", 84, 135)
        javelin("dragon", 92, 150)

        crossbow("wood", "logs", "bronze", 9, 60, 120)
        crossbow("oak", "oak_logs", "blurite", 24, 160, 320, quest = "quest_knightssword")
        crossbow("willow", "willow_logs", "iron", 39, 220, 440)
        crossbow("teak", "teak_logs", "steel", 46, 270, 540)
        crossbow("maple", "maple_logs", "mithril", 54, 320, 640)
        crossbow("mahogany", "mahogany_logs", "adamantite", 61, 410, 820)
        crossbow("yew", "yew_logs", "runite", 69, 500, 1000)
        crossbow("magic", "magic_logs", "dragon", 78, 700, 1350, animationWood = "yew")

        shield("oak", 27, 500)
        shield("willow", 42, 830)
        shield("maple", 57, 1165)
        shield("yew", 72, 1500)
        shield("magic", 87, 1830)
        shield("redwood", 92, 2160)

        recipe(
            "arrow_broad", "headless_arrow", "slayer_broad_arrows", 52, 100,
            secondary = "slayer_broad_arrowhead", batchSize = 15, kind = "ARROW",
            animation = "human_fletching_add_arrow_tips", slayerUnlock = "slayer_ammo_unlocked",
        )
        recipe(
            "bolt_broad", "slayer_broad_bolt_unfinished", "slayer_broad_bolt", 55, 30,
            secondary = "feather", batchSize = 10, kind = "PROJECTILE",
            animation = "human_fletching_add_bolt_feathers_rune", slayerUnlock = "slayer_ammo_unlocked",
        )
        recipe(
            "tip_bolt_amethyst_broad", "slayer_broad_bolt", "slayer_broad_bolt_amethyst", 76, 106,
            secondary = "xbows_bolt_tips_amethyst", batchSize = 10, kind = "ARROW",
            animation = "human_fletching_add_bolt_tips_rune", slayerUnlock = "slayer_ammo_unlocked",
        )
    }

    private fun DBTableBuilder.cut(
        key: String, log: String, product: String, level: Int, xp: Int, count: Int = 1,
    ) = recipe(
        key, log, product, level, xp, productCount = count, tool = "knife",
        kind = "CUT", animation = "human_fletching", repeatTicks = 3,
    )

    private fun DBTableBuilder.bow(log: String, bow: String, level: Int, xp: Int, stringXp: Int = xp) {
        cut("cut_$bow", log, "unstrung_$bow", level, xp)
        recipe(
            "string_$bow", "unstrung_$bow", bow, level, stringXp, secondary = "bow_string",
            kind = "STRING", animation = "stringing_$bow",
        )
    }

    private fun DBTableBuilder.arrow(metal: String, level: Int, xp: Int) = recipe(
        "arrow_$metal", "headless_arrow", "${metal}_arrow", level, xp,
        secondary = "${metal}_arrowheads", batchSize = 15, kind = "ARROW",
        animation = "human_fletching_add_arrow_tips",
    )

    private fun DBTableBuilder.dart(metal: String, level: Int, xp: Int) = recipe(
        "dart_$metal", "${metal}_dart_tip", "${metal}_dart", level, xp,
        secondary = "feather", batchSize = 10, kind = "PROJECTILE",
        animation = "human_fletching_add_dart_feathers_$metal",
    )

    private fun DBTableBuilder.bolt(
        metal: String, product: String, level: Int, xp: Int, animationMetal: String = metal,
    ) = recipe(
        "bolt_$metal", "xbows_crossbow_bolts_${metal}_unfeathered", product, level, xp,
        secondary = "feather", batchSize = 10, kind = "PROJECTILE",
        animation = "human_fletching_add_bolt_feathers_$animationMetal",
    )

    private fun DBTableBuilder.gemTips(
        key: String, gem: String, tips: String, level: Int, xp: Int, animation: String,
        count: Int = 12,
    ) = recipe(
        "cut_tips_$key", gem, tips, level, xp, productCount = count, tool = "chisel",
        animation = animation, kind = "CUT", firstTicks = 5, repeatTicks = 5,
    )

    private fun DBTableBuilder.tippedBolts(
        gem: String, bolt: String, product: String, tips: String, metal: String,
        level: Int, xp: Int,
    ) {
        recipe(
            "tip_bolt_$gem", bolt, product, level, xp, secondary = tips,
            batchSize = 10, kind = "ARROW", animation = "human_fletching_add_bolt_tips_$metal",
        )
        recipe(
            "tip_dragon_bolt_$gem", "dragon_bolts", "dragon_bolts_unenchanted_$gem", 84, xp,
            secondary = tips, batchSize = 10, kind = "ARROW",
            animation = "human_fletching_add_bolt_tips_dragon",
        )
    }

    private fun DBTableBuilder.javelin(metal: String, level: Int, xp: Int) = recipe(
        "javelin_$metal", "javelin_shaft", "${metal}_javelin", level, xp,
        secondary = "${metal}_javelin_head", batchSize = 15, kind = "ARROW",
        animation = "human_fletching_add_arrow_tips", firstTicks = 1,
    )

    private fun DBTableBuilder.crossbow(
        wood: String, log: String, metal: String, level: Int, stockXp: Int, assemblyXp: Int,
        quest: String = "", animationWood: String = wood,
    ) {
        cut("stock_$wood", log, "xbows_crossbow_stock_$wood", level, stockXp)
        recipe(
            "assemble_crossbow_$metal", "xbows_crossbow_stock_$wood",
            "xbows_crossbow_unstrung_$metal", level, assemblyXp,
            secondary = "xbows_crossbow_limbs_$metal", tool = "hammer", kind = "MAKE",
            animation = "xbows_fletching_${animationWood}_$metal", quest = quest,
        )
        recipe(
            "string_crossbow_$metal", "xbows_crossbow_unstrung_$metal",
            "xbows_crossbow_$metal", level, stockXp, secondary = "xbows_crossbow_string",
            kind = "STRING", animation = "xbows_stringing_crossbow_$metal", quest = quest,
        )
    }

    private fun DBTableBuilder.shield(wood: String, level: Int, xp: Int) = recipe(
        "shield_$wood", "${wood}_logs", "${wood}_shield", level, xp,
        inputCount = 2, tool = "knife", kind = "CUT", animation = "human_fletching",
        firstTicks = 7, repeatTicks = 7,
    )

    private fun DBTableBuilder.recipe(
        key: String,
        input: String,
        product: String,
        level: Int,
        xp: Int,
        productCount: Int = 1,
        secondary: String? = null,
        tool: String? = null,
        batchSize: Int = 1,
        animation: String,
        kind: String,
        firstTicks: Int = 2,
        repeatTicks: Int = 2,
        inputCount: Int = 1,
        secondaryCount: Int = 1,
        slayerUnlock: String? = null,
        quest: String = "",
    ) {
        row("dbrow.fletching_$key") {
            column(INPUT, "obj.$input".asRSCM(RSCMType.OBJ))
            secondary?.let { column(SECONDARY, "obj.$it".asRSCM(RSCMType.OBJ)) }
            tool?.let { column(TOOL, "obj.$it".asRSCM(RSCMType.OBJ)) }
            column(PRODUCT, "obj.$product".asRSCM(RSCMType.OBJ))
            column(PRODUCT_COUNT, productCount)
            column(LEVEL, level)
            column(XP_TENTHS, xp)
            column(BATCH_SIZE, batchSize)
            column(ANIMATION, "seq.$animation".asRSCM(RSCMType.SEQ))
            column(KIND, kind)
            column(FIRST_TICKS, firstTicks)
            column(REPEAT_TICKS, repeatTicks)
            column(INPUT_COUNT, inputCount)
            column(SECONDARY_COUNT, secondaryCount)
            slayerUnlock?.let { column(SLAYER_UNLOCK, "dbrow.$it".asRSCM(RSCMType.DBROW)) }
            column(QUEST, quest)
            if (secondary == "feather") {
                columnRSCM(
                    SECONDARY_ALTERNATIVES,
                    "obj.hunting_stripy_bird_feather", "obj.hunting_jungle_feather",
                    "obj.hunting_polar_feather", "obj.hunting_desert_feather",
                    "obj.hunting_woodland_feather", "obj.gryphon_feather", "obj.stymphike_feather",
                )
            }
        }
    }
}
