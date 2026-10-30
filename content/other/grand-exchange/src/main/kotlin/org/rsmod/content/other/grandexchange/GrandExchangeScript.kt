package org.rsmod.content.other.grandexchange

import dev.openrune.ServerCacheManager
import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class GrandExchangeScript
@Inject
constructor(private val ge: GrandExchange, private val prices: GuidePrices) : PluginScript() {
    override fun ScriptContext.startup() {
        for (clerk in CLERKS) {
            onOpNpc1(clerk) { menu() }
            onOpNpc2(clerk) { menu() }
            onOpNpc3(clerk) { collectAll() }
        }
        onOpLoc1(BOOTH) { menu() }
        onOpLoc2(BOOTH) { collectAll() }
    }

    private suspend fun ProtectedAccess.menu() {
        ge.refresh(player)
        when (
            choice5(
                "Buy an item",
                Action.Buy,
                "Sell an item",
                Action.Sell,
                "Collect",
                Action.Collect,
                "View offers",
                Action.View,
                "Cancel an offer",
                Action.Abort,
                title = "Grand Exchange",
            )
        ) {
            Action.Buy -> offer(GeKind.Buy)
            Action.Sell -> offer(GeKind.Sell)
            Action.Collect -> collectAll()
            Action.View -> showOffers()
            Action.Abort -> abortOffer()
        }
    }

    private suspend fun ProtectedAccess.offer(kind: GeKind) {
        val slot = ge.offers(player).indexOfFirst { it.isEmpty }
        if (slot < 0) {
            mesbox("All of your Grand Exchange slots are in use. Collect or cancel an offer.")
            return
        }
        val item = findItem(stringDialog("Enter the name of the item:")) ?: return
        val guide = prices.price(item)
        val quantity = countDialog("How many do you wish to ${kind.verb}?")
        val price = countDialog("Set a price for each item. (Guide price: ${guide.format()} gp)")
        val result =
            if (kind == GeKind.Buy) ge.buy(player, slot, item, quantity, price)
            else ge.sell(player, slot, item, quantity, price)
        mesbox(result.message(kind))
    }

    private suspend fun ProtectedAccess.findItem(query: String): Int? {
        val name = query.trim().lowercase()
        if (name.isEmpty()) return null
        val candidates =
            ServerCacheManager.getItems().values.filter {
                !it.isCert && it.name.isNotEmpty() && prices.price(it.id) > 0
            }
        val exact = candidates.filter { it.name.equals(name, ignoreCase = true) }
        val match = exact.minByOrNull { it.id } ?: candidates.filter { it.name.contains(name, true) }.minByOrNull { it.name.length }
        if (match == null) {
            mesbox("Could not find a tradeable item named '$query'.")
            return null
        }
        return match.id
    }

    private suspend fun ProtectedAccess.showOffers() {
        ge.refresh(player)
        val lines =
            ge.offers(player).mapIndexedNotNull { slot, offer ->
                if (offer.isEmpty) null else "${slot + 1}: ${offer.describe()}"
            }
        mesbox(if (lines.isEmpty()) "You have no Grand Exchange offers." else lines.joinToString("<br>"))
    }

    private suspend fun ProtectedAccess.abortOffer() {
        ge.refresh(player)
        val active = ge.offers(player).withIndex().firstOrNull { it.value.canAbort }
        if (active == null) {
            mesbox("You have no active offers to cancel.")
            return
        }
        ge.abort(player, active.index)
        mesbox("Offer ${active.index + 1} cancelled. Collect your refund.")
    }

    private suspend fun ProtectedAccess.collectAll() {
        ge.refresh(player)
        var collected = false
        var blocked = false
        for (slot in 0 until GrandExchangeRules.SLOT_COUNT) {
            when (ge.collect(player, slot)) {
                GeResult.Ok -> collected = true
                GeResult.NoSpace -> blocked = true
                else -> Unit
            }
        }
        player.mes(
            when {
                blocked -> "You do not have enough inventory space to collect everything."
                collected -> "You collect your Grand Exchange offers."
                else -> "You have nothing to collect."
            }
        )
    }

    private fun GeOffer.describe(): String {
        val name = ServerCacheManager.getItem(item)?.name ?: "item"
        val state =
            when (status) {
                GeStatus.Active -> "pending"
                GeStatus.Complete -> "complete"
                GeStatus.Aborted -> "cancelled"
            }
        return "${kind.verb.replaceFirstChar { it.uppercase() }} $quantity x $name at ${price.format()} ($state)"
    }

    private fun GeResult.message(kind: GeKind): String =
        when (this) {
            GeResult.Ok -> "Your ${kind.verb} offer was placed."
            GeResult.MissingCoins -> "You do not have enough coins."
            GeResult.MissingItems -> "You do not have enough of that item."
            GeResult.NotTradeable -> "That item cannot be traded on the Grand Exchange."
            GeResult.InvalidOffer -> "That quantity and price are not valid."
            GeResult.SlotBusy,
            GeResult.InvalidSlot -> "No free Grand Exchange slot."
            else -> "The offer could not be placed."
        }

    private fun Int.format(): String = "%,d".format(this)

    private val GeKind.verb: String
        get() = if (this == GeKind.Buy) "buy" else "sell"

    private enum class Action {
        Buy,
        Sell,
        Collect,
        View,
        Abort,
    }

    private companion object {
        val CLERKS = listOf("npc.ge_clerk_1", "npc.ge_clerk_2", "npc.ge_clerk_3", "npc.ge_clerk_4")
        const val BOOTH = "loc.exchange_bank_wall_exchange"
    }
}
