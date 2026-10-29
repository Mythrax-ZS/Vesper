package org.rsmod.content.skills.agility.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.pack.PluginPack

class AgilityPluginPack : PluginPack() {
    override fun dbTables(): List<DBTable> =
        listOf(AgilityTables.courses(), AgilityTables.obstacles(), AgilityMarksTable.spawns())
}
