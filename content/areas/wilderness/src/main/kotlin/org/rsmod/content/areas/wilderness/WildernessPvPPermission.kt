package org.rsmod.content.areas.wilderness

import jakarta.inject.Inject
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.death.PvPContextPermission
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.hasWildernessOptOut
import org.rsmod.game.entity.Player

/** Wilderness context only; the shared authorization layer retains player validity and rule vetoes. */
public class WildernessPvPPermission
@Inject
constructor(
    private val areaChecker: AreaChecker,
    private val regions: RegionRegistry,
) : PvPContextPermission {
    override fun permits(attacker: Player, target: Player): Boolean {
        if (attacker.hasWildernessOptOut() || target.hasWildernessOptOut()) {
            return false
        }
        if (!areaChecker.inArea("area.wilderness", attacker.coords) ||
            !areaChecker.inArea("area.wilderness", target.coords)
        ) {
            return false
        }

        val copied = RegionRegistry.inWorkingArea(attacker.coords) ||
            RegionRegistry.inWorkingArea(target.coords)
        if (!copied) {
            return true
        }

        // Matching source-map coordinates do not establish membership in the same live copy.
        val attackerRegion = regions[attacker.coords] ?: return false
        return attackerRegion === regions[target.coords]
    }
}
