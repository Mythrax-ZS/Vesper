package org.rsmod.content.skills.mining.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.pack.PluginPack

class MiningPluginPack : PluginPack() {
    override fun dbTables(): List<DBTable> = listOf(MiningClueGeodesTable.create())
}
