package org.rsmod.content.bosses.kbd.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType
import dev.openrune.pack.PluginPack

class KbdPluginPack : PluginPack() {
    override fun dbTables(): List<DBTable> = listOf(
        dbTable("dbtable.kbd_breath_protection", serverOnly = true) {
            column("normal_max", 0, VarType.INT)
            column("special_max", 1, VarType.INT)
            column("potion_reduction", 2, VarType.INT)
            protection("unprotected", 65, 50)
            protection("prayer", 20, 15)
            protection("shield", 15, 10)
        },
    )

    private fun DBTableBuilder.protection(name: String, normalMax: Int, specialMax: Int) {
        row("dbrow.kbd_breath_$name") {
            column(0, normalMax)
            column(1, specialMax)
            column(2, 15)
        }
    }
}
