package org.rsmod.api.npc.hit

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.enums.NamedEnums
import org.rsmod.game.entity.Npc

/** An explicit override, including zero, takes precedence over the current visible type's baseline. */
public var Npc.flatArmour: Int
    get() {
        val overrideType = checkNotNull(ServerCacheManager.getVarn("varn.flat_armour".asRSCM(RSCMType.VARN)))
        return if (overrideType in vars) vars[overrideType] else NamedEnums.npc_flat_armour[visType] ?: 0
    }
    set(value) {
        vars["varn.flat_armour"] = value
    }

public fun Npc.clearFlatArmourOverride() {
    vars.remove("varn.flat_armour")
}
