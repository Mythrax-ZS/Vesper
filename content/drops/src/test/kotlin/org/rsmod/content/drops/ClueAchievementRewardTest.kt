package org.rsmod.content.drops

import dev.openrune.ServerCacheManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.game.entity.Player

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class ClueAchievementRewardTest {
    @Test
    fun `only claimed tier state improves corresponding clue denominator`() {
        val player = Player()
        for (status in 0..3) {
            for (tier in listOf("easy", "medium", "hard", "elite", "master")) {
                VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_$tier", status)
            }
            val expected = if (status == 2) 95 else 100
            assertEquals(expected, player.easyClueDropDenominator(100))
            assertEquals(expected, player.mediumClueDropDenominator(100))
            assertEquals(expected, player.hardClueDropDenominator(100))
            assertEquals(expected, player.eliteClueDropDenominator(100))
            assertEquals(expected, player.masterClueDropDenominator(100))
        }
    }

    @Test
    fun `claiming easy tier cannot unlock unrelated higher tier clue rewards`() {
        val player = Player()
        VarPlayerIntMapSetter.set(player, "varbit.ca_tier_status_easy", 2)
        assertEquals(61, player.easyClueDropDenominator(65))
        assertEquals(65, player.mediumClueDropDenominator(65))
        assertEquals(65, player.hardClueDropDenominator(65))
        assertEquals(65, player.eliteClueDropDenominator(65))
        assertEquals(65, player.masterClueDropDenominator(65))
    }

    companion object {
        @JvmStatic @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
