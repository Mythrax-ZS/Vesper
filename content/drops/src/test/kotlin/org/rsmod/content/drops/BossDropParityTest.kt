package org.rsmod.content.drops

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.util.Wearpos
import dtx.core.ArgMap
import dtx.core.RollResult
import dtx.core.Single
import dtx.core.with
import dtx.impl.chance.ChanceRollable
import dtx.impl.chance.RateBoosts
import dtx.rs.RSDropTable
import dtx.rs.RSPreRollTable
import dtx.rs.RSWeightedTable
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.random.Random
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.death.NpcDeathDropHook
import org.rsmod.api.death.NpcDeathKillContext
import org.rsmod.api.death.NpcDropReceiveHook
import org.rsmod.api.droptable.DropRateBoosts
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.DropTableRegistry
import org.rsmod.api.droptable.KillRollContext
import org.rsmod.api.droptable.rollCount
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.table.QuestRow
import org.rsmod.content.drops.tables.monsters.cerberusDropTable
import org.rsmod.content.drops.tables.monsters.vorkathDropTable
import org.rsmod.content.drops.tables.shared.SharedDropTables
import org.rsmod.content.quest.manager.QuestRequirementMode
import org.rsmod.content.quest.manager.QuestRequirementPolicy
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.map.CoordGrid

/** Exercises the actual DSL graph and boundary conditions without statistical assertions. */
@ResourceLock("ServerCacheManager")
@ResourceLock("kotlin.random.Random.Default")
@ResourceLock("QuestRequirements")
@ResourceLock("DropRateBoosts")
@ResourceLock("RateBoosts")
class BossDropParityTest {
    private var previousQuestPolicy = QuestRequirements.activePolicy()
    private var previousClueBoost = DropRateBoosts.clueScrollBoostPercent
    private var previousWorldBoost = RateBoosts.multiplier

    @BeforeEach
    fun respectQuestProgress() {
        previousQuestPolicy = QuestRequirements.activePolicy()
        previousClueBoost = DropRateBoosts.clueScrollBoostPercent
        previousWorldBoost = RateBoosts.multiplier
        QuestRequirements.install(QuestRequirementPolicy(QuestRequirementMode.RespectProgress))
        DropRateBoosts.clueScrollBoostPercent = { _, _ -> 0 }
        RateBoosts.multiplier = { _, _ -> 1.0 }
    }

    @AfterEach
    fun restoreQuestPolicy() {
        QuestRequirements.install(previousQuestPolicy)
        DropRateBoosts.clueScrollBoostPercent = previousClueBoost
        RateBoosts.multiplier = previousWorldBoost
    }

    @Test
    fun `Cerberus unique roll replaces common loot and each unique has probability one in 520`() {
        val table = cerberusDropTable.main()
        val probabilities = probabilities(table)
        assertEquals(1.0, probabilities.values.sum(), 1e-12)
        for (obj in listOf("primordial_crystal", "pegasian_crystal", "eternal_crystal", "smouldering_stone")) {
            assertEquals(1.0 / 520, probabilities["obj.$obj"]!!, 1e-12)
        }
        assertTrue(table.inlineSeparateRolls.isEmpty())
        val uniqueSlot = table.selectEntries(equippedPlayer(), ArgMap.Empty).single {
            val nested = it.rollable as? RSWeightedTable<Player, DropRollItem>
            nested != null && nested !== SharedDropTables.rareDrop
        }.rangeStart
        withRolls(intArrayOf(uniqueSlot, 0)) {
            val drops = cerberusDropTable.roll(equippedPlayer()).items()
            assertEquals(listOf("obj.primordial_crystal"), drops.map { it.obj })
        }
    }

    @Test
    fun `ordinary Cerberus kills do not grant clue step rewards`() {
        val player = equippedPlayer()
        val tertiaries = cerberusDropTable.tertiaries()
        val rewards = tertiaries.tableEntries.flatMap { it.rollable.roll(player).items() }
        assertFalse(rewards.any { it.obj == "obj.trail_reward_casket_elite" })
        assertEquals(1, rewards.count { it.obj == "obj.trail_elite_emote_exp1" })
        assertEquals(1.0, tertiaries.entry("obj.trail_elite_emote_exp1", player).chanceFor(player), 1e-12)
        withRolls(intArrayOf(cerberusDropTable.slot("obj.rune_platebody"))) {
            assertEquals(listOf("obj.rune_platebody"), cerberusDropTable.roll(player).items().map { it.obj })
        }
    }

