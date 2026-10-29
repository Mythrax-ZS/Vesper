package org.rsmod.api.account.saver

import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.main.CharacterMainSaveData
import org.rsmod.game.world.WorldType

public data class CharacterSaveSnapshot(
    val accountId: Int,
    val characterId: Int,
    val worldType: WorldType,
    val main: CharacterMainSaveData,
    val segments: Map<String, CharacterDataStage.SaveData>,
)
