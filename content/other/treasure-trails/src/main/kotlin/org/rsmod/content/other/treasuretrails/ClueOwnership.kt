package org.rsmod.content.other.treasuretrails

import dev.openrune.ServerCacheManager
import dev.openrune.types.ItemServerType
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.Inventory

enum class ClueTier { Beginner, Easy, Medium, Hard, Elite, Master }

object ClueOwnership {
    fun tier(type: ItemServerType): ClueTier? = tier(type.internalName)

    fun tier(symbol: String): ClueTier? {
        val name = symbol.removePrefix("obj.")
        if (!isBox(symbol) && !isActive(symbol)) return null
        return ClueTier.entries.firstOrNull { tier ->
            val key = tier.name.lowercase()
            name.contains("_${key}_") || name.endsWith("_$key")
        }
    }

    fun isBox(symbol: String): Boolean = symbol.removePrefix("obj.").startsWith("league_clue_box_")

    fun isActive(symbol: String): Boolean {
        val name = symbol.removePrefix("obj.")
        return (name.startsWith("trail_clue_") || name.startsWith("trail_easy_") ||
            name.startsWith("trail_medium_") || name.startsWith("trail_hard_") ||
            name.startsWith("trail_elite_")) &&
            !name.contains("reward_casket") || name.startsWith("wc_clue_nest_") ||
            name.startsWith("fishing_clue_bottle_") || name.startsWith("mining_clue_geode_")
    }

    fun capacity(player: Player, tier: ClueTier): Int {
        val name = tier.name.lowercase()
        return 2 + player.vars["varbit.scroll_case_${name}_minor"] +
            player.vars["varbit.scroll_case_${name}_major"] + player.vars["varbit.scroll_case_mimic"]
    }

    fun count(player: Player, tier: ClueTier, activeOnly: Boolean = false): Long =
        storage(player).sumOf { inventory ->
            inventory.objs.filterNotNull().sumOf { item ->
                val type = ServerCacheManager.getItem(item.id)
                if (type != null && tier(type) == tier && (!activeOnly || !isBox(type.internalName))) {
                    item.count.toLong()
                } else 0L
            }
        }

    private fun storage(player: Player): Set<Inventory> = buildSet {
        add(player.inv)
        add(player.worn)
        player.invMap["inv.bank"]?.let(::add)
        player.invMap["inv.gravestone"]?.let(::add)
        player.invMap["inv.death_permanent"]?.let(::add)
    }
}
