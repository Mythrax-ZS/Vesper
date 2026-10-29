package org.rsmod.content.skills.agility

import dev.openrune.ServerCacheManager
import kotlin.math.abs
import org.rsmod.api.table.agility.AgilityCoursesRow
import org.rsmod.api.table.agility.AgilityObstaclesRow
import org.rsmod.map.CoordGrid

internal enum class AgilityTraversal {
    BALANCE,
    CRAWL,
    CLIMB,
    JUMP,
}

internal class AgilityObstacle(
    val data: AgilityObstaclesRow,
    val course: AgilityCoursesRow,
) {
    val kind = AgilityTraversal.valueOf(data.kind)
    val animation = checkNotNull(ServerCacheManager.getAnim(data.animation.id))
    val enterAnimation = data.enterAnimation?.let { checkNotNull(ServerCacheManager.getAnim(it.id)) }
    val leaveAnimation = data.leaveAnimation?.let { checkNotNull(ServerCacheManager.getAnim(it.id)) }

    init {
        require(data.course == course.key)
        require(course.key > 0 && course.steps > 0 && course.level in 1..99)
        require(data.step in 1..course.steps && data.xpTenths >= 0 && course.bonusTenths >= 0)
        require(data.entrySpan in 0..1 && animation.tickDuration > 0)
        require(data.locCoord.level == data.entry.level)
        require(data.entry != data.exit)
        if (kind != AgilityTraversal.CLIMB) require(data.entry.level == data.exit.level)
    }

    fun entryFrom(from: CoordGrid): CoordGrid =
        data.entry.translate((from.x - data.entry.x).coerceIn(0, data.entrySpan), 0)

    fun exitFrom(entry: CoordGrid): CoordGrid =
        data.exit.translate(entry.x - data.entry.x, 0)

    fun travelTicks(entry: CoordGrid): Int = when (kind) {
        AgilityTraversal.BALANCE, AgilityTraversal.CRAWL -> entry.chebyshevDistance(exitFrom(entry))
        AgilityTraversal.CLIMB ->
            animation.tickDuration * abs(data.exit.level - entry.level).coerceAtLeast(1)
        AgilityTraversal.JUMP -> animation.tickDuration
    }

    fun totalTicks(entry: CoordGrid): Int =
        (enterAnimation?.tickDuration ?: 0) + travelTicks(entry) +
            (leaveAnimation?.tickDuration ?: 0)
}
