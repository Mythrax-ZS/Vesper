package org.rsmod.content.areas.wilderness

import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.map.area.MapAreaDecoder
import dev.openrune.map.area.MapAreaDefinitions
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import io.mockk.mockk
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import net.rsprot.protocol.game.outgoing.varp.VarpSmall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.flushVarpsSetBeforeFirstTick
import org.rsmod.api.registry.loc.LocRegistryNormal
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.registry.region.RegionRegistryResult
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.spells.runes.BlightedRunePackPermission
import org.rsmod.api.spells.runes.BlightedRunePackPolicy
import org.rsmod.events.EventBus
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.region.Region
import org.rsmod.game.region.RegionListLarge
import org.rsmod.game.region.RegionListSmall
import org.rsmod.game.region.RegionListWorldEntity
import org.rsmod.game.region.util.RegionRotations
import org.rsmod.game.region.zone.RegionZoneCopy
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey
import org.rsmod.map.zone.ZoneGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/** Real packed geography and varp packets; client CS2 rendering is validated separately. */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class WildernessBlightedRunePackPermissionTest {
    @Test
    fun `packed Wilderness Ferox upper floors and Mage Bank permit sacks without UI state`() {
        val f = Fixture()
        for (coords in listOf(
            WILDERNESS, CoordGrid(3010, 10120), CoordGrid(3130, 3630),
            CoordGrid(3132, 3621, 1), CoordGrid(3137, 3644, 1), CoordGrid(3141, 3637, 2),
            MAGE_BANK, CoordGrid(2508, 4696),
        )) {
            f.player.coords = coords
            assertEquals(0, f.player.vars[PERMISSION_BIT])
            assertTrue(f.permission.permits(f.player), "Missing packed permission at $coords")
        }
    }

    @Test
    fun `safe towns Ferox dungeon and Mage Bank adjacent squares cannot forge permission`() {
        val f = Fixture()
        for (coords in listOf(
            SAFE, CoordGrid(3150, 10015), CoordGrid(3156, 10024),
            CoordGrid(2495, 4715), CoordGrid(2560, 4715),
            CoordGrid(2538, 4671), CoordGrid(2538, 4736),
            MAGE_BANK.copy(level = 1), MAGE_BANK.copy(level = 2), MAGE_BANK.copy(level = 3),
        )) {
            f.player.coords = coords
            VarPlayerIntMapSetter.set(f.player, PERMISSION_BIT, 1)
            assertFalse(f.permission.permits(f.player), "Forged flag authorized $coords")
        }
    }

    @Test
    fun `current coordinates are authoritative before the next UI refresh`() {
        val f = Fixture()
        f.player.coords = WILDERNESS
        f.sync.onPostTick(f.player)
        assertEquals(1, f.player.vars[PERMISSION_BIT])

        f.player.coords = SAFE
        assertFalse(f.permission.permits(f.player))
        assertEquals(1, f.player.vars[PERMISSION_BIT], "Fixture retains stale client state")
        f.sync.onPostTick(f.player)
        assertEquals(0, f.player.vars[PERMISSION_BIT])

        f.player.coords = MAGE_BANK
        assertTrue(f.permission.permits(f.player))
        assertEquals(0, f.player.vars[PERMISSION_BIT], "No delayed flag may veto a valid cast")
    }

    @Test
    fun `copied regions use original geography and planes for every rotation`() {
        val cases = listOf(
            WILDERNESS to true,
            CoordGrid(3137, 3644, 1) to true,
            CoordGrid(3141, 3637, 2) to true,
            MAGE_BANK to true,
            CoordGrid(2508, 4696) to true,
            MAGE_BANK.copy(level = 1) to false,
            SAFE to false,
            CoordGrid(3150, 10015) to false,
        )
        for (rotation in 0..3) {
            for ((normal, allowed) in cases) {
                val f = Fixture()
                f.player.coords = f.copy(f.region(), normal, rotation, (normal.level + 1) % 4)
                assertEquals(normal, f.areas.normalizedCoords(f.player.coords))
                assertEquals(allowed, f.permission.permits(f.player), "$normal rotation=$rotation")
            }
        }
        val f = Fixture()
        f.player.coords = f.region().southWest
        assertFalse(f.permission.permits(f.player), "Unmapped instance space must not grant")
    }

    @Test
    fun `registered login corrects the permission bit while preserving sibling native state`() {
        val f = Fixture()
        VarPlayerIntMapSetter.set(f.player, PARENT_VARP, SIBLING_STATE)
        f.client.messages.clear()
        f.player.coords = MAGE_BANK
        f.login()
        assertEquals(1, f.player.vars[PERMISSION_BIT])
        assertEquals(SIBLING_STATE or Int.MIN_VALUE, f.player.vars[PARENT_VARP])
        assertEquals(listOf(parentId() to (SIBLING_STATE or Int.MIN_VALUE)), f.client.varps())

        f.player.coords = SAFE
        f.client.messages.clear()
        f.login()
        assertEquals(0, f.player.vars[PERMISSION_BIT])
        assertEquals(SIBLING_STATE, f.player.vars[PARENT_VARP])
        assertEquals(listOf(parentId() to SIBLING_STATE), f.client.varps())
    }

    @Test
    fun `post tick emits exactly one parent update per permission transition`() {
        val f = Fixture()
        f.player.coords = WILDERNESS
        repeat(3) { f.sync.onPostTick(f.player) }
        f.player.coords = MAGE_BANK
        repeat(3) { f.sync.onPostTick(f.player) }
        assertEquals(listOf(parentId() to Int.MIN_VALUE), f.client.varps())

        f.player.coords = SAFE
        repeat(3) { f.sync.onPostTick(f.player) }
        assertEquals(listOf(parentId() to Int.MIN_VALUE, parentId() to 0), f.client.varps())

        f.player.coords = CoordGrid(3132, 3621, 1)
        f.sync.onPostTick(f.player)
        assertEquals(listOf(parentId() to Int.MIN_VALUE, parentId() to 0, parentId() to Int.MIN_VALUE), f.client.varps())
        assertEquals(3, f.client.messages.size, "Unchanged ticks must not send unrelated packets")
    }

    @Test
    fun `cold login defers native update until first tick flush without losing it`() {
        val f = Fixture()
        f.player.processedMapClock = 0
        f.player.coords = MAGE_BANK
        f.login()
        assertTrue(f.client.messages.isEmpty())
        assertTrue(parentId() in f.player.varpsSetBeforeFirstTick)

        f.player.processedMapClock = 1
        f.player.flushVarpsSetBeforeFirstTick()
        f.sync.onPostTick(f.player)
        assertEquals(listOf(parentId() to Int.MIN_VALUE), f.client.varps())
        assertTrue(f.player.varpsSetBeforeFirstTick.isEmpty())
    }

    @Test
    fun `cold relog clears stale granted state before the first client tick`() {
        val f = Fixture()
        f.player.processedMapClock = 0
        f.player.vars.backing[parentId()] = SIBLING_STATE or Int.MIN_VALUE
        f.player.coords = SAFE
        f.login()
        assertEquals(0, f.player.vars[PERMISSION_BIT])
        assertTrue(f.client.messages.isEmpty())
        f.player.processedMapClock = 1
        f.player.flushVarpsSetBeforeFirstTick()
        assertEquals(listOf(parentId() to SIBLING_STATE), f.client.varps())
    }

    @Test
    fun `UI synchronization uses aggregate permissions without turning the flag into a grant`() {
        var permittedElsewhere = true
        val extra = object : BlightedRunePackPermission {
            override fun permits(player: Player): Boolean = permittedElsewhere
        }
        val f = Fixture(setOf(extra))
        f.player.coords = SAFE
        f.login()
        assertEquals(1, f.player.vars[PERMISSION_BIT])
        assertFalse(f.permission.permits(f.player), "UI state must not authorize the geography provider")

        permittedElsewhere = false
        f.sync.onPostTick(f.player)
        assertEquals(0, f.player.vars[PERMISSION_BIT])
        assertEquals(listOf(parentId() to Int.MIN_VALUE, parentId() to 0), f.client.varps())
    }

    private class Fixture(extra: Set<BlightedRunePackPermission> = emptySet()) {
        val client = RecordingClient()
        val player = Player().apply {
            worldType = WorldType.MAIN
            coords = SAFE
            processedMapClock = 100
            client = this@Fixture.client
        }
        private val collision = CollisionFlagMap()
        private val locs = LocZoneStorage()
        val regions = RegionRegistry(
            RegionListSmall(), RegionListLarge(), RegionListWorldEntity(),
            LocRegistryNormal(ZoneUpdateMap(), collision, locs), collision, locs,
            mockk(), mockk(), ZonePlayerActivityBitSet(),
        )
        val areas = AreaChecker(regions, areaIndex)
        val permission = WildernessBlightedRunePackPermission(areas)
        val sync = BlightedRunePackSync(BlightedRunePackPolicy(extra + permission))
        private val events = EventBus()

        init {
            with(BlightedRunePackScript(sync)) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        fun login() {
            events.publish(SessionStateEvent.Login(player))
        }

        fun region(): Region = (regions.registerSmall() as RegionRegistryResult.Add.Success).region

        fun copy(region: Region, normal: CoordGrid, rotation: Int, destinationLevel: Int): CoordGrid {
            val destination = region.southWest.copy(level = destinationLevel)
            regions.registerZone(region, ZoneKey.from(destination), RegionZoneCopy(ZoneKey.from(normal), rotation, null))
            return destination.translate(RegionRotations.translateCoords(rotation, ZoneGrid.from(normal)))
        }
    }

    private class RecordingClient : Client<Any, Any> {
        val messages = mutableListOf<Any>()
        override fun write(message: Any) { messages += message }
        override fun close() = Unit
        override fun read(player: Player) = Unit
        override fun flush() = Unit
        override fun flushHighPriority() = Unit
        override fun unregister(service: Any, player: Player) = Unit
        fun varps(): List<Pair<Int, Int>> = messages.map {
            when (it) {
                is VarpSmall -> it.id to it.value
                is VarpLarge -> it.id to it.value
                else -> error("Unexpected packet: $it")
            }
        }
    }

    companion object {
        private const val PERMISSION_BIT = "varbit.blighted_items_allowed"
        private const val PARENT_VARP = "varp.zeah_temp_2"
        private const val SIBLING_STATE = 0x423
        private val WILDERNESS = CoordGrid(3201, 3554)
        private val SAFE = CoordGrid(3222, 3218)
        private val MAGE_BANK = CoordGrid(2538, 4715)
        private lateinit var areaIndex: AreaIndex
        private fun parentId(): Int = PARENT_VARP.asRSCM(RSCMType.VARP)

        @JvmStatic
        @BeforeAll
        fun cache() {
            val cache = ServerCacheManager.init(241)
            try {
                areaIndex = AreaIndex()
                for (region in cache.archives(MAPS)) {
                    if (region > 25286 || 7 !in cache.files(MAPS, region)) continue
                    if (cache.data(MAPS, region, 0) == null || cache.data(MAPS, region, 1) == null) continue
                    val data = checkNotNull(cache.data(MAPS, region, 7))
                    MapAreaDefinitions.putAreas(areaIndex, MapSquareKey(region), MapAreaDecoder.decode(InlineByteBuf(data)))
                }
            } finally {
                cache.close()
            }
        }
    }
}
