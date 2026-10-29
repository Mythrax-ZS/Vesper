package org.rsmod.content.other.pets

import dev.openrune.ServerCacheManager
import dtx.core.ArgMap
import dtx.core.Single
import dtx.core.with
import dtx.rs.RSPreRollTable
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.random.Random
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.death.NpcDeathDropContext
import org.rsmod.api.death.NpcDeathDropHook
import org.rsmod.api.death.NpcDeathKillContext
import org.rsmod.api.death.NpcDropReceiveHook
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.DropTableRegistry
import org.rsmod.api.droptable.KillRollContext
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.table.PetDropsRow
import org.rsmod.content.drops.NpcDropTableKillHook
import org.rsmod.content.drops.toml.ContentDropTableTomlResolver
import org.rsmod.content.interfaces.collectionlog.collectionTransmit
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.client.NoopClient
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scan.PluginClasspathScan
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
@ResourceLock("kotlin.random.Random.Default")
@ResourceLock("InvTransactions")
class PetDropOwnershipTest {
    @Test
    fun `rebuilt fallback table contains only five NPCs without a registered pet chance`() {
        val rows = PetDropsRow.all()
        assertEquals(5, rows.size)
        assertEquals(
            setOf("npc.mad_angel", "npc.mad_angel_quest", "npc.inferno_tzkalzuk_placeholder",
                "npc.maggot_king", "npc.gargboss_dusk_phase4"),
            rows.map { it.npc.internalName }.toSet(),
        )
        for (row in rows) {
            val table = registry.forNpc(row.npc.internalName) ?: continue
            val directChances = table.tableEntries.filterIsInstance<RSPreRollTable<Player, DropRollItem>>()
                .flatMap { it.tableEntries }
                .mapNotNull { (it.rollable as? Single<Player, DropRollItem>)?.result }
            assertFalse(directChances.any { it.obj == row.pet.internalName }, row.npc.internalName)
        }
    }

    @Test
    fun `Mole kill rolls one pet chance and awards one insured follower without a ground copy`() {
        val f = MoleDeath()
        f.kill(petSuccess = true)

        val pet = checkNotNull(Pets.forObj("obj.molepet")).first
        assertEquals(pet.base.objId, f.player.followerObj)
        assertTrue(f.insurance.isInsured(f.player, pet))
        assertEquals(0, f.player.inv.physicalCount("obj.molepet"))
        assertEquals(1, f.player.collectionTransmit.physicalCount("obj.molepet"))
        assertEquals(1, f.client.messages.count { "funny feeling" in it.toString() })
        assertEquals(1, f.client.messages.count { "automatically insured" in it.toString() })
        verify(exactly = 1) { f.followers.spawn(f.player, any()) }
        f.assertOrdinaryLoot(kills = 1)
    }

    @Test
    fun `failed Mole pet chance has no fallback second roll or pet reward`() {
        val f = MoleDeath()
        f.kill(petSuccess = false, fallbackFirst = true)

        assertFalse(f.insurance.isInsured(f.player, checkNotNull(Pets.forObj("obj.molepet")).first))
        assertEquals(0, f.player.followerObj)
        assertEquals(0, f.player.inv.physicalCount("obj.molepet"))
        assertEquals(0, f.player.collectionTransmit.physicalCount("obj.molepet"))
        assertTrue(f.client.messages.none { "funny feeling" in it.toString() })
        verify(exactly = 0) { f.followers.spawn(any(), any()) }
        f.assertOrdinaryLoot(kills = 1)
    }

    @Test
    fun `another successful kill preserves ownership without a second physical pet`() {
        val f = MoleDeath()
        f.kill(petSuccess = true)
        f.kill(petSuccess = true, fallbackFirst = true)

        verify(exactly = 1) { f.followers.spawn(f.player, any()) }
        assertEquals(0, f.player.inv.physicalCount("obj.molepet"))
        assertEquals(2, f.player.collectionTransmit.physicalCount("obj.molepet"))
        assertEquals(1, f.client.messages.count { "would have been followed" in it.toString() })
        assertEquals(1, f.client.messages.count { "automatically insured" in it.toString() })
        f.assertOrdinaryLoot(kills = 2)
    }

