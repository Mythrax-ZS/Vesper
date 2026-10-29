package org.rsmod.content.other.consumables.potion

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.util.Wearpos
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.invtx.add
import org.rsmod.api.invtx.delete
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.api.player.vars.boolVarp
import org.rsmod.api.table.PotionRow
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.inv.isAnyType
import org.rsmod.game.inv.isType
import org.rsmod.utils.bits.withBits

internal var Player.enduranceStaminaEnhanced by boolVarp("varp.endurance_stamina_enhanced")

/** Inventory operations keep potion doses and the ring's packed charges in one transaction. */
@Singleton
class RingOfEndurance @Inject constructor(private val charges: ObjChargeManager) {
    internal val potions: List<PotionRow> by lazy {
        listOf("dbrow.stamina_potion", "dbrow.stamina_mix", "dbrow.extended_stamina_potion")
            .map(PotionRow::getRow)
    }

    private val chargeBits by lazy {
        checkNotNull(ServerCacheManager.getVarObj(CHARGES.asRSCM(RSCMType.VAROBJ))).bits
    }

    fun charges(obj: InvObj?): Int =
        if (obj.isType(CHARGED)) charges.getCharges(obj, CHARGES) else 0

    fun isWorn(player: Player): Boolean = player.worn[Wearpos.Ring.slot].isAnyType(CHARGED, EMPTY)

    fun hasPassive(player: Player): Boolean = charges(player.worn[Wearpos.Ring.slot]) >= PASSIVE_CHARGES

    /** Returns null if neither the potion nor ring charge could be consumed. */
    internal fun consumeDose(
        player: Player,
        inventory: Inventory,
        slot: Int,
        type: ItemServerType,
        replacement: ItemServerType,
        potion: PotionRow,
    ): Boolean? {
        val dose = inventory[slot] ?: return null
        if (dose.id != type.id || dose.count != 1) return null
        val ring = player.worn[Wearpos.Ring.slot]
        val cost = chargeCost(potion)
        val current = charges(ring)
        val enhance = cost > 0 && current >= cost
        val result = player.invTransaction(inventory, player.worn) {
            val from = select(inventory)
            delete(from, dose.id, 1, slot = slot)
            add(from, replacement.id, 1, slot = slot)
            if (enhance) {
                val worn = select(player.worn)
                val before = checkNotNull(ring)
                val after = withCharges(before, current - cost)
                delete(worn, before.id, 1, slot = Wearpos.Ring.slot)
                add(worn, after.id, 1, vars = after.vars, slot = Wearpos.Ring.slot)
            }
        }
        return if (result.success) enhance else null
    }

    internal fun charge(
        player: Player,
        ringSlot: Int,
        expectedRing: InvObj,
        potionSlot: Int,
        expectedPotion: InvObj,
        requestedDoses: Int,
    ): Int {
        if (ringSlot == potionSlot || requestedDoses <= 0 ||
            player.inv[ringSlot] != expectedRing || player.inv[potionSlot] != expectedPotion ||
            !expectedRing.isAnyType(CHARGED, EMPTY) || expectedRing.count != 1 || expectedPotion.count != 1
        ) return 0
        val potion = potions.singleOrNull { it.items.any { item -> item.id == expectedPotion.id } } ?: return 0
        val index = potion.items.indexOfFirst { it.id == expectedPotion.id }
        val cost = chargeCost(potion)
        val current = charges(expectedRing)
        val doses = minOf(requestedDoses, potion.items.size - index, (MAX_CHARGES - current) / cost)
        if (doses <= 0) return 0
        val replacement = potion.items.getOrNull(index + doses) ?: potion.empty
        val after = withCharges(expectedRing, current + doses * cost)
        val result = player.invTransaction(player.inv) {
            val inv = select(player.inv)
            delete(inv, expectedRing.id, 1, slot = ringSlot)
            delete(inv, expectedPotion.id, 1, slot = potionSlot)
            add(inv, after.id, 1, vars = after.vars, slot = ringSlot)
            add(inv, replacement.id, 1, slot = potionSlot)
        }
        return if (result.success) doses * cost else 0
    }

    /** Manual uncharging destroys the stored doses; it never refunds stamina potions. */
    internal fun uncharge(player: Player, slot: Int, expected: InvObj): Boolean {
        if (player.inv[slot] != expected || !expected.isType(CHARGED) || expected.count != 1) return false
        val after = withCharges(expected, 0)
        return player.invTransaction(player.inv) {
            val inv = select(player.inv)
            delete(inv, expected.id, 1, slot = slot)
            add(inv, after.id, 1, vars = after.vars, slot = slot)
        }.success
    }

    internal fun withCharges(ring: InvObj, count: Int): InvObj {
        require(count in 0..MAX_CHARGES)
        return InvObj(if (count == 0) EMPTY else CHARGED, vars = ring.vars.withBits(chargeBits, count))
    }

    internal fun chargeCost(potion: PotionRow): Int = when (potion.key) {
        "stamina_potion", "stamina_mix" -> 1
        "extended_stamina_potion" -> 2
        else -> 0
    }

    companion object {
        const val CHARGED = "obj.ring_of_endurance"
        const val EMPTY = "obj.ring_of_endurance_nocharges"
        const val CHARGES = "varobj.stamina_charges"
        const val MAX_CHARGES = 1_000
        const val PASSIVE_CHARGES = 500
    }
}
