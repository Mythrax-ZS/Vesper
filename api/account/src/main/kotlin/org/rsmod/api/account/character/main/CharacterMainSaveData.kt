package org.rsmod.api.account.character.main

import java.time.LocalDateTime
import org.rsmod.map.CoordGrid

public data class CharacterMainSaveData(
    val displayName: String?,
    val members: Boolean,
    val coords: CoordGrid,
    val lastLogin: LocalDateTime,
    val runEnergy: Int,
    val xpRatePercent: Int,
    val clearPresence: Boolean,
    val varps: Map<Int, Int>,
    val attrsJson: Map<String, String>,
)
