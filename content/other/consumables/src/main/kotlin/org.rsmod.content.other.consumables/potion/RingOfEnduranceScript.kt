package org.rsmod.content.other.consumables.potion

import dev.openrune.util.Wearpos
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.player.events.interact.HeldEquipEvents
import org.rsmod.api.player.hook.PlayerInvUpdateHook
import org.rsmod.api.player.hook.PlayerPostTickHook
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpWorn2
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.inv.InventoryChangeListener
import org.rsmod.plugin.module.PluginModule
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class RingOfEnduranceModule : PluginModule() {
    override fun bind() {
        addSetBinding<PlayerInvUpdateHook>(RingOfEnduranceStateHook::class.java)
        addSetBinding<PlayerPostTickHook>(RingOfEnduranceStateHook::class.java)
    }
}

@Singleton
class RingOfEnduranceStateHook @Inject constructor(
    private val ring: RingOfEndurance,
    private val effects: PotionEffectService,
) : PlayerInvUpdateHook, PlayerPostTickHook, InventoryChangeListener {
    fun attach(player: Player) {
        player.worn.addChangeListener(this)
        updatePassive(player)
    }

    override fun onInventoryChanged(inventory: Inventory) {
        val player = inventory.owner ?: return
        onInvUpdated(player, inventory)
    }

    fun onEquipmentChange(player: Player, wearpos: Wearpos) {
        if (wearpos != Wearpos.Ring) return
        // Equipment swaps remove the old physical ring even if another endurance ring replaces
        // it. Charge updates and the final-charge transformation emit no equipment event.
        effects.removeEnduranceEnhancement(player)
        updatePassive(player)
    }

    override fun onInvUpdated(player: Player, inv: Inventory) {
        if (inv !== player.worn) return
        updatePassive(player)
        if (!ring.isWorn(player)) effects.removeEnduranceEnhancement(player)
    }

    override fun onPostTick(player: Player) {
        if (player.vars["varbit.stamina_active"] == 0) player.enduranceStaminaEnhanced = false
    }

    fun updatePassive(player: Player) {
        VarPlayerIntMapSetter.set(player, "varbit.improved_stamina_passive", if (ring.hasPassive(player)) 1 else 0)
    }
}

class RingOfEnduranceScript @Inject constructor(
    private val ring: RingOfEndurance,
    private val state: RingOfEnduranceStateHook,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpHeld4(RingOfEndurance.CHARGED) { checkCharges(it.inventory[it.slot]) }
        onOpWorn2(RingOfEndurance.CHARGED) { checkCharges(player.worn[Wearpos.Ring.slot]) }
        onOpHeld5(RingOfEndurance.CHARGED) { uncharge(it.slot) }
        for (potion in ring.potions) {
            for (item in potion.items) {
                for (ringType in listOf(RingOfEndurance.CHARGED, RingOfEndurance.EMPTY)) {
                    onOpHeldU(ringType, item.internalName) { charge(it.firstSlot, it.secondSlot) }
                }
            }
        }
        onEvent<HeldEquipEvents.WearposChange> { state.onEquipmentChange(player, wearpos) }
        onPlayerLogin { state.attach(player) }
    }

    private fun ProtectedAccess.checkCharges(obj: InvObj?) {
        mes("Your Ring of endurance is charged with ${ring.charges(obj)} stamina doses.")
    }

    private suspend fun ProtectedAccess.charge(ringSlot: Int, potionSlot: Int) {
        val beforeRing = inv[ringSlot] ?: return
        val beforePotion = inv[potionSlot] ?: return
        if (ring.charges(beforeRing) >= RingOfEndurance.MAX_CHARGES) {
            mes("Your Ring of endurance is already fully charged.")
            return
        }
        val requested = countDialog("How many doses would you like to add?")
        val added = ring.charge(player, ringSlot, beforeRing, potionSlot, beforePotion, requested)
        if (added > 0) {
            mes("You load your Ring of endurance with $added charges. It now has ${ring.charges(inv[ringSlot])} charges.")
        }
    }

    private suspend fun ProtectedAccess.uncharge(slot: Int) {
        val before = inv[slot] ?: return
        if (!choice2("Uncharge.", true, "Cancel.", false,
                title = "All of the stamina charges will be lost.")) return
        if (ring.uncharge(player, slot, before)) mes("You uncharge your Ring of endurance.")
    }
}
