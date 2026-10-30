package org.rsmod.content.other.grandexchange

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.invtx.delete
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.invtx.invDel
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.game.entity.Player

enum class GeResult {
    Ok,
    InvalidSlot,
    SlotBusy,
    NotTradeable,
    InvalidOffer,
    MissingCoins,
    MissingItems,
    NothingToAbort,
    NothingToCollect,
    NoSpace,
}

@Singleton
class GrandExchange @Inject constructor(private val prices: GuidePrices) {
    fun offers(player: Player): List<GeOffer> =
        List(GrandExchangeRules.SLOT_COUNT) { GeSlots.read(player, it) }

    fun buy(player: Player, slot: Int, item: Int, quantity: Int, price: Int): GeResult {
        val tradeable = tradeableItem(item) ?: return GeResult.NotTradeable
        val failure = validate(player, slot, quantity, price)
        if (failure != null) return failure
        val total = price * quantity
        if (!player.invDel(player.inv, COINS, total).success) return GeResult.MissingCoins
        place(player, slot, GeOffer(GeKind.Buy, tradeable, quantity, price))
        return GeResult.Ok
    }

    fun sell(player: Player, slot: Int, item: Int, quantity: Int, price: Int): GeResult {
        val tradeable = tradeableItem(item) ?: return GeResult.NotTradeable
        val failure = validate(player, slot, quantity, price)
        if (failure != null) return failure
        if (!takeItems(player, tradeable, quantity)) return GeResult.MissingItems
        place(player, slot, GeOffer(GeKind.Sell, tradeable, quantity, price))
        return GeResult.Ok
    }

    fun abort(player: Player, slot: Int): GeResult {
        if (slot !in 0 until GrandExchangeRules.SLOT_COUNT) return GeResult.InvalidSlot
        val offer = GeSlots.read(player, slot)
        if (!offer.canAbort) return GeResult.NothingToAbort
        GeSlots.write(player, slot, GrandExchangeRules.abort(offer))
        return GeResult.Ok
    }

    fun collect(player: Player, slot: Int, asNote: Boolean = true): GeResult {
        if (slot !in 0 until GrandExchangeRules.SLOT_COUNT) return GeResult.InvalidSlot
        val offer = GeSlots.read(player, slot)
        if (offer.isEmpty || !offer.hasClaim) return GeResult.NothingToCollect
        val coins = deliverCoins(player, offer.claimCoins)
        val items = deliverItems(player, offer.item, offer.claimItems, asNote)
        val remaining = offer.copy(claimCoins = offer.claimCoins - coins, claimItems = offer.claimItems - items)
        if (!remaining.hasClaim && remaining.status != GeStatus.Active) {
            GeSlots.clear(player, slot)
        } else {
            GeSlots.write(player, slot, remaining)
        }
        return if (coins + items == 0) GeResult.NoSpace else GeResult.Ok
    }

    fun refresh(player: Player) {
        for (slot in 0 until GrandExchangeRules.SLOT_COUNT) {
            val offer = GeSlots.read(player, slot)
            if (offer.isEmpty || offer.status != GeStatus.Active) continue
            GeSlots.write(player, slot, settle(offer))
        }
    }

    private fun place(player: Player, slot: Int, offer: GeOffer) {
        GeSlots.write(player, slot, settle(offer))
    }

    private fun settle(offer: GeOffer): GeOffer =
        GrandExchangeRules.settle(offer, prices.price(offer.item), prices.taxExempt(offer.item))

    private fun validate(player: Player, slot: Int, quantity: Int, price: Int): GeResult? =
        when {
            slot !in 0 until GrandExchangeRules.SLOT_COUNT -> GeResult.InvalidSlot
            !GrandExchangeRules.validOffer(quantity, price) -> GeResult.InvalidOffer
            !GeSlots.read(player, slot).isEmpty -> GeResult.SlotBusy
            else -> null
        }

    private fun tradeableItem(item: Int): Int? {
        val type = ServerCacheManager.getItem(item) ?: return null
        val base = if (type.isCert) type.certlink else item
        return base.takeIf { it != COINS && prices.price(it) > 0 }
    }

    private fun takeItems(player: Player, item: Int, quantity: Int): Boolean {
        val type = ServerCacheManager.getItem(item) ?: return false
        val noted = if (type.canCert) type.certlink else null
        val plain = held(player, item)
        val certs = noted?.let { held(player, it) } ?: 0
        if (plain.toLong() + certs < quantity) return false
        val fromPlain = minOf(plain, quantity)
        val fromCerts = quantity - fromPlain
        return player
            .invTransaction(player.inv) {
                val inv = select(player.inv)
                if (fromPlain > 0) delete(inv = inv, obj = item, count = fromPlain)
                if (fromCerts > 0 && noted != null) delete(inv = inv, obj = noted, count = fromCerts)
            }
            .success
    }

    private fun held(player: Player, item: Int): Int =
        player.inv.objs.filterNotNull().filter { it.id == item }.sumOf { it.count.toLong() }
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun deliverCoins(player: Player, claim: Int): Int {
        if (claim <= 0) return 0
        if (player.invAdd(player.inv, COINS, claim).success) return claim
        val room = Int.MAX_VALUE - held(player, COINS)
        val partial = minOf(claim, room)
        val hasSlot = held(player, COINS) > 0 || !player.inv.isFull()
        if (partial <= 0 || !hasSlot) return 0
        return if (player.invAdd(player.inv, COINS, partial).success) partial else 0
    }

    private fun deliverItems(player: Player, item: Int, claim: Int, asNote: Boolean): Int {
        if (claim <= 0) return 0
        val type = ServerCacheManager.getItem(item) ?: return 0
        val delivered = if (asNote && type.canCert && !type.isStackable) type.certlink else item
        if (player.invAdd(player.inv, delivered, claim).success) return claim
        if (type.isStackable || delivered != item) return 0
        val partial = minOf(claim, player.inv.freeSpace())
        if (partial <= 0) return 0
        return if (player.invAdd(player.inv, item, partial).success) partial else 0
    }

    private companion object {
        val COINS: Int by lazy { "obj.coins".asRSCM(RSCMType.OBJ) }
    }
}
