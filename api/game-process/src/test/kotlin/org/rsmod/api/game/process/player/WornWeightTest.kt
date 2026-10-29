package org.rsmod.api.game.process.player

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.inv.weight.InvWeight
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.player.worn.HeldEquipOp
import org.rsmod.api.player.worn.HeldEquipResult
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.plugin.scripts.ScriptContext

@ResourceLock("ServerCacheManager")
class WornWeightTest {
    @Test
    fun `equipping graceful changes carried weight to its negative worn weight`() {
        val player = player()
        val parts = listOf("hood", "cape", "top", "legs", "gloves", "boots")
        for ((slot, part) in parts.withIndex()) player.inv[slot] = InvObj("obj.graceful_$part")
        assertEquals(3700, InvWeight.calculateWeightInGrams(player))
        val equipment = HeldEquipOp(EventBus())
        for ((slot, part) in parts.withIndex()) {
            val result = assertInstanceOf(HeldEquipResult.Success::class.java, equipment.equip(player, slot, player.inv))
            assertNull(player.inv[slot])
            assertEquals(InvObj("obj.graceful_${part}_worn"), player.worn[result.equipWearpos.slot])
        }
        assertEquals(-25000, InvWeight.calculateWeightInGrams(player))
        assertEquals(6, player.worn.count { it != null })
    }

    @Test
    fun `bank contents do not add run weight and stack counts cannot overflow it`() {
        val player = player()
        player.inv[0] = InvObj("obj.coins", Int.MAX_VALUE)
        val bank = player.invMap.getOrPut("inv.bank")
        player.transmittedInvs.add(bank.type.id)
        bank[0] = InvObj("obj.rune_platebody", Int.MAX_VALUE)
        assertEquals(0, InvWeight.calculateWeightInGrams(player))
        player.inv[1] = InvObj("obj.rune_platebody")
        assertEquals(9979, InvWeight.calculateWeightInGrams(player))
    }

    private fun player(): Player = Player().apply {
        inv = invMap.getOrPut("inv.inv")
        worn = invMap.getOrPut("inv.worn")
        transmittedInvs.add("inv.inv".asRSCM())
        transmittedInvs.add("inv.worn".asRSCM())
    }

    companion object {
        private val RESTORATIONS = mutableListOf<() -> Unit>()

        @JvmStatic @BeforeAll fun cache() {
            ServerCacheManager.init(241).close()
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val previous = field.get(null)
                RESTORATIONS += { field.set(null, previous) }
            }
            val previousStorage = InvVirtualStorageHolder.instance
            RESTORATIONS += { InvVirtualStorageHolder.instance = previousStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic @AfterAll fun restore() {
            RESTORATIONS.asReversed().forEach { it() }
            RESTORATIONS.clear()
        }
    }
}
