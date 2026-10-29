package org.rsmod.content.skills.agility

import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.agilityLvl
import org.rsmod.api.table.agility.AgilityCoursesRow
import org.rsmod.game.entity.Player
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.RouteFinding
import org.rsmod.routefinder.collision.CollisionFlagMap

internal suspend fun ProtectedAccess.crossAgilityObstacle(
    obstacle: AgilityObstacle,
    collision: CollisionFlagMap,
    onLapCompleted: (Player, AgilityCoursesRow) -> Unit = { _, _ -> },
): Boolean {
    if (player.agilityLvl < obstacle.course.level) {
        mes("You need an Agility level of ${obstacle.course.level} to negotiate this obstacle.")
        return false
    }
    val entry = obstacle.entryFrom(coords)
    if (coords.level != entry.level || coords.chebyshevDistance(entry) > MAX_APPROACH_TICKS) {
        mes("You can't negotiate this obstacle from here.")
        return false
    }
    arriveDelay()
    if (!approachAgilityEntry(entry, collision)) return false

    val exit = obstacle.exitFrom(entry)
    if (!collision.isZoneAllocated(exit.x, exit.z, exit.level)) return false
    ifCloseChat()
    player.abortRoute()
    player.agilityRecoveryStart = entry.packed + 1
    player.agilityRecoveryEnd = exit.packed + 1
    var completed = false
    try {
        faceSquare(CoordGrid(exit.x, exit.z, entry.level))
        obstacle.enterAnimation?.let {
            anim(it.internalName)
            delay(it)
        }
        if (coords != entry) return false
        val ticks = obstacle.travelTicks(entry)
        anim(obstacle.animation.internalName)
        if (entry.level == exit.level) {
            exactMove(entry, exit, 0, ticks * CLIENT_CYCLES_PER_TICK, agilityFacing(entry, exit),
                TeleportType.Exempt)
            if (coords != exit) return false
            delay(ticks)
        } else {
            repeat(ticks / obstacle.animation.tickDuration) {
                anim(obstacle.animation.internalName)
                delay(obstacle.animation)
                if (coords != entry) return false
            }
            telejump(exit, TeleportType.Exempt)
        }
        if (coords != exit) return false
        obstacle.leaveAnimation?.let {
            anim(it.internalName)
            delay(it)
        }
        if (coords != exit) return false

        val lap = player.finishAgilityObstacle(obstacle)
        val xpTenths = obstacle.data.xpTenths + if (lap) obstacle.course.bonusTenths else 0
        if (xpTenths > 0) statAdvance("stat.agility", xpTenths / 10.0)
        completed = true
        if (lap) onLapCompleted(player, obstacle.course)
        return true
    } finally {
        resetAnim()
        player.resetPendingFaceSquare()
        if (completed) player.clearAgilityRecovery() else player.recoverAgility(collision)
    }
}

private suspend fun ProtectedAccess.approachAgilityEntry(
    entry: CoordGrid,
    collision: CollisionFlagMap,
): Boolean {
    if (coords == entry) return true
    val route = RouteFinding(collision).findRoute(
        level = entry.level, srcX = coords.x, srcZ = coords.z,
        destX = entry.x, destZ = entry.z, moveNear = false,
    )
    if (route.failed) return false
    val path = route.map { CoordGrid(it.x, it.z, entry.level) }
    var previous = coords
    val distance = path.sumOf { tile -> previous.chebyshevDistance(tile).also { previous = tile } }
    if (distance > MAX_APPROACH_TICKS) return false
    player.routeRequest = null
    player.routeDestination.clear()
    player.routeDestination.addAll(path)
    try {
        repeat(distance + 1) {
            if (coords == entry) return true
            player.moveSpeed = MoveSpeed.Walk
            delay(1)
        }
        return coords == entry
    } finally {
        player.abortRoute()
    }
}

internal fun agilityFacing(from: CoordGrid, to: CoordGrid): Int = when {
    to.x > from.x && to.z > from.z -> 1280
    to.x > from.x && to.z < from.z -> 1792
    to.x < from.x && to.z > from.z -> 768
    to.x < from.x && to.z < from.z -> 256
    to.x > from.x -> 1536
    to.x < from.x -> 512
    to.z > from.z -> 1024
    else -> 0
}

private const val MAX_APPROACH_TICKS = 4
private const val CLIENT_CYCLES_PER_TICK = 30
