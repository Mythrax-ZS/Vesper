package org.rsmod.content.other.grandexchange

import org.rsmod.plugin.module.PluginModule

class GrandExchangeModule : PluginModule() {
    override fun bind() {
        bind(GuidePrices::class.java).to(CacheGuidePrices::class.java)
    }
}
