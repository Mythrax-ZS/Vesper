package org.rsmod.content.other.consumables.potion

import dev.openrune.ServerCacheManager
import dev.openrune.util.Wearpos
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.death.PlayerDeathDrops
import org.rsmod.api.death.PlayerDeathHandling
import org.rsmod.api.death.UntradeableHandling
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.invtx.invTransfer
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.worn.HeldEquipOp
import org.rsmod.api.player.worn.HeldEquipResult
import org.rsmod.api.player.worn.WornUnequipOp
import org.rsmod.api.player.worn.WornUnequipResult
import org.rsmod.api.server.config.ServerConfig
import org.rsmod.api.table.PotionRow
import org.rsmod.content.interfaces.bank.bankCapacity
import org.rsmod.content.interfaces.bank.scripts.BankInvScript
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.inv.InventoryChangeListener
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class RingOfEnduranceTest {
    @Test
    fun `all native stamina dose variants preserve residual doses and packed ring state`() {
        val f = Fixture()
        for (potion in f.ring.potions) {
            for ((index, type) in potion.items.withIndex()) {
                val beforeRing = InvObj(RingOfEndurance.EMPTY, vars = 1 shl 16)
                val beforePotion = InvObj(type)
                f.player.inv[0] = beforeRing
                f.player.inv[1] = beforePotion
                val added = f.ring.charge(f.player, 0, beforeRing, 1, beforePotion, 1)
                assertEquals(f.ring.chargeCost(potion), added, type.internalName)
                assertEquals(added, f.ring.charges(f.player.inv[0]))
                assertEquals(1 shl 16, f.player.inv[0]!!.vars and (1 shl 16))
                assertEquals((potion.items.getOrNull(index + 1) ?: potion.empty).id, f.player.inv[1]!!.id)
                assertEquals(2, f.player.inv.objs.count { it != null })
            }
        }
    }

    @Test
    fun `charge cap never consumes a partial extended dose or overflows on a large request`() {
        val f = Fixture()
        val ordinary = PotionRow.getRow("dbrow.stamina_potion")
        val extended = PotionRow.getRow("dbrow.extended_stamina_potion")
        f.prepareCharge(999, ordinary)
        assertEquals(1, f.charge(Int.MAX_VALUE))
        assertEquals(1_000, f.ring.charges(f.player.inv[0]))
        assertEquals(ordinary.items[1].id, f.player.inv[1]!!.id)
        val full = f.player.inv.objs.toList()
        assertEquals(0, f.charge(4))
        assertEquals(full, f.player.inv.objs.toList())
        f.prepareCharge(999, extended)
        val odd = f.player.inv.objs.toList()
        assertEquals(0, f.charge(1))
        assertEquals(odd, f.player.inv.objs.toList())
        f.prepareCharge(998, extended)
        assertEquals(2, f.charge(1))
        assertEquals(extended.items[1].id, f.player.inv[1]!!.id)
        assertEquals(1_000, f.ring.charges(f.player.inv[0]))
    }

    @Test
    fun `charging with a full inventory replaces the existing slots without losing other items`() {
        val f = Fixture()
        for (slot in f.player.inv.indices) f.player.inv[slot] = InvObj("obj.bronze_dagger")
        val potion = PotionRow.getRow("dbrow.stamina_potion")
        f.prepareCharge(0, potion)
        val unaffected = f.player.inv.objs.drop(2)
        assertEquals(4, f.charge(4))
        assertEquals(potion.empty.id, f.player.inv[1]!!.id)
        assertEquals(unaffected, f.player.inv.objs.drop(2))
        assertEquals(0, f.player.inv.freeSpace())
    }

    @Test
    fun `stale replaced or vanished charging inputs and nonpositive requests are rejected`() {
        val f = Fixture()
        val potion = PotionRow.getRow("dbrow.stamina_potion")
        f.prepareCharge(0, potion)
        val ring = f.player.inv[0]!!
        val dose = f.player.inv[1]!!
        for (request in listOf(-1, 0)) assertEquals(0, f.ring.charge(f.player, 0, ring, 1, dose, request))
        f.player.inv[1] = null
        assertEquals(0, f.ring.charge(f.player, 0, ring, 1, dose, 1))
        assertEquals(ring, f.player.inv[0])
        f.player.inv[1] = dose
        f.player.inv[0] = InvObj("obj.bronze_dagger")
        assertEquals(0, f.ring.charge(f.player, 0, ring, 1, dose, 1))
        assertEquals(dose, f.player.inv[1])
        f.player.inv[0] = ring
        assertEquals(4, f.ring.charge(f.player, 0, ring, 1, dose, 4))
        val committed = f.player.inv.objs.toList()
        assertEquals(0, f.ring.charge(f.player, 0, ring, 1, dose, 4))
        assertEquals(committed, f.player.inv.objs.toList())
    }

    @Test
    fun `manual uncharge discards all charges returns the current empty ring and cannot repeat`() {
        val f = Fixture()
        for (slot in f.player.inv.indices) f.player.inv[slot] = InvObj("obj.bronze_dagger")
        val charged = f.ring.withCharges(InvObj(RingOfEndurance.EMPTY), 1_000)
        f.player.inv[0] = charged
        val others = f.player.inv.objs.drop(1)
        assertTrue(f.ring.uncharge(f.player, 0, charged))
        assertEquals(InvObj(RingOfEndurance.EMPTY), f.player.inv[0])
        assertFalse(f.ring.uncharge(f.player, 0, charged))
        assertEquals(others, f.player.inv.objs.drop(1))
        assertEquals(0, f.player.inv.freeSpace())
    }

    @Test
    fun `each supported potion consumes its ring charge cost and doubles real energy and duration effects`() {
        for (row in listOf("dbrow.stamina_potion", "dbrow.stamina_mix", "dbrow.extended_stamina_potion")) {
            val f = Fixture()
            val potion = PotionRow.getRow(row)
            val cost = f.ring.chargeCost(potion)
            f.equip(cost)
            assertEquals(true, f.drink(potion))
            assertEquals(InvObj(RingOfEndurance.EMPTY), f.player.worn[Wearpos.Ring.slot])
            assertTrue(f.player.enduranceStaminaEnhanced)
            assertEquals(if (cost == 2) 8_000 else 4_000, f.player.runEnergy)
            assertEquals(f.clock.cycle + potion.effect.duration * 2, f.player.attr[PotionBuffState.staminaExpiresAt])
            f.hook.onInvUpdated(f.player, f.player.worn)
            assertTrue(f.player.enduranceStaminaEnhanced, "a worn empty ring is still equipped")
        }
    }

    @Test
    fun `insufficient ring charges preserve the ring and grant only the ordinary potion effect`() {
        val f = Fixture()
        val potion = PotionRow.getRow("dbrow.extended_stamina_potion")
        f.equip(1)
        val before = f.player.worn[Wearpos.Ring.slot]
        assertEquals(false, f.drink(potion))
        assertEquals(before, f.player.worn[Wearpos.Ring.slot])
        assertEquals(4_000, f.player.runEnergy)
        assertEquals(f.clock.cycle + 400, f.player.attr[PotionBuffState.staminaExpiresAt])
        assertFalse(f.player.enduranceStaminaEnhanced)
    }

    @Test
    fun `stale drinking events cannot consume a ring charge or a replacement item`() {
        val f = Fixture()
        val potion = PotionRow.getRow("dbrow.stamina_potion")
        f.equip(10)
        f.player.inv[0] = InvObj(potion.items.first())
        assertEquals(true, f.ring.consumeDose(f.player, f.player.inv, 0, potion.items[0], potion.items[1], potion))
        val inv = f.player.inv.objs.toList()
        val worn = f.player.worn.objs.toList()
        repeat(2) {
            assertNull(f.ring.consumeDose(f.player, f.player.inv, 0, potion.items[0], potion.items[1], potion))
        }
        assertEquals(inv, f.player.inv.objs.toList())
        assertEquals(worn, f.player.worn.objs.toList())
    }

    @Test
    fun `passive is derived from the worn ring and changes at exactly 500 charges`() {
        val f = Fixture()
        for (count in listOf(0, 1, 499, 500, 999, 1_000)) {
            f.equip(count)
            f.hook.onInvUpdated(f.player, f.player.worn)
            assertEquals(if (count >= 500) 1 else 0, f.player.vars["varbit.improved_stamina_passive"])
        }
        f.equip(500)
        assertEquals(true, f.drink(PotionRow.getRow("dbrow.stamina_potion")))
        f.hook.onInvUpdated(f.player, f.player.worn)
        assertEquals(499, f.ring.charges(f.player.worn[Wearpos.Ring.slot]))
        assertEquals(0, f.player.vars["varbit.improved_stamina_passive"])
        assertEquals(1, f.player.vars["varbit.stamina_active"])
        f.player.inv[5] = f.ring.withCharges(InvObj(RingOfEndurance.EMPTY), 1_000)
        f.player.worn[Wearpos.Ring.slot] = null
        f.hook.onInvUpdated(f.player, f.player.worn)
        assertEquals(0, f.player.vars["varbit.improved_stamina_passive"])
    }

    @Test
    fun `unequipping halves only the remaining enhanced duration and cannot halve it repeatedly`() {
        val f = Fixture()
        f.equip(10)
        f.drink(PotionRow.getRow("dbrow.stamina_potion"))
        f.advance(100)
        val ring = f.player.worn[Wearpos.Ring.slot]
        f.player.worn[Wearpos.Ring.slot] = null
        f.hook.onInvUpdated(f.player, f.player.worn)
        assertEquals(f.clock.cycle + 150, f.player.attr[PotionBuffState.staminaExpiresAt])
        assertFalse(f.player.enduranceStaminaEnhanced)
        repeat(2) { f.hook.onInvUpdated(f.player, f.player.worn) }
        f.player.worn[Wearpos.Ring.slot] = ring
        f.hook.onInvUpdated(f.player, f.player.worn)
        assertEquals(f.clock.cycle + 150, f.player.attr[PotionBuffState.staminaExpiresAt])
    }

    @Test
    fun `a later normal dose replaces enhancement provenance while expiration clears it`() {
        val f = Fixture()
        f.equip(1)
        val potion = PotionRow.getRow("dbrow.stamina_potion")
        assertEquals(true, f.drink(potion))
        assertEquals(false, f.drink(potion))
        assertFalse(f.player.enduranceStaminaEnhanced)
        f.player.worn[Wearpos.Ring.slot] = null
        f.hook.onInvUpdated(f.player, f.player.worn)
        assertEquals(f.clock.cycle + 200, f.player.attr[PotionBuffState.staminaExpiresAt])
        f.equip(1)
        f.drink(potion)
        f.advance(400)
        f.effects.processStamina(f.access)
        assertFalse(f.player.enduranceStaminaEnhanced)
        assertEquals(0, f.player.vars["varbit.stamina_active"])
        assertNull(f.player.attr[PotionBuffState.staminaExpiresAt])
    }

    @Test
    fun `saved enhanced stamina resumes without offline decay and halves correctly after login`() {
        val f = Fixture()
        f.equip(10)
        f.drink(PotionRow.getRow("dbrow.stamina_potion"))
        f.advance(50)
        f.effects.onLogout(f.player)
        assertEquals(350, f.player.attr[PotionBuffState.savedStaminaTicks])
        f.player.attr.remove(PotionBuffState.staminaExpiresAt)
        f.advance(1_000)
        f.effects.onLogin(f.player)
        assertTrue(f.player.enduranceStaminaEnhanced)
        assertEquals(f.clock.cycle + 350, f.player.attr[PotionBuffState.staminaExpiresAt])
        f.player.worn[Wearpos.Ring.slot] = null
        f.hook.onInvUpdated(f.player, f.player.worn)
        assertEquals(f.clock.cycle + 175, f.player.attr[PotionBuffState.staminaExpiresAt])
        f.player.enduranceStaminaEnhanced = true
        f.player.attr[PotionBuffState.savedStaminaTicks] = 0
        f.effects.onLogin(f.player)
        assertFalse(f.player.enduranceStaminaEnhanced)
        assertNull(f.player.attr[PotionBuffState.staminaExpiresAt])
    }

    @Test
    fun `actual unequip and reequip within one tick halve remaining duration immediately once`() {
        val f = Fixture()
        f.equip(10)
        f.drink(PotionRow.getRow("dbrow.stamina_potion"))
        f.advance(100)
        assertEquals(WornUnequipResult.Success, WornUnequipOp(f.events).unequip(
            f.player, Wearpos.Ring.slot, f.player.worn, f.player.inv,
        ))
        assertEquals(f.clock.cycle + 150, f.player.attr[PotionBuffState.staminaExpiresAt])
        val slot = f.player.inv.objs.indexOfFirst { it?.id == InvObj(RingOfEndurance.CHARGED).id }
        assertTrue(HeldEquipOp(f.events).equip(f.player, slot, f.player.inv) is HeldEquipResult.Success)
        assertTrue(f.ring.isWorn(f.player))
        assertFalse(f.player.enduranceStaminaEnhanced)
        assertEquals(f.clock.cycle + 150, f.player.attr[PotionBuffState.staminaExpiresAt])
    }

    @Test
    fun `physical endurance ring swap halves duration while last charge transformation preserves it`() {
        for (remaining in listOf(1, 10)) {
            val f = Fixture()
            f.equip(remaining)
            f.drink(PotionRow.getRow("dbrow.stamina_potion"))
            assertTrue(f.player.enduranceStaminaEnhanced)
            assertEquals(f.clock.cycle + 400, f.player.attr[PotionBuffState.staminaExpiresAt])
            f.player.inv[1] = f.ring.withCharges(InvObj(RingOfEndurance.EMPTY), 600)
            assertTrue(HeldEquipOp(f.events).equip(f.player, 1, f.player.inv) is HeldEquipResult.Success)
            assertEquals(600, f.ring.charges(f.player.worn[Wearpos.Ring.slot]))
            assertFalse(f.player.enduranceStaminaEnhanced)
            assertEquals(f.clock.cycle + 200, f.player.attr[PotionBuffState.staminaExpiresAt])
        }
    }

    @Test
    fun `full inventory rollback and deferred transfer do not reduce effect before commit`() {
        val f = Fixture()
        f.equip(600)
        f.drink(PotionRow.getRow("dbrow.stamina_potion"))
        for (slot in f.player.inv.indices) f.player.inv[slot] = InvObj("obj.bronze_dagger")
        assertTrue(WornUnequipOp(f.events).unequip(
            f.player, Wearpos.Ring.slot, f.player.worn, f.player.inv,
        ) is WornUnequipResult.Fail.NotEnoughInvSpace)
        assertTrue(f.player.enduranceStaminaEnhanced)
        assertEquals(f.clock.cycle + 400, f.player.attr[PotionBuffState.staminaExpiresAt])
        assertEquals(599, f.ring.charges(f.player.worn[Wearpos.Ring.slot]))
        f.player.inv[0] = null
        val deferred = f.player.invTransfer(
            f.player.worn, Wearpos.Ring.slot, 1, f.player.inv, autoCommit = false,
        )
        assertTrue(deferred.success)
        assertTrue(f.player.enduranceStaminaEnhanced)
        assertNull(f.player.inv[0])
        deferred.commitAll()
        assertFalse(f.player.enduranceStaminaEnhanced)
        assertEquals(f.clock.cycle + 200, f.player.attr[PotionBuffState.staminaExpiresAt])
        assertEquals(599, f.ring.charges(f.player.inv[0]))
    }

    @Test
    fun `bank deposit equipment uses the same immediate removal lifecycle`() {
        val f = Fixture()
        f.equip(600)
        f.drink(PotionRow.getRow("dbrow.stamina_potion"))
        f.player.invMap["inv.bank"] = Inventory.create("inv.bank").apply { owner = f.player }
        f.player.bankCapacity = 800
        with(BankInvScript(f.events, mockk(), mockk())) { f.access.depositWorn() }
        assertNull(f.player.worn[Wearpos.Ring.slot])
        assertEquals(599, f.ring.charges(f.player.invMap.getValue("inv.bank")[0]))
        assertEquals(f.clock.cycle + 200, f.player.attr[PotionBuffState.staminaExpiresAt])
        assertFalse(f.player.enduranceStaminaEnhanced)
    }

    @Test
    fun `death inventory clear removes enhancement immediately without losing retained ring charges`() {
        val f = Fixture()
        f.equip(600)
        f.drink(PotionRow.getRow("dbrow.stamina_potion"))
        val kept = f.player.worn[Wearpos.Ring.slot]!!
        val drops = PlayerDeathDrops(
            f.clock, mockk(), mockk(), mockk(), emptySet(), ServerConfig("Vesper", 43594, 241, "TEST", 1),
        )
        drops.applyDrops(
            f.player,
            PlayerDeathDrops.DeathDropResult(listOf(kept), emptyList(), emptyList(), emptyList(), 0),
            PlayerDeathHandling(3, null, 6000, 100, false, UntradeableHandling.KEEP),
            f.player.coords,
        )
        assertNull(f.player.worn[Wearpos.Ring.slot])
        assertEquals(kept, f.player.inv[0])
        assertFalse(f.player.enduranceStaminaEnhanced)
        assertEquals(f.clock.cycle + 200, f.player.attr[PotionBuffState.staminaExpiresAt])
    }

    @Test
    fun `direct clear and same tick restoration cannot preserve enhancement or duplicate notifications`() {
        val f = Fixture()
        f.equip(600)
        f.drink(PotionRow.getRow("dbrow.stamina_potion"))
        val before = f.player.worn[Wearpos.Ring.slot]
        var notifications = 0
        val listener = InventoryChangeListener {
            notifications++
            it.notifyChanged()
        }
        f.player.worn.addChangeListener(listener)
        f.player.worn.addChangeListener(listener)
        f.hook.attach(f.player)
        assertTrue(f.player.enduranceStaminaEnhanced)
        f.player.worn.fillNulls()
        assertEquals(1, notifications)
        f.player.worn.fillNulls()
        assertEquals(1, notifications)
        f.player.worn[Wearpos.Ring.slot] = before
        assertEquals(2, notifications)
        assertFalse(f.player.enduranceStaminaEnhanced)
        assertEquals(f.clock.cycle + 200, f.player.attr[PotionBuffState.staminaExpiresAt])
        f.player.worn.removeChangeListener(listener)
        f.player.worn[Wearpos.Ring.slot] = null
        assertEquals(2, notifications)
    }

    private class Fixture {
        val clock = MapClock(100)
        val ring = RingOfEndurance(ObjChargeManager())
        val effects = PotionEffectService(mockk(), clock)
        val hook = RingOfEnduranceStateHook(ring, effects)
        val events = EventBus()
        val player = Player().apply {
            client = SilentClient()
            currentMapClock = clock.cycle
            processedMapClock = clock.cycle
            inv = Inventory.create("inv.inv")
            worn = Inventory.create("inv.worn")
            inv.owner = this
            worn.owner = this
            statMap.setBaseLevel("stat.agility", 99)
            runEnergy = 0
        }
        val access = ProtectedAccess(player, GameCoroutine(), ProtectedAccessContextFactory.empty())

        init {
            hook.attach(player)
            with(RingOfEnduranceScript(ring, hook)) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        fun equip(count: Int) { player.worn[Wearpos.Ring.slot] = ring.withCharges(InvObj(RingOfEndurance.EMPTY), count) }
        fun prepareCharge(count: Int, potion: PotionRow) {
            player.inv[0] = ring.withCharges(InvObj(RingOfEndurance.EMPTY), count)
            player.inv[1] = InvObj(potion.items.first())
        }
        fun charge(doses: Int): Int = ring.charge(player, 0, player.inv[0]!!, 1, player.inv[1]!!, doses)
        fun advance(ticks: Int) {
            clock.cycle += ticks
            player.currentMapClock = clock.cycle
            player.processedMapClock = clock.cycle
        }
        fun drink(potion: PotionRow): Boolean? {
            player.inv[0] = InvObj(potion.items.first())
            val enhanced = ring.consumeDose(player, player.inv, 0, potion.items[0], potion.items[1], potion)
            if (enhanced != null) effects.apply(access, potion.effect, enhanced)
            return enhanced
        }
    }

    private class SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private val restorations = mutableListOf<() -> Unit>()

        @JvmStatic @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val previous = field.get(null)
                restorations += { field.set(null, previous) }
            }
            val storage = InvVirtualStorageHolder.instance
            restorations += { InvVirtualStorageHolder.instance = storage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic @AfterAll
        fun restore() { restorations.asReversed().forEach { it() }; restorations.clear() }
    }
}
