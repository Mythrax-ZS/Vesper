package org.rsmod.api.account.character.inv

import org.rsmod.api.account.character.CharacterDataStage

public data class CharacterInventorySaveData(val inventories: List<Inventory>) : CharacterDataStage.SaveData {
    public data class Inventory(val key: String, val capacity: Int, val objs: Map<Int, CharacterInventoryData.Obj>)
}
