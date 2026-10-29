package org.rsmod.content.drops.tables.shared

import dtx.rs.RSWeightedTable
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.rsPlayerWeightedTable
import org.rsmod.game.entity.Player

internal val TREE_HERB_SEED_DROP_TABLE: RSWeightedTable<Player, DropRollItem> =
    rsPlayerWeightedTable(total = 250) {
        name("Tree-herb seed drop table")
        30 weight "obj.ranarr_seed" count 1
        28 weight "obj.snapdragon_seed" count 1
        22 weight "obj.torstol_seed" count 1
        21 weight "obj.watermelon_seed" count 15
        20 weight "obj.willow_seed" count 1
        18 weight "obj.mahogany_seed" count 1
        18 weight "obj.maple_seed" count 1
        18 weight "obj.teak_seed" count 1
        18 weight "obj.yew_seed" count 1
        14 weight "obj.papaya_tree_seed" count 1
        11 weight "obj.magic_tree_seed" count 1
        10 weight "obj.palm_tree_seed" count 1
        8 weight "obj.spirit_tree_seed" count 1
        6 weight "obj.dragonfruit_tree_seed" count 1
        4 weight "obj.celastrus_tree_seed" count 1
        4 weight "obj.redwood_tree_seed" count 1
    }
