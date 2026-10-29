package org.rsmod.api.droptable

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.util.Wearpos
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class RingOfWealthTest {
    @Test
    fun `every normal and imbued charge state removes the rare table nothing entry`() {
        val player = player()
        val nothing = nothingDrop()
        assertTrue(nothing.condition(player))
        for (symbol in NORMAL_RINGS + IMBUED_RINGS) {
            player.worn[Wearpos.Ring.slot] = InvObj(symbol)
            assertTrue(player.wearingRingOfWealth(), symbol)
            assertFalse(nothing.condition(player), symbol)
        }
        player.worn[Wearpos.Ring.slot] = null
        assertFalse(player.wearingRingOfWealth())
        assertTrue(nothing.condition(player))
    }

    @Test
    fun `only imbued charge states qualify for the wilderness clue benefit`() {
        val player = player()
        for (symbol in NORMAL_RINGS) {
            player.worn[Wearpos.Ring.slot] = InvObj(symbol)
            assertFalse(player.wearingImbuedRingOfWealth(), symbol)
        }
        for (symbol in IMBUED_RINGS) {
            player.worn[Wearpos.Ring.slot] = InvObj(symbol)
            assertTrue(player.wearingImbuedRingOfWealth(), symbol)
        }
    }

    @Test
    fun `a carried ring or a ring in another equipment slot grants neither benefit`() {
        val player = player()
        player.inv[0] = InvObj("obj.ring_of_wealth_i5")
        player.worn[Wearpos.Front.slot] = InvObj("obj.ring_of_wealth_i")
        assertFalse(player.wearingRingOfWealth())
        assertFalse(player.wearingImbuedRingOfWealth())
        assertTrue(nothingDrop().condition(player))
    }

    @Test
    fun `unrelated rings certificates and the imbue scroll do not grant wealth effects`() {
        val player = player()
        for (symbol in listOf(
            "obj.ring_of_life", "obj.ring_of_forging", "obj.lightbearer",
            "obj.cert_ring_of_wealth", "obj.bh_imbue_ringofwealth",
        )) {
            player.worn[Wearpos.Ring.slot] = InvObj(symbol)
            assertFalse(player.wearingRingOfWealth(), symbol)
            assertFalse(player.wearingImbuedRingOfWealth(), symbol)
            assertTrue(nothingDrop().condition(player), symbol)
        }
    }

    private fun player(): Player = Player().apply {
        worn = inventory("inv.worn", 14)
        inv = inventory("inv.inv", 28)
    }

    private fun inventory(symbol: String, size: Int): Inventory = Inventory(
        checkNotNull(ServerCacheManager.getInventory(symbol.asRSCM())), arrayOfNulls(size),
    )

    companion object {
        private val NORMAL_RINGS = listOf(
            "obj.ring_of_wealth", "obj.ring_of_wealth_1", "obj.ring_of_wealth_2",
            "obj.ring_of_wealth_3", "obj.ring_of_wealth_4", "obj.ring_of_wealth_5",
        )
        private val IMBUED_RINGS = listOf(
            "obj.ring_of_wealth_i", "obj.ring_of_wealth_i1", "obj.ring_of_wealth_i2",
            "obj.ring_of_wealth_i3", "obj.ring_of_wealth_i4", "obj.ring_of_wealth_i5",
        )

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            ServerCacheManager.init(241).close()
        }
    }
}
