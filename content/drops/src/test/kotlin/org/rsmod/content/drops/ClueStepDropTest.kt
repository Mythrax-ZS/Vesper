package org.rsmod.content.drops

import dev.openrune.ServerCacheManager
import dtx.core.ArgMap
import dtx.core.RollResult
import dtx.core.with
import dtx.rs.RSDropTable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.KillRollContext
import org.rsmod.content.drops.tables.monsters.aberrantSpectreDropTable
import org.rsmod.content.drops.tables.monsters.amoxliatlDropTable
import org.rsmod.content.drops.tables.monsters.basiliskDropTable
import org.rsmod.content.drops.tables.monsters.basiliskKnightDropTable
import org.rsmod.content.drops.tables.monsters.bloodveldDropTable
import org.rsmod.content.drops.tables.monsters.brutalGreenDragonDropTable
import org.rsmod.content.drops.tables.monsters.crocodileDropTable
import org.rsmod.content.drops.tables.monsters.deviantSpectreDropTable
import org.rsmod.content.drops.tables.monsters.earthenNaguaDropTable
import org.rsmod.content.drops.tables.monsters.ferociousBarbarianSpiritDropTable
import org.rsmod.content.drops.tables.monsters.frostNaguaDropTable
import org.rsmod.content.drops.tables.monsters.mutatedBloodveldDropTable
import org.rsmod.content.drops.tables.monsters.rockCrabDropTable
import org.rsmod.content.drops.tables.monsters.shellbaneGryphonDropTable
import org.rsmod.content.drops.tables.monsters.spiritualMageDropTable
import org.rsmod.content.drops.tables.monsters.waterfiendDropTable
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.Inventory
import org.rsmod.map.CoordGrid

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
@ResourceLock("kotlin.random.Random.Default")
class ClueStepDropTest {
    @Test
    fun `ordinary kills cannot produce elite or master clue completion rewards`() {
        val tables = listOf(
            aberrantSpectreDropTable, amoxliatlDropTable, basiliskDropTable,
            basiliskKnightDropTable, bloodveldDropTable, brutalGreenDragonDropTable,
            crocodileDropTable, deviantSpectreDropTable, earthenNaguaDropTable,
            ferociousBarbarianSpiritDropTable, frostNaguaDropTable, mutatedBloodveldDropTable,
            rockCrabDropTable, shellbaneGryphonDropTable, spiritualMageDropTable,
            waterfiendDropTable,
        )
        for (table in tables) {
            for (roll in listOf(0.0, 99.999)) {
                val drops = rollTertiaries(table, roll)
                assertFalse(drops.any { it.obj.startsWith("obj.trail_reward_casket_") }, table.tableIdentifier)
                assertFalse(drops.any { clueScrollTierForObj(it.obj) == ClueScrollTier.Master }, table.tableIdentifier)
                val legitimateElite = table === amoxliatlDropTable || table === shellbaneGryphonDropTable
                val expectedElite = if (legitimateElite && roll == 0.0) 1 else 0
                assertEquals(expectedElite,
                    drops.count { clueScrollTierForObj(it.obj) == ClueScrollTier.Elite },
                    table.tableIdentifier)
            }
        }
    }

    @Test
    fun `Amoxliatl and Shellbane retain their random elite roll rather than a guaranteed clue`() {
        for (table in listOf(amoxliatlDropTable, shellbaneGryphonDropTable)) {
            val successful = rollTertiaries(table, 0.0).filter {
                clueScrollTierForObj(it.obj) == ClueScrollTier.Elite
            }
            assertEquals(1, successful.size)
            assertEquals(1..1, successful.single().count)
            val failed = rollTertiaries(table, 99.999)
            assertFalse(failed.any { clueScrollTierForObj(it.obj) == ClueScrollTier.Elite })
        }
    }

    private fun rollTertiaries(table: RSDropTable<Player, DropRollItem>, roll: Double): List<DropRollItem> {
        val player = Player().apply {
            coords = CoordGrid(3200, 3200)
            inv = Inventory.create("inv.inv")
            worn = Inventory.create("inv.worn")
        }
        val areas = mockk<AreaChecker>()
        every { areas.inArea(any(), any()) } returns false
        val args = ArgMap(
            KillRollContext.npc with Npc(table.npcs.first(), player.coords),
            KillRollContext.areaChecker with areas,
            KillRollContext.dropCoords with player.coords,
        )
        mockkObject(Random.Default)
        return try {
            every { Random.Default.nextDouble(any<Double>(), any<Double>()) } returns roll
            when (val result = table.tableEntries.last().roll(player, args)) {
                is RollResult.Nothing -> emptyList()
                is RollResult.Single -> listOf(result.result)
                is RollResult.ListOf -> result.results
            }
        } finally {
            unmockkObject(Random.Default)
        }
    }

    companion object {
        @JvmStatic @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