    @Test
    fun `Vorkath main branch probabilities conserve mass including seed and bolt subrolls`() {
        val table = vorkathDropTable.main()
        val probabilities = probabilities(table)
        assertEquals(1.0, probabilities.values.sum(), 1e-12)
        assertEquals(5.0 / 150 * 25 / 91, probabilities["obj.xbows_bolt_tips_diamond"]!!, 1e-12)
        assertEquals(5.0 / 150 + 5.0 / 150 * 14 / 91, probabilities["obj.xbows_bolt_tips_dragonstone"]!!, 1e-12)
        assertEquals(1.0 / 150 + 3.0 / 150 * 28 / 250, probabilities["obj.snapdragon_seed"]!!, 1e-12)
        assertEquals(1.0 / 150 + 3.0 / 150 * 22 / 250, probabilities["obj.torstol_seed"]!!, 1e-12)
        assertEquals(4.0 / 150 * 3 / 8, probabilities["obj.cert_raw_shark"]!!, 1e-12)
        assertEquals(4.0 / 150 * 3 / 8, probabilities["obj.shark_lure"]!!, 1e-12)
        assertEquals(4.0 / 150 * 2 / 8, probabilities["obj.cert_mantaray"]!!, 1e-12)
        assertTrue(table.inlineSeparateRolls.isEmpty())
    }

    @Test
    fun `Vorkath rolls main table twice but guaranteed loot only once`() {
        withRolls(intArrayOf(vorkathDropTable.slot("obj.rune_longsword"), vorkathDropTable.slot("obj.rune_kiteshield"))) {
            val drops = vorkathDropTable.roll(playerWithKills(49)).items()
            assertEquals(listOf("obj.dragonhide_blue", "obj.dragon_bones_superior", "obj.rune_longsword", "obj.rune_kiteshield"), drops.map { it.obj })
            assertEquals(2..2, drops.single { it.obj == "obj.dragonhide_blue" }.count)
            assertEquals(1..1, drops.single { it.obj == "obj.dragon_bones_superior" }.count)
        }
    }

    @Test
    fun `Vorkath extra bone passes through existing remains hooks without spawning a duplicate`() {
        val player = playerWithKills(49).apply { lootDropDuration = 100 }
        val npc = Npc("npc.vorkath", CoordGrid(3200, 3200))
        assertEquals("obj.dragon_bones_superior".asRSCM(), checkNotNull(npc.type.paramsRaw)["param.dropped_remains".asRSCM()])
        val registry = mockk<DropTableRegistry>()
        val areas = mockk<AreaChecker>()
        val objects = mockk<ObjRepository>()
        val random = mockk<GameRandom>()
        every { registry.forNpc(npc, areas) } returns vorkathDropTable
        every { random.of(any<Int>(), any<Int>()) } answers { firstArg() }
        val consumed = mutableListOf<String>()
        val remainsHook = NpcDeathDropHook { context ->
            consumed += context.dropType.internalName
            true
        }
        // Receive the ordinary items elsewhere so this test observes only the remains hook.
        val receiver = NpcDropReceiveHook { _, obj, _ -> obj != "obj.dragon_bones_superior" }
        val hook = NpcDropTableKillHook(registry, areas, objects, random, setOf(remainsHook), setOf(receiver))
        withRolls(intArrayOf(vorkathDropTable.slot("obj.rune_longsword"), vorkathDropTable.slot("obj.rune_longsword"))) {
            hook.onKill(NpcDeathKillContext(player, npc, lootTrackerEventId = 1))
        }
        assertEquals(listOf("obj.dragon_bones_superior"), consumed)
        verify { objects wasNot Called }
    }

    @Test
    fun `Vorkath head is guaranteed only at kill 50 and cannot also pass the random roll`() {
        val guaranteed = vorkathDropTable.tableEntries.first()
        for (kills in listOf(0, 1, 49, 50, 51, 100, Int.MAX_VALUE)) {
            val player = playerWithKills(kills)
            val headCount = guaranteed.roll(player).items().count { it.obj == "obj.vorkath_head" }
            val randomHead = vorkathDropTable.tertiaries().entry("obj.vorkath_head", player)
            assertEquals(if (kills == 50) 1 else 0, headCount, "kill count $kills")
            assertEquals(kills != 50, randomHead.includeInRoll(player, ArgMap.Empty), "kill count $kills")
            assertEquals(2.0, randomHead.chanceFor(player), 1e-12)
        }
        // Force every eligible tertiary to succeed: the milestone still awards just one head.
        withRolls(intArrayOf(vorkathDropTable.slot("obj.rune_longsword"), vorkathDropTable.slot("obj.rune_longsword")), chance = 0.0) {
            val drops = vorkathDropTable.roll(playerWithKills(50)).items()
            assertEquals(1, drops.count { it.obj == "obj.vorkath_head" })
        }
    }

