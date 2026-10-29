package org.rsmod.content.areas.wilderness

import dev.openrune.ServerCacheManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.player.hook.PlayerTeleportValidator
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

/** Exercises native Tele Block timer interpretation, not map membership or spell casting. */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class WildernessTeleportBlockTest {
    private val areas = mockk<AreaChecker> {
        every { inArea("area.wilderness", any()) } returns true
        every { normalizedCoords(any()) } answers { CoordGrid(firstArg<Int>()) }
    }
    private val validator = PlayerTeleportValidator(setOf(WildernessTeleportHook()))

    @Test
    fun `remaining immunity never blocks an otherwise legal teleport`() {
        val player = Player().apply { coords = CoordGrid(3200, 3550) }
        for (timer in listOf(0, 1, 50, 99, 100)) {
            VarPlayerIntMapSetter.set(player, "varbit.teleblock_cycles", timer)
            assertNull(validator.validate(player, TeleportType.Standard, areas), "timer=$timer")
            assertEquals(timer, player.vars["varbit.teleblock_cycles"])
        }
    }

    @Test
    fun `active block includes its final tick and leaves timer consumption to the lifecycle`() {
        val player = Player().apply { coords = CoordGrid(3200, 3550) }
        for (timer in listOf(101, 102, 350, 600)) {
            VarPlayerIntMapSetter.set(player, "varbit.teleblock_cycles", timer)
            assertEquals(
                "A teleport block has been cast on you.",
                validator.validate(player, TeleportType.Standard, areas),
                "timer=$timer",
            )
            assertEquals(timer, player.vars["varbit.teleblock_cycles"])
        }
    }

    @Test
    fun `immunity does not bypass other wilderness restrictions and exempt teleports stay exempt`() {
        val player = Player().apply { coords = CoordGrid(3200, 3700) }
        VarPlayerIntMapSetter.set(player, "varbit.teleblock_cycles", 100)
        assertEquals(
            "You can't use this teleport after level 20 wilderness.",
            validator.validate(player, TeleportType.Standard, areas),
        )
        assertEquals(
            "The Chronicle doesn't work in the Wilderness.",
            validator.validate(player, TeleportType.Chronicle, areas),
        )
        assertEquals(
            "You cannot teleport to a minigame from the Wilderness.",
            validator.validate(player, TeleportType.Minigame, areas),
        )
        VarPlayerIntMapSetter.set(player, "varbit.teleblock_cycles", 600)
        assertNull(validator.validate(player, TeleportType.Exempt, areas))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
        }
    }
}
