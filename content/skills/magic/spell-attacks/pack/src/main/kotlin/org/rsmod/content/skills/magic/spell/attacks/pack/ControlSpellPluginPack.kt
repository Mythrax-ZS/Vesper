package org.rsmod.content.skills.magic.spell.attacks.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType
import dev.openrune.pack.PluginPack
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType

class ControlSpellPluginPack : PluginPack() {
    override fun dbTables(): List<DBTable> = listOf(
        dbTable("dbtable.standard_control_spells", serverOnly = true) {
            column("spell", 0, VarType.OBJ)
            column("kind", 1, VarType.STRING)
            column("duration", 2, VarType.INT)
            column("max_hit", 3, VarType.INT)
            column("miss_xp_tenths", 4, VarType.INT)
            column("hit_xp_tenths", 5, VarType.INT)
            column("animation", 6, VarType.STRING)
            column("staff_animation", 7, VarType.STRING)
            column("launch", 8, VarType.STRING)
            column("travel", 9, VarType.STRING)
            column("miss_travel", 10, VarType.STRING)
            column("impact", 11, VarType.STRING)
            column("cast_sound", 12, VarType.STRING)
            column("hit_sound", 13, VarType.STRING)
            column("projectile", 14, VarType.STRING)
            column("swampbark_bonus", 15, VarType.INT)

            row("dbrow.standard_bind") {
                column(0, "obj.20_bind".asRSCM(RSCMType.OBJ))
                column(1, "BIND")
                column(2, 8)
                column(3, 0)
                column(4, 300)
                column(5, 310)
                column(6, "seq.human_castentangle")
                column(7, "seq.human_castentangle_staff")
                column(8, "spotanim.entangle_casting")
                column(9, "spotanim.entangle_travel")
                column(11, "spotanim.bind_impact")
                column(12, "synth.bind_cast")
                column(13, "synth.bind_impact")
                column(14, "projanim.bind")
                column(15, 2)
            }
            row("dbrow.standard_snare") {
                column(0, "obj.50_snare".asRSCM(RSCMType.OBJ))
                column(1, "BIND")
                column(2, 16)
                column(3, 2)
                column(4, 600)
                column(5, 610)
                column(6, "seq.human_castentangle")
                column(7, "seq.human_castentangle_staff")
                column(8, "spotanim.entangle_casting")
                column(9, "spotanim.entangle_travel")
                column(11, "spotanim.snare_impact")
                column(12, "synth.snare_all")
                column(14, "projanim.bind")
                column(15, 2)
            }
            row("dbrow.standard_entangle") {
                column(0, "obj.79_entangle".asRSCM(RSCMType.OBJ))
                column(1, "BIND")
                column(2, 24)
                column(3, 5)
                column(4, 700)
                column(5, 890)
                column(6, "seq.human_castentangle")
                column(7, "seq.human_castentangle_staff")
                column(8, "spotanim.entangle_casting")
                column(9, "spotanim.entangle_travel")
                column(11, "spotanim.entangle_impact")
                column(12, "synth.entangle_cast_and_fire")
                column(13, "synth.entangle_hit")
                column(14, "projanim.bind")
                column(15, 2)
            }
            row("dbrow.standard_teleblock") {
                column(0, "obj.85_teleport_block".asRSCM(RSCMType.OBJ))
                column(1, "TELEBLOCK")
                column(2, 500)
                column(3, 0)
                column(4, 836)
                column(5, 950)
                column(6, "seq.human_casting_tele_block")
                column(7, "seq.human_casting_tele_block_staff")
                // Native failure projectile is named. Success projectile is not independently
                // identified; do not reuse the historical numeric id now assigned to Ballista.
                column(10, "spotanim.tele_block_travel_forfail")
                column(11, "spotanim.tele_block_impact")
                column(12, "synth.teleportblock_cast")
                column(13, "synth.teleportblock_impact")
                column(14, "projanim.magic_spell")
                column(15, 0)
            }
        }
    )
}