    @Test
    fun `Vorkath post-quest rewards are not registered for quest or sleeping forms`() {
        assertEquals(listOf("npc.vorkath"), vorkathDropTable.npcs)
        val repeatable = checkNotNull(ServerCacheManager.getNpc("npc.vorkath".asRSCM()))
        val quest = checkNotNull(ServerCacheManager.getNpc("npc.vorkath_quest".asRSCM()))
        val killcountParam = "param.killcount_varp".asRSCM()
        assertEquals("varp.total_vorkath_kills".asRSCM(), checkNotNull(repeatable.paramsRaw)[killcountParam])
        assertFalse(checkNotNull(quest.paramsRaw).containsKey(killcountParam))
    }

    @Test
    fun `Vorkath ordinary elite clue has unboosted one in 65 probability`() {
        val player = equippedPlayer()
        assertEquals(100.0 / 65, vorkathDropTable.tertiaries().entry("obj.trail_elite_emote_exp1", player).chanceFor(player), 1e-12)
    }

    @Test
    fun `shared rare table preserves all 128 slots and includes both paths to key halves`() {
        for (ring in listOf(false, true)) {
            val player = equippedPlayer(ring)
            val gems = probabilities(SharedDropTables.gem, player)
            val rare = probabilities(SharedDropTables.rareDrop, player)
            val gemPool = if (ring) 65.0 else 128.0
            assertEquals(1.0, rare.values.sum(), 1e-12)
            assertEquals(1.0, gems.values.sum(), 1e-12)
            assertEquals(21.0 / 128, rare["obj.coins"]!!, 1e-12)
            for (key in listOf("obj.keyhalf1", "obj.keyhalf2")) {
                assertEquals(20.0 / 128 + 20.0 / 128 / gemPool, rare[key]!!, 1e-12)
                assertEquals(1.0 / gemPool, gems[key]!!, 1e-12)
            }
            assertEquals(32.0 / gemPool, gems["obj.uncut_sapphire"]!!, 1e-12)
            assertEquals(!ring, rare.containsKey(""), "ring removes nested empty outcomes")
            for ((boss, access, commonCoins) in listOf(
                Triple(cerberusDropTable, 3.0 / 130, 5.0 / 130),
                Triple(vorkathDropTable, 5.0 / 150, 5.0 / 150),
            )) {
                val main = probabilities(boss.main(), player)
                assertEquals(1.0, main.values.sum(), 1e-12)
                assertEquals(commonCoins + access * 21.0 / 128, main["obj.coins"]!!, 1e-12)
                assertEquals(access * rare["obj.keyhalf1"]!!, main["obj.keyhalf1"]!!, 1e-12)
            }
        }
    }

    @Test
    fun `tree herb seed rolls are complete and shark lure quantities are only doubled shark amounts`() {
        assertEquals(16, probabilities(SharedDropTables.treeHerbSeed).size)
        assertEquals(1.0, probabilities(SharedDropTables.treeHerbSeed).values.sum(), 1e-12)
        // Resolve every new gameval so a typo cannot produce invalid loot at runtime.
        assertAll(SharedDropTables.treeHerbSeed.tableEntries.map { entry -> Executable {
            val item = (entry.rollable as Single<Player, DropRollItem>).result
            assertTrue(ServerCacheManager.getItem(item.obj.asRSCM()) != null, item.obj)
            assertEquals(if (item.obj == "obj.watermelon_seed") 15..15 else 1..1, item.count)
        } })
        val foods = vorkathDropTable.main().tableEntries.map { it.rollable }
            .filterIsInstance<RSWeightedTable<Player, DropRollItem>>()
            .single { table -> table.tableEntries.any { (it.rollable as? Single<Player, DropRollItem>)?.result?.obj == "obj.shark_lure" } }
        val lure = foods.tableEntries.map { (it.rollable as Single<Player, DropRollItem>).result }
            .single { it.obj == "obj.shark_lure" }
        val random = mockk<GameRandom>()
        val counts = (0..20).map { index ->
            every { random.of(21) } returns index
            lure.rollCount(random)
        }
        assertEquals((70..110 step 2).toList(), counts)
    }

