package org.rsmod.content.other.grandexchange

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrandExchangeRulesTest {
    @Test
    fun `tax is two percent per item floored and capped`() {
        assertEquals(0, GrandExchangeRules.taxPerItem(49, false))
        assertEquals(1, GrandExchangeRules.taxPerItem(50, false))
        assertEquals(3, GrandExchangeRules.taxPerItem(199, false))
        assertEquals(5_000_000, GrandExchangeRules.taxPerItem(Int.MAX_VALUE, false))
        assertEquals(0, GrandExchangeRules.taxPerItem(1_000_000, true))
    }

    @Test
    fun `offer validation rejects non positive values and total overflow`() {
        assertFalse(GrandExchangeRules.validOffer(0, 10))
        assertFalse(GrandExchangeRules.validOffer(10, 0))
        assertFalse(GrandExchangeRules.validOffer(-1, 10))
        assertFalse(GrandExchangeRules.validOffer(65_536, 32_768))
        assertTrue(GrandExchangeRules.validOffer(Int.MAX_VALUE, 1))
        assertTrue(GrandExchangeRules.validOffer(1, Int.MAX_VALUE))
        assertFalse(GrandExchangeRules.validOffer(2, Int.MAX_VALUE))
    }

    @Test
    fun `buy fills at guide price and refunds the difference`() {
        val offer = GeOffer(GeKind.Buy, item = 1, quantity = 10, price = 150)
        val settled = GrandExchangeRules.settle(offer, 100, false)
        assertEquals(GeStatus.Complete, settled.status)
        assertEquals(10, settled.claimItems)
        assertEquals(500, settled.claimCoins)
        assertEquals(1000, settled.spent)
        assertEquals(1500, settled.spent + settled.claimCoins)
    }

    @Test
    fun `buy below guide price stays pending and abort refunds full escrow`() {
        val offer = GeOffer(GeKind.Buy, 1, 10, 90)
        val settled = GrandExchangeRules.settle(offer, 100, false)
        assertEquals(offer, settled)
        val aborted = GrandExchangeRules.abort(settled)
        assertEquals(GeStatus.Aborted, aborted.status)
        assertEquals(900, aborted.claimCoins)
        assertEquals(0, aborted.claimItems)
    }

    @Test
    fun `sell fills at guide price after tax`() {
        val offer = GeOffer(GeKind.Sell, 1, 10, 100)
        val settled = GrandExchangeRules.settle(offer, 100, false)
        assertEquals(GeStatus.Complete, settled.status)
        assertEquals(980, settled.claimCoins)
        assertEquals(0, settled.claimItems)
    }

    @Test
    fun `sell above guide price stays pending and abort returns the items`() {
        val offer = GeOffer(GeKind.Sell, 1, 10, 101)
        assertEquals(offer, GrandExchangeRules.settle(offer, 100, false))
        val aborted = GrandExchangeRules.abort(offer)
        assertEquals(10, aborted.claimItems)
        assertEquals(0, aborted.claimCoins)
    }

    @Test
    fun `sell below guide price receives the guide price`() {
        val settled = GrandExchangeRules.settle(GeOffer(GeKind.Sell, 1, 3, 1), 1000, true)
        assertEquals(3000, settled.claimCoins)
    }

    @Test
    fun `completed or aborted offers never settle or abort again`() {
        val done = GrandExchangeRules.settle(GeOffer(GeKind.Buy, 1, 5, 10), 10, false)
        assertEquals(done, GrandExchangeRules.settle(done, 10, false))
        assertEquals(done, GrandExchangeRules.abort(done))
    }

    @Test
    fun `maximum sized buy conserves every coin`() {
        val offer = GeOffer(GeKind.Buy, 1, 2, Int.MAX_VALUE / 2)
        val settled = GrandExchangeRules.settle(offer, 1, false)
        assertEquals(offer.price.toLong() * 2, settled.spent.toLong() + settled.claimCoins)
    }

    @Test
    fun `items are conserved across every buy and sell outcome`() {
        for (guide in listOf(1, 50, 99, 100, 101, 5000)) {
            for (price in listOf(1, 99, 100, 101, 5000)) {
                val buy = GrandExchangeRules.settle(GeOffer(GeKind.Buy, 1, 7, price), guide, false)
                assertEquals(7, buy.claimItems + if (buy.status == GeStatus.Active) 7 else 0)
                val sell = GrandExchangeRules.settle(GeOffer(GeKind.Sell, 1, 7, price), guide, false)
                assertTrue(sell.claimCoins >= 0)
            }
        }
    }
}
