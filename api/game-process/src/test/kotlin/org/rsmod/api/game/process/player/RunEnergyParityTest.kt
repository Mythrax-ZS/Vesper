package org.rsmod.api.game.process.player

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.player.hook.PlayerRunRecoveryHook
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj

@ResourceLock("ServerCacheManager")
class RunEnergyParityTest {
    private val processor = PlayerRunUpdateProcessor(emptySet())

    @Test
    fun `running uses current agility and clamps effective weight at zero and 64kg`() {
        val cases = listOf(
            Triple(1, 0, 59), Triple(99, 0, 40), Triple(106, 0, 38),
            Triple(1, 64000, 126), Triple(99, 64000, 85), Triple(106, 64000, 82),
            Triple(99, -25000, 40), Triple(99, 100000, 85), Triple(99, 20000, 53),
        )
        for ((level, weight, loss) in cases) {
            val player = player(level).apply { runWeight = weight; pendingStepCount = 2 }
            processor.process(player)
            assertEquals(5000 - loss, player.runEnergy, "level=$level grams=$weight")
        }
    }

    @Test
    fun `stamina overrides the ring passive without stacking their reductions`() {
        for ((stamina, ring, loss) in listOf(
            Triple(false, false, 53), Triple(false, true, 45),
            Triple(true, false, 15), Triple(true, true, 15),
        )) {
            val player = player(99).apply { runWeight = 20000; pendingStepCount = 2 }
            VarPlayerIntMapSetter.set(player, "varbit.stamina_active", if (stamina) 1 else 0)
            VarPlayerIntMapSetter.set(player, "varbit.improved_stamina_passive", if (ring) 1 else 0)
            processor.process(player)
            assertEquals(5000 - loss, player.runEnergy, "stamina=$stamina ring=$ring")
        }
    }

    @Test
    fun `standing and single-tile movement restore at current agility thresholds`() {
        for ((level, gain) in listOf(0 to 15, 9 to 15, 10 to 16, 99 to 24, 106 to 25)) {
            for (steps in 0..1) {
                val player = player(level).apply { pendingStepCount = steps }
                processor.process(player)
                assertEquals(5000 + gain, player.runEnergy, "level=$level steps=$steps")
            }
        }
        val nearlyFull = player(106).apply { runEnergy = 9999 }
        processor.process(nearlyFull)
        assertEquals(10000, nearlyFull.runEnergy)
    }

    @Test
    fun `every graceful subset uses exact integer restoration and only six pieces grant full bonus`() {
        val pieces = listOf("hood" to 3, "cape" to 3, "top" to 4, "legs" to 4, "gloves" to 3, "boots" to 3)
        for (mask in 0 until 64) {
            for (level in listOf(1, 99, 106)) {
                val player = player(level)
                var percent = 0
                for ((bit, piece) in pieces.withIndex()) {
                    if (mask and (1 shl bit) == 0) continue
                    val type = checkNotNull(ServerCacheManager.getItem("obj.graceful_${piece.first}_worn".asRSCM()))
                    player.worn[type.wearpos1] = InvObj(type)
                    percent += piece.second
                }
                if (mask == 63) percent = 30
                val base = when (level) { 1 -> 15; 99 -> 24; else -> 25 }
                processor.process(player)
                assertEquals(5000 + base * (100 + percent) / 100, player.runEnergy, "mask=$mask level=$level")
            }
        }
    }

    @Test
    fun `recovery hooks pause regeneration without preventing running drain and resume immediately`() {
        var allowed = false
        val processor = PlayerRunUpdateProcessor(setOf(PlayerRunRecoveryHook { allowed }))
        val player = player(99)
        for (steps in 0..1) {
                player.pendingStepCount = steps
            processor.process(player)
            assertEquals(5000, player.runEnergy)
        }
        player.pendingStepCount = 2
        processor.process(player)
        assertEquals(4960, player.runEnergy)
        allowed = true
        player.pendingStepCount = 0
        processor.process(player)
        assertEquals(4984, player.runEnergy)
    }

    private fun player(level: Int) = Player().apply {
        inv = invMap.getOrPut("inv.inv")
        worn = invMap.getOrPut("inv.worn")
        statMap.setBaseLevel("stat.agility", 99)
        statMap.setCurrentLevel("stat.agility", level.toByte())
        runEnergy = 5000
    }

    companion object {
        @JvmStatic @BeforeAll fun cache() { ServerCacheManager.init(241).close() }
    }
}
