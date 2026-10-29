package org.rsmod.api.droptable.toml

import dtx.rs.RSPrerollTableBuilder
import dtx.rs.RSWeightedTable
import org.rsmod.api.droptable.ChanceRollStyle
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.PendingDropItemConfig
import org.rsmod.api.droptable.addRateFirstItem
import org.rsmod.game.entity.Player

public interface DropTableTomlResolver {
    public fun sharedTable(name: String): RSWeightedTable<Player, DropRollItem>

    public fun applyHooks(config: PendingDropItemConfig, hooks: TomlDropHooks)

    public fun addChanceEntry(
        builder: RSPrerollTableBuilder<Player, DropRollItem>,
        entry: TomlChanceEntry,
        style: ChanceRollStyle,
        item: DropRollItem,
    ) {
        check(!entry.clueRateModifiers && entry.npcs.isEmpty()) { "This resolver does not support clue rate modifiers or NPC filters." }
        builder.addRateFirstItem(entry.numerator, entry.denominator, style, item)
    }

    public fun applyBrimstoneKeyRoll(
        builder: dtx.rs.RSPrerollTableBuilder<Player, DropRollItem>,
        konarTaskBonus: Boolean,
    )
}