    @Test
    fun `chaos talisman region includes only the original ground-plane rectangle`() {
        for (x in listOf(1919, 1920, 3000, 3903, 3904)) {
            for (z in listOf(6400, 9473, 9474, 10000, 11263, 11264)) {
                for (level in 0..3) {
                    val coord = CoordGrid(x, z, level)
                    val expected = level == 0 && x != 1919 && x != 3904 && z in listOf(9474, 10000, 11263)
                    assertEquals(expected, coord.isChaosTalismanArea(), coord.toString())
                }
            }
        }
    }

    @Test
    fun `talisman outcome uses the player position even when NPC context disagrees or is absent`() {
        for ((coord, expected, other) in listOf(
            Triple(CoordGrid(3000, 10000), "obj.chaos_talisman", "obj.nature_talisman"),
            Triple(CoordGrid(3000, 3200), "obj.nature_talisman", "obj.chaos_talisman"),
            Triple(CoordGrid(3000, 10000, 1), "obj.nature_talisman", "obj.chaos_talisman"),
        )) {
            val player = equippedPlayer().apply { coords = coord }
            val opposite = if (coord.z < 9474) CoordGrid(3000, 10000) else CoordGrid(3000, 3200)
            for (args in listOf(ArgMap.Empty, ArgMap(
                KillRollContext.npc with Npc("npc.vorkath", opposite),
                KillRollContext.areaChecker with mockk<AreaChecker>(),
            ))) {
                val gems = probabilities(SharedDropTables.gem, player, args)
                assertEquals(4.0 / 128, gems[expected]!!, 1e-12)
                assertFalse(gems.containsKey(other))
            }
        }
    }

    @Test
    fun `gem quest gate preserves pool size and replaces the entire mega roll with a talisman`() {
        val end = QuestRow.getRow("dbrow.quest_legends").endstate
        for (ring in listOf(false, true)) {
            for (stage in listOf(0, 1, end - 1, end, end + 1)) {
                val player = equippedPlayer(ring).apply {
                    coords = CoordGrid(3000, 10000)
                    vars.backing.put("varp.legendsquest".asRSCM(), stage)
                }
                val complete = stage >= end
                val pool = if (ring) 65.0 else 128.0
                val megaPool = if (ring) 15.0 else 128.0
                val gems = probabilities(SharedDropTables.gem, player, ArgMap.Empty)
                val rare = probabilities(SharedDropTables.rareDrop, player, ArgMap.Empty)
                assertEquals(pool.toInt(), SharedDropTables.gem.selectEntries(player, ArgMap.Empty).maxOf { it.rangeEnd })
                assertEquals(1.0, gems.values.sum(), 1e-12)
                assertEquals((if (complete) 3.0 else 4.0) / pool, gems["obj.chaos_talisman"]!!, 1e-12)
                val gemSpear = if (complete) 8.0 / (pool * megaPool) else 0.0
                assertEquals(gemSpear, gems["obj.rune_spear"] ?: 0.0, 1e-12)
                assertEquals(15.0 / 128 * 8 / megaPool + 20.0 / 128 * gemSpear, rare["obj.rune_spear"]!!, 1e-12)
            }
        }
    }

    @Test
    fun `uncompleted gem mega slot returns a talisman without consuming another random roll`() {
        for (ring in listOf(false, true)) {
            val player = equippedPlayer(ring).apply { coords = CoordGrid(3000, 10000) }
            val slot = SharedDropTables.gem.selectEntries(player, ArgMap.Empty).last().rangeStart
            withRolls(intArrayOf(slot)) {
                assertEquals(listOf("obj.chaos_talisman"), SharedDropTables.gem.roll(player).items().map { it.obj })
            }
        }
    }

