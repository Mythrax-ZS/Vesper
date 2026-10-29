package org.rsmod.content.areas.wilderness

import jakarta.inject.Inject
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.spells.runes.BlightedRunePackPermission
import org.rsmod.game.entity.Player

public class WildernessBlightedRunePackPermission
@Inject
constructor(private val areas: AreaChecker) : BlightedRunePackPermission {
    override fun permits(player: Player): Boolean =
        areas.inArea("area.wilderness", player.coords) ||
            areas.inArea("area.ferox_enclave", player.coords) ||
            areas.inArea("area.mage_arena_bank", player.coords)
}
