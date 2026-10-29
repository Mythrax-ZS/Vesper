package org.rsmod.content.skills.fletching.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.pack.PluginPack

class FletchingPluginPack : PluginPack() {
    override fun dbTables(): List<DBTable> = listOf(FletchingTable.recipes())
}
