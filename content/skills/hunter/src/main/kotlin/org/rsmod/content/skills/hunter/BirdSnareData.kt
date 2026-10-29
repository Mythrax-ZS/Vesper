package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.types.ObjectServerType
import org.rsmod.api.enums.NamedEnums
import org.rsmod.api.enums.SkillGuideEnums
import org.rsmod.api.table.SkillFeaturesRow
import org.rsmod.api.table.hunter.HunterSnareBirdsRow

internal class BirdSnareData(val row: HunterSnareBirdsRow) {
    val npc = checkNotNull(ServerCacheManager.getNpc(row.npc.id))
    val trapping = checkNotNull(ServerCacheManager.getObject(row.trappingLoc.id))
    val caught = checkNotNull(ServerCacheManager.getObject(row.caughtLoc.id))
    val feather = checkNotNull(ServerCacheManager.getItem(row.feather.id))
    val level = row.guide.hunterLevel()
}

internal fun SkillFeaturesRow.hunterLevel(): Int {
    val hunter = checkNotNull(ServerCacheManager.getStats("stat.hunter".asRSCM(RSCMType.STAT)))
    val guideId = SkillGuideEnums.skill_guide_skill_to_id.getValue(hunter)
    return skill.chunked(3).single { it[0] == guideId }[1]
}

internal object BirdSnares {
    const val SNARE = "obj.hunting_ojibway_bird_snare"
    const val BONES = "obj.bones"
    const val MEAT = "obj.spit_raw_bird_meat"
    const val OPEN = "loc.hunting_ojibway_trap"
    const val BROKEN = "loc.hunting_ojibway_trap_broken"
    const val FAILING = "loc.hunting_ojibway_trap_failing"
    const val LAY_ANIM = "seq.human_laytrap"
    const val SET_ANIM = "seq.hunting_setting_trap_small"
    const val PICKUP_ANIM = "seq.human_pickupfloor"
    const val LAND_ANIM = "seq.bird_landontrap"
    const val LEAVE_ANIM = "seq.bird_leavetrap"
    const val CATCH_ANIM = "seq.bird_caughtintrap"
    const val FAIL_ANIM = "seq.hunting_ojibway_fail"
    const val TRAP_LIFETIME = 100

    // Inferred from Mod Ash's box-trap description, not established for bird snares.
    const val SEARCH_INTERVAL = 3
    const val SEARCH_RANGE = 2

    val BIRDS by lazy { HunterSnareBirdsRow.all().map(::BirdSnareData) }
    val BY_NPC by lazy { BIRDS.associateBy { it.npc.id } }
    val TRAP_LIMITS by lazy {
        NamedEnums.hunter_trap_limits.map { (guide, limit) ->
            SkillFeaturesRow.getRow(guide.id).hunterLevel() to checkNotNull(limit)
        }
    }

    fun item(symbol: String): ItemServerType =
        checkNotNull(ServerCacheManager.getItem(symbol.asRSCM(RSCMType.OBJ)))

    fun loc(symbol: String): ObjectServerType =
        checkNotNull(ServerCacheManager.getObject(symbol.asRSCM(RSCMType.LOC)))

    fun animationTicks(symbol: String): Int =
        checkNotNull(ServerCacheManager.getAnim(symbol.asRSCM(RSCMType.SEQ))).tickDuration.coerceAtLeast(1)
}
