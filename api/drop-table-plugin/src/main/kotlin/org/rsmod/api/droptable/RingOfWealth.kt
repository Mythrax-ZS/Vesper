package org.rsmod.api.droptable

import dev.openrune.util.Wearpos
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isAnyType

public fun Player.wearingRingOfWealth(): Boolean =
    wearingImbuedRingOfWealth() || worn[Wearpos.Ring.slot].isAnyType(
        "obj.ring_of_wealth", "obj.ring_of_wealth_1", "obj.ring_of_wealth_2",
        "obj.ring_of_wealth_3", "obj.ring_of_wealth_4", "obj.ring_of_wealth_5",
    )

public fun Player.wearingImbuedRingOfWealth(): Boolean =
    worn[Wearpos.Ring.slot].isAnyType(
        "obj.ring_of_wealth_i", "obj.ring_of_wealth_i1", "obj.ring_of_wealth_i2",
        "obj.ring_of_wealth_i3", "obj.ring_of_wealth_i4", "obj.ring_of_wealth_i5",
    )
