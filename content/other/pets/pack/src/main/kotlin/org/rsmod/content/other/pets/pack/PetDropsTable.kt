package org.rsmod.content.other.pets.pack

import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

/**
 * Boss pets keyed by the npc that drops them, so a pet can be wired up without editing that npc's
 * drop table. Npcs whose drop table already lists the pet item must not appear here, or the pet
 * would be rolled twice.
 */
object PetDropsTable {
    const val NPC = 0
    const val PET = 1
    const val RATE = 2

    fun drops() = dbTable("dbtable.pet_drops", serverOnly = true) {
        column("npc", NPC, VarType.NPC)
        column("pet", PET, VarType.OBJ)
        column("rate", RATE, VarType.INT)

        fun drop(npc: String, pet: String, rate: Int) {
            row("dbrow.pet_drop_${npc.removePrefix("npc.")}") {
                columnRSCM(NPC, npc)
                columnRSCM(PET, pet)
                column(RATE, rate)
            }
        }

        drop("npc.mad_angel", "obj.madangelpet", 2000)
        drop("npc.mad_angel_quest", "obj.madangelpet", 2000)
        drop("npc.inferno_tzkalzuk_placeholder", "obj.infernopet", 100)
        drop("npc.maggot_king", "obj.maggotkingpet", 3500)
        drop("npc.gargboss_dusk_phase4", "obj.dawnpet", 3000)
    }
}