    @Test
    fun `full inventory with a different follower keeps the successful pet insured for reclaim`() {
        val f = MoleDeath()
        f.player.followerObj = checkNotNull(Pets.forObj("obj.krakenpet")).second.objId
        f.player.inv.indices.forEach { f.player.inv.objs[it] = InvObj("obj.shark") }
        val before = f.player.inv.objs.copyOf()

        f.kill(petSuccess = true)

        assertTrue(f.insurance.isInsured(f.player, checkNotNull(Pets.forObj("obj.molepet")).first))
        assertTrue(before.contentEquals(f.player.inv.objs))
        assertEquals(0, f.player.inv.physicalCount("obj.molepet"))
        assertEquals(1, f.player.collectionTransmit.physicalCount("obj.molepet"))
        assertEquals(1, f.client.messages.count { "Probita in East Ardougne" in it.toString() })
        verify(exactly = 0) { f.followers.spawn(any(), any()) }
        f.assertOrdinaryLoot(kills = 1)
    }

    @Test
    fun `remaining fallback bosses each retain exactly one hit or miss roll`() {
        for (row in PetDropsRow.all()) {
            for (success in listOf(false, true)) {
                val rewards = mockk<PetRewards>()
                val random = mockk<GameRandom>()
                val player = player()
                every { random.of(row.rate) } returns if (success) 0 else row.rate - 1
                every { rewards.give(player, row.pet.internalName) } returns true

                PetNpcDropHook(rewards, random, emptySet()).onKill(
                    NpcDeathKillContext(player, Npc(row.npc.internalName, player.coords), 1),
                )

                verify(exactly = 1) { random.of(row.rate) }
                verify(exactly = if (success) 1 else 0) { rewards.give(player, row.pet.internalName) }
            }
        }
    }

    @Test
    fun `suppressed deaths bypass both canonical and fallback pet rolls`() {
        val suppressor = object : NpcDeathDropHook {
            override fun tryConsume(context: NpcDeathDropContext): Boolean = false
            override fun suppressesDropTable(context: NpcDeathKillContext): Boolean = true
        }
        val f = MoleDeath(setOf(suppressor))
        f.tableHook.onKill(f.context)
        f.fallbackHook.onKill(f.context)
        val fallbackNpc = PetDropsRow.all().first().npc.internalName
        f.fallbackHook.onKill(f.context.copy(npc = Npc(fallbackNpc, f.player.coords)))

        assertTrue(f.ordinaryLoot.isEmpty())
        assertTrue(f.client.messages.isEmpty())
        verify { f.fallbackRandom wasNot Called }
        verify { f.countRandom wasNot Called }
        verify { f.ground wasNot Called }
    }

    private class MoleDeath(extraHooks: Set<NpcDeathDropHook> = emptySet()) {
        val client = RecordingClient()
        val player = player(client)
        val context = NpcDeathKillContext(player, Npc("npc.mole_giant", player.coords), 1)
        val followers = mockk<PetFollowers>()
        val insurance = PetInsurance()
        val fallbackRandom = mockk<GameRandom>()
        val countRandom = mockk<GameRandom>()
        val ground = mockk<ObjRepository>()
        val ordinaryLoot = mutableListOf<Pair<String, Int>>()
        val tableHook: NpcDropTableKillHook
        val fallbackHook: PetNpcDropHook

