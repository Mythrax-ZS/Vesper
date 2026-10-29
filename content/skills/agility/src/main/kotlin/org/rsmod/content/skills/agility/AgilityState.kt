package org.rsmod.content.skills.agility

import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

internal var Player.agilityLapCourse by intVarp("varp.agility_lap_course")
internal var Player.agilityLapStep by intVarp("varp.agility_lap_step")
internal var Player.agilityRecoveryStart by intVarp("varp.agility_recovery_start")
internal var Player.agilityRecoveryEnd by intVarp("varp.agility_recovery_end")

internal fun Player.clearAgilityLap() {
    agilityLapCourse = 0
    agilityLapStep = 0
}

internal fun Player.finishAgilityObstacle(obstacle: AgilityObstacle): Boolean {
    val step = obstacle.data.step
    if (step == 1) {
        agilityLapCourse = obstacle.course.key
        agilityLapStep = 1
    } else if (agilityLapCourse == obstacle.course.key && agilityLapStep == step - 1) {
        agilityLapStep = step
    } else {
        clearAgilityLap()
        return false
    }
    if (agilityLapStep != obstacle.course.steps) return false
    clearAgilityLap()
    return true
}

internal fun Player.clearAgilityRecovery() {
    agilityRecoveryStart = 0
    agilityRecoveryEnd = 0
}

internal fun Player.recoverAgility(collision: CollisionFlagMap) {
    clearAgilityLap()
    val packedStart = agilityRecoveryStart
    val packedEnd = agilityRecoveryEnd
    clearAgilityRecovery()
    if (packedStart == 0 || packedEnd == 0) return
    val start = CoordGrid(packedStart - 1)
    val end = CoordGrid(packedEnd - 1)
    if (coords != start && coords != end) return
    if (!collision.isZoneAllocated(start.x, start.z, start.level)) return
    pendingExactMove = null
    if (isSlotAssigned) PathingEntityCommon.telejump(this, collision, start) else coords = start
}
