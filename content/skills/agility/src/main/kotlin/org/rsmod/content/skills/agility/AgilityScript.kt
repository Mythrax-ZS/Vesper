package org.rsmod.content.skills.agility

import jakarta.inject.Inject
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.table.agility.AgilityCoursesRow
import org.rsmod.api.table.agility.AgilityObstaclesRow
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

class AgilityScript @Inject constructor(
    private val collision: CollisionFlagMap,
    private val marks: AgilityMarks,
) : PluginScript() {
    override fun ScriptContext.startup() {
        val courses = AgilityCoursesRow.all().associateBy { it.key }
        val obstacles = AgilityObstaclesRow.all().map {
            AgilityObstacle(it, checkNotNull(courses[it.course]))
        }
        for ((loc, placements) in obstacles.groupBy { it.data.loc.internalName }) {
            onOpLoc1(loc) { event ->
                val obstacle = placements.singleOrNull { it.data.locCoord == event.loc.coords }
                if (obstacle == null) {
                    mes("You can't negotiate this obstacle from here.")
                } else {
                    crossAgilityObstacle(obstacle, collision) { player, course ->
                        marks.completeLap(player, course)
                    }
                }
            }
        }
        onEvent<SessionStateEvent.PrepareLogin> { player.recoverAgility(collision) }
        onPlayerLogout { player.recoverAgility(collision) }
        onPlayerLogin { marks.login(player) }
        onPlayerLogout { marks.logout(player) }
        onPlayerSoftTimer(AgilityMarks.TIMER) { marks.updateLifetimes(player) }
    }
}
