package org.rsmod.content.other.grandexchange

object GrandExchangeRules {
    const val SLOT_COUNT = 8
    const val TAX_PERCENT = 2L
    const val TAX_CAP_PER_ITEM = 5_000_000L

    fun taxPerItem(unitPrice: Int, exempt: Boolean): Long {
        if (exempt || unitPrice <= 0) return 0
        return (unitPrice.toLong() * TAX_PERCENT / 100).coerceAtMost(TAX_CAP_PER_ITEM)
    }

    fun validOffer(quantity: Int, price: Int): Boolean =
        quantity > 0 && price > 0 && quantity.toLong() * price <= Int.MAX_VALUE

    fun fillsAt(kind: GeKind, offerPrice: Int, guidePrice: Int): Boolean =
        when (kind) {
            GeKind.Buy -> guidePrice in 1..offerPrice
            GeKind.Sell -> guidePrice in 1..Int.MAX_VALUE && offerPrice <= guidePrice
            GeKind.Empty -> false
        }

    fun settle(offer: GeOffer, guidePrice: Int, taxExempt: Boolean): GeOffer {
        if (offer.status != GeStatus.Active || !fillsAt(offer.kind, offer.price, guidePrice)) {
            return offer
        }
        val remaining = offer.quantity - offer.filled
        if (remaining <= 0) return offer.copy(status = GeStatus.Complete)
        val gross = guidePrice.toLong() * remaining
        return when (offer.kind) {
            GeKind.Buy -> {
                val refund = (offer.price.toLong() - guidePrice) * remaining
                offer.copy(
                    filled = offer.quantity,
                    spent = Math.addExact(offer.spent.toLong(), gross).toInt(),
                    claimItems = Math.addExact(offer.claimItems.toLong(), remaining.toLong()).toInt(),
                    claimCoins = Math.addExact(offer.claimCoins.toLong(), refund).toInt(),
                    status = GeStatus.Complete,
                )
            }
            GeKind.Sell -> {
                val net = gross - taxPerItem(guidePrice, taxExempt) * remaining
                offer.copy(
                    filled = offer.quantity,
                    spent = Math.addExact(offer.spent.toLong(), net).toInt(),
                    claimCoins = Math.addExact(offer.claimCoins.toLong(), net).toInt(),
                    status = GeStatus.Complete,
                )
            }
            GeKind.Empty -> offer
        }
    }

    fun abort(offer: GeOffer): GeOffer {
        if (!offer.canAbort) return offer
        val remaining = offer.quantity - offer.filled
        return when (offer.kind) {
            GeKind.Buy ->
                offer.copy(
                    claimCoins =
                        Math.addExact(offer.claimCoins.toLong(), offer.price.toLong() * remaining)
                            .toInt(),
                    status = GeStatus.Aborted,
                )
            GeKind.Sell ->
                offer.copy(
                    claimItems = Math.addExact(offer.claimItems.toLong(), remaining.toLong()).toInt(),
                    status = GeStatus.Aborted,
                )
            GeKind.Empty -> offer
        }
    }
}
