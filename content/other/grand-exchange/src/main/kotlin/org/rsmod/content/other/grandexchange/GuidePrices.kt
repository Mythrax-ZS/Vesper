package org.rsmod.content.other.grandexchange

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.enums.GeEnums

interface GuidePrices {
    fun price(item: Int): Int

    fun buyLimit(item: Int): Int

    fun taxExempt(item: Int): Boolean
}

class CacheGuidePrices : GuidePrices {
    override fun price(item: Int): Int = type(item)?.let { GeEnums.ge_guide_price[it] } ?: 0

    override fun buyLimit(item: Int): Int = type(item)?.let { GeEnums.ge_buy_limit[it] } ?: 0

    override fun taxExempt(item: Int): Boolean = item == BOND

    private fun type(item: Int) = ServerCacheManager.getItem(item)

    private companion object {
        val BOND: Int by lazy { "obj.osrs_bond".asRSCM(RSCMType.OBJ) }
    }
}
