package org.rsmod.content.other.grandexchange

import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.game.entity.Player

object GeSlots {
    fun read(player: Player, slot: Int): GeOffer {
        val kind = GeKind.of(player.vars[name(slot, "kind")])
        if (kind == GeKind.Empty) return GeOffer.EMPTY
        return GeOffer(
            kind = kind,
            item = player.vars[name(slot, "item")],
            quantity = player.vars[name(slot, "quantity")],
            price = player.vars[name(slot, "price")],
            filled = player.vars[name(slot, "filled")],
            spent = player.vars[name(slot, "spent")],
            claimItems = player.vars[name(slot, "claim_item")],
            claimCoins = player.vars[name(slot, "claim_coins")],
            status = GeStatus.of(player.vars[name(slot, "status")]),
        )
    }

    fun write(player: Player, slot: Int, offer: GeOffer) {
        put(player, slot, "kind", offer.kind.id)
        put(player, slot, "item", offer.item)
        put(player, slot, "quantity", offer.quantity)
        put(player, slot, "price", offer.price)
        put(player, slot, "filled", offer.filled)
        put(player, slot, "spent", offer.spent)
        put(player, slot, "claim_item", offer.claimItems)
        put(player, slot, "claim_coins", offer.claimCoins)
        put(player, slot, "status", offer.status.id)
    }

    fun clear(player: Player, slot: Int) = write(player, slot, GeOffer.EMPTY)

    private fun put(player: Player, slot: Int, field: String, value: Int) =
        VarPlayerIntMapSetter.set(player, name(slot, field), value)

    private fun name(slot: Int, field: String) = "varp.ge_slot_${slot}_$field"
}
