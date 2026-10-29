package org.rsmod.content.other.treasuretrails

import dev.openrune.ServerCacheManager
import org.rsmod.api.random.GameRandom
import org.rsmod.api.table.TreasureTrailsBeginnerRewardsRow
import org.rsmod.game.inv.InvObj

/** Complete published adult-account distribution; no approximate rare-table replacement. */
internal object BeginnerRewards {
    val rows: List<TreasureTrailsBeginnerRewardsRow> by lazy {
        TreasureTrailsBeginnerRewardsRow.all().sortedBy { it.rowId }.also { rows ->
            check(rows.size == 74 && rows.sumOf { it.weight } == 44_280) {
                "Incomplete beginner reward table"
            }
            check(rows.map { it.item.id }.distinct().size == rows.size)
            rows.forEach {
                check(it.weight > 0 && it.minimum > 0 && it.maximum >= it.minimum)
                check(!it.item.isDummyItem)
                if (it.noted) check(it.item.certlink > 0 && it.item.certtemplate == 0)
            }
        }
    }
    val totalWeight: Int get() = rows.sumOf { it.weight }

    fun select(roll: Int): TreasureTrailsBeginnerRewardsRow {
        require(roll in 0 until totalWeight)
        var remainder = roll
        for (row in rows) {
            if (remainder < row.weight) return row
            remainder -= row.weight
        }
        error("Beginner reward weights did not cover roll $roll")
    }

    fun roll(random: GameRandom): List<InvObj> = List(random.of(1, 3)) {
        val row = select(random.of(totalWeight))
        val id = if (row.noted) row.item.certlink else row.item.id
        val item = checkNotNull(ServerCacheManager.getItem(id)) { "Missing beginner reward item $id" }
        InvObj(item, random.of(row.minimum, row.maximum))
    }
}