    @Test
    fun `completed gem mega slot rolls the actual shared table with ring nothing removal`() {
        for (ring in listOf(false, true)) {
            val player = equippedPlayer(ring).apply {
                vars.backing.put("varp.legendsquest".asRSCM(), QuestRow.getRow("dbrow.quest_legends").endstate)
            }
            val slot = SharedDropTables.gem.selectEntries(player, ArgMap.Empty).last().rangeStart
            val shieldSlot = SharedDropTables.megaRare.selectEntries(player, ArgMap.Empty).single {
                (it.rollable as? Single<Player, DropRollItem>)?.result?.obj == "obj.dragonshield_a"
            }.rangeStart
            withRolls(intArrayOf(slot, shieldSlot)) {
                assertEquals(listOf("obj.dragonshield_a"), SharedDropTables.gem.roll(player).items().map { it.obj })
            }
        }
    }

    @Test
    fun `explicit quest policy can unlock gem access without rewriting saved progress`() {
        val player = equippedPlayer()
        assertFalse(player.hasCompletedLegendsQuest())
        QuestRequirements.install(QuestRequirementPolicy(
            QuestRequirementMode.VirtualCompletions, virtualCompletions = setOf("quest_legends"),
        ))
        assertTrue(player.hasCompletedLegendsQuest())
        assertEquals(0, player.vars["varp.legendsquest"])
        assertTrue(probabilities(SharedDropTables.gem, player).containsKey("obj.rune_spear"))
    }

    /** Enumerates slot widths (including nested tables) instead of sampling random frequencies. */
    private fun probabilities(
        table: RSWeightedTable<Player, DropRollItem>,
        player: Player = equippedPlayer(),
        args: ArgMap = ArgMap(
            KillRollContext.npc with Npc("npc.vorkath", CoordGrid(3200, 3200)),
            KillRollContext.areaChecker with mockk<AreaChecker>(),
        ),
    ): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        val entries = table.selectEntries(player, args)
        val total = entries.maxOf { it.rangeEnd }
        for (entry in entries) {
            val probability = (entry.rangeEnd - entry.rangeStart).toDouble() / total
            val nested = entry.rollable
            when {
                nested is RSWeightedTable -> probabilities(nested, player, args).forEach { (obj, chance) -> result.merge(obj, probability * chance, Double::plus) }
                else -> {
                    val drop = nested.roll(player, args).items().single()
                    result.merge(drop.obj, probability, Double::plus)
                }
            }
        }
        return result
    }

    private fun withRolls(ints: IntArray, chance: Double = 99.0, block: () -> Unit) {
        var index = 0
        mockkObject(Random.Default)
        try {
            every { Random.Default.nextInt(any<Int>(), any<Int>()) } answers {
                val value = ints[index++]
                check(value in firstArg<Int>() until secondArg<Int>())
                value
            }
            every { Random.Default.nextDouble(any<Double>(), any<Double>()) } returns chance
            block()
            assertEquals(ints.size, index, "every expected main-table roll must be consumed")
        } finally {
            unmockkObject(Random.Default)
        }
    }

    private fun RSDropTable<Player, DropRollItem>.main() =
        tableEntries.elementAt(3) as RSWeightedTable<Player, DropRollItem>

    private fun RSDropTable<Player, DropRollItem>.slot(obj: String): Int =
        main().selectEntries(equippedPlayer(), ArgMap.Empty).single {
            (it.rollable as? Single<Player, DropRollItem>)?.result?.obj == obj
        }.rangeStart

    private fun RSDropTable<Player, DropRollItem>.tertiaries() =
        tableEntries.last() as RSPreRollTable<Player, DropRollItem>

    private fun RSPreRollTable<Player, DropRollItem>.entry(obj: String, player: Player): ChanceRollable<Player, DropRollItem> =
        tableEntries.single { it.rollable.roll(player).items().any { drop -> drop.obj == obj } }

    private fun RollResult<DropRollItem>.items(): List<DropRollItem> = when (this) {
        is RollResult.Nothing -> emptyList()
        is RollResult.Single -> listOf(result)
        is RollResult.ListOf -> results
    }

    private fun playerWithKills(kills: Int): Player = equippedPlayer().apply {
        vars.backing.put("varp.total_vorkath_kills".asRSCM(), kills)
    }

    private fun equippedPlayer(ring: Boolean = false): Player = Player().apply {
        coords = CoordGrid(3200, 3200)
        inv = Inventory.create("inv.inv")
        worn = Inventory.create("inv.worn")
        if (ring) worn.objs[Wearpos.Ring.slot] = InvObj("obj.ring_of_wealth")
    }

    companion object {
        @JvmStatic @BeforeAll fun cache() { ServerCacheManager.init(241).close() }
    }
}
