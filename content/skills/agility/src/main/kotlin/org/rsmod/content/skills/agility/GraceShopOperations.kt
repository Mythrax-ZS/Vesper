package org.rsmod.content.skills.agility

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import jakarta.inject.Inject
import org.rsmod.api.market.MarketPrices
import org.rsmod.api.shops.currency.InvObjShopCurrency
import org.rsmod.api.shops.operation.StandardCurrencyShopOperations
import org.rsmod.api.shops.restock.ShopRestockProcess
import org.rsmod.game.shop.Shop

class GraceShopOperations @Inject constructor(
    restockProcess: ShopRestockProcess,
    marketPrices: MarketPrices,
) : StandardCurrencyShopOperations(
    InvObjShopCurrency("obj.grace", "mark of grace", "marks of grace"),
    restockProcess,
    marketPrices,
) {
    override fun canSell(type: ItemServerType, shop: Shop): Boolean =
        shop.inv.type.id == INVENTORY.asRSCM() &&
            shop.inv.type.stock.any { it.obj == type.id }

    internal companion object {
        const val CURRENCY = "currency.mark_of_grace"
        const val INVENTORY = "inv.skill_guide_agility_courses"
    }
}