        init {
            every { followers.hasFollower(player) } answers { player.followerObj != 0 }
            every { followers.spawn(player, any()) } answers { player.followerObj = secondArg<PetForm>().objId }
            every { fallbackRandom.of(any<Int>()) } returns 0
            every { countRandom.of(any<Int>(), any<Int>()) } answers { firstArg() }
            val rewards = PetRewards(followers, insurance, mockk())
            val hooks = extraHooks + PetDropHook(rewards)
            val receiver = NpcDropReceiveHook { _, obj, count ->
                if (Pets.forObj(obj) != null) false else {
                    ordinaryLoot += obj to count
                    true
                }
            }
            tableHook = NpcDropTableKillHook(registry, areas, ground, countRandom, hooks, setOf(receiver))
            fallbackHook = PetNpcDropHook(rewards, fallbackRandom, hooks)
        }

        fun kill(petSuccess: Boolean, fallbackFirst: Boolean = false) {
            val args = ArgMap(
                KillRollContext.npc with context.npc,
                KillRollContext.areaChecker with areas,
                KillRollContext.dropCoords with context.dropCoords,
            )
            val table = checkNotNull(registry.forNpc(context.npc, areas))
            val tertiaries = table.tableEntries.last() as RSPreRollTable<Player, DropRollItem>
            val rolledEntries = tertiaries.tableEntries.filter { it.chance < 100 && it.includeInRoll(player, args) }
            val petEntries = rolledEntries.filter { (it.rollable as? Single<Player, DropRollItem>)?.result?.obj == "obj.molepet" }
            assertEquals(1, petEntries.size)
            assertEquals(100.0 / 3000, petEntries.single().chanceFor(player, args), 1e-12)
            val petIndex = rolledEntries.indexOf(petEntries.single())
            var chanceDraws = 0
            mockkObject(Random.Default)
            try {
                every { Random.Default.nextInt(any<Int>(), any<Int>()) } answers { firstArg() }
                every { Random.Default.nextDouble(any<Double>(), any<Double>()) } answers {
                    if (chanceDraws++ == petIndex && petSuccess) 0.0 else 99.0
                }
                if (fallbackFirst) fallbackHook.onKill(context)
                tableHook.onKill(context)
                if (!fallbackFirst) fallbackHook.onKill(context)
                assertEquals(rolledEntries.size, chanceDraws)
            } finally {
                unmockkObject(Random.Default)
            }
            verify { fallbackRandom wasNot Called }
            verify { ground wasNot Called }
        }

        fun assertOrdinaryLoot(kills: Int) {
            assertEquals(kills * 3, ordinaryLoot.size)
            for (obj in listOf("obj.mole_claw", "obj.mole_skin", "obj.adamant_longsword")) {
                assertEquals(kills, ordinaryLoot.filter { it.first == obj }.sumOf { it.second }, obj)
            }
        }
    }

    private class RecordingClient : Client<Any, Any> by NoopClient {
        val messages = mutableListOf<Any>()
        override fun write(message: Any) { messages += message }
    }

    companion object {
        private val RESTORATIONS = mutableListOf<() -> Unit>()
        private lateinit var areas: AreaChecker
        private lateinit var registry: DropTableRegistry

        private fun player(client: Client<Any, Any> = NoopClient): Player = Player(client = client).apply {
            worldType = WorldType.MAIN
            accountId = 1
            characterId = 1
            coords = CoordGrid(1759, 5189)
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            lootDropDuration = 100
        }

        @JvmStatic @BeforeAll
        fun initialize() {
            ServerCacheManager.init(241).close()
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val old = field.get(null)
                RESTORATIONS += { field.set(null, old) }
            }
            val oldStorage = InvVirtualStorageHolder.instance
            RESTORATIONS += { InvVirtualStorageHolder.instance = oldStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
            areas = mockk<AreaChecker> { every { inArea(any(), any<CoordGrid>()) } returns false }
            registry = DropTableRegistry(ContentDropTableTomlResolver(areas, mockk()))
        }

        @JvmStatic @AfterAll
        fun restoreGlobals() {
            PluginClasspathScan.release()
            RESTORATIONS.asReversed().forEach { it() }
            RESTORATIONS.clear()
        }
    }
}
