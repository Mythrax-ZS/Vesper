package org.rsmod.content.skills.agility

import jakarta.inject.Inject
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.shops.Shops
import org.rsmod.api.shops.operation.ShopOperationMap
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class GraceScript @Inject constructor(
    private val shops: Shops,
    private val operations: GraceShopOperations,
    private val operationMap: ShopOperationMap,
) : PluginScript() {
    override fun ScriptContext.startup() {
        operationMap.register(GraceShopOperations.CURRENCY, operations)
        onOpNpc3("npc.rooftops_grace") {
            shops.open(
                player = player,
                title = "Grace's Graceful Clothing",
                shopInv = GraceShopOperations.INVENTORY,
                buyPercentage = 80.0,
                sellPercentage = 100.0,
                changePercentage = 0.0,
                currency = GraceShopOperations.CURRENCY,
            )
        }
    }
}
