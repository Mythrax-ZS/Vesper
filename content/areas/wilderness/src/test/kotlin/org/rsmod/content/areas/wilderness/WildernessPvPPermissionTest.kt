package org.rsmod.content.areas.wilderness

import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.map.area.MapAreaDecoder
import dev.openrune.map.area.MapAreaDefinitions
import dev.openrune.map.util.InlineByteBuf
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.area.checker.wildernessLevel
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.loc.LocRegistryNormal
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.registry.region.RegionRegistryResult
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.canPvp
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.hasWildernessOptOut
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.setCanPvp
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.setWildernessOptOut
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.entity.Player
import org.rsmod.game.map.LocZoneStorage
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
import org.rsmod.routefinder.collision.CollisionFlagMap

/** Context permission tests, not proof of live Ferox apron geometry or a complete PvP dispatcher. */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class WildernessPvPPermissionTest {
    @Test
    fun `fresh player state does not need the delayed attack menu flag to grant Wilderness context`() {
        val f = Fixture()
        assertFalse(f.attacker.canPvp())
        assertFalse(f.target.canPvp())
        assertTrue(f.permits())

        f.attacker.setCanPvp(true)
        val restored = player(f.attacker.coords).apply {
            attr.putAllFromPersistence(f.attacker.attr.toPersistentMap())
        }
        assertFalse(restored.canPvp(), "The area/menu flag must not survive persistence")
        assertTrue(f.permission.permits(restored, f.target))
    }

    @Test
    fun `current positions reject stale true menu flags immediately on either side of the border`() {
        val f = Fixture()
        f.attacker.setCanPvp(true)
        f.target.setCanPvp(true)
        assertTrue(f.permits())

        f.target.coords = SAFE
        assertTrue(f.target.canPvp(), "Fixture deliberately retains the old area flag")
        assertFalse(f.permits())
        assertFalse(f.permission.permits(f.target, f.attacker))

        f.attacker.coords = SAFE
        assertFalse(f.permits())
        f.target.coords = WILDERNESS
        assertFalse(f.permits())
        f.attacker.coords = WILDERNESS
        assertTrue(f.permits())
    }

    @Test
    fun `legacy custom opt out is an independent veto and survives persistence for either participant`() {
        val f = Fixture()
        for (subject in listOf(f.attacker, f.target)) {
            subject.setCanPvp(true)
            subject.setWildernessOptOut(true)
            assertFalse(f.permits())
            assertFalse(f.permission.permits(f.target, f.attacker))

            val restored = player(subject.coords).apply {
                attr.putAllFromPersistence(subject.attr.toPersistentMap())
            }
            assertTrue(restored.hasWildernessOptOut())
            assertFalse(f.permission.permits(restored, player(WILDERNESS)))

            subject.setWildernessOptOut(false)
            assertTrue(f.permits())
        }
    }

    @Test
    fun `packed ditch boundary and included dungeon areas control the grant`() {
        val f = Fixture()
        for (tile in listOf(CoordGrid(3200, 3525), CoordGrid(3010, 10120), CoordGrid(3330, 10050), CoordGrid(1750, 11540))) {
            f.attacker.coords = tile
            f.target.coords = tile.translate(1, 0)
            assertTrue(f.permits(), "Packed Wilderness area missing at $tile")
        }
        for (tile in listOf(CoordGrid(3200, 3524), SAFE, CoordGrid(2271, 4695))) {
            f.attacker.coords = tile
            f.target.coords = WILDERNESS
            assertFalse(f.permits(), "Non-Wilderness tile granted at $tile")
        }
    }

    @Test
    fun `packed Ferox exclusion cannot be bypassed by menu flags or either teleblock variable`() {
        val f = Fixture()
        f.attacker.coords = CoordGrid(3122, 3628)
        f.target.coords = CoordGrid(3123, 3628)
        assertTrue(f.areas.inArea("area.wilderness", f.attacker.coords))
        assertTrue(f.areas.inArea("area.ferox_enclave", f.target.coords))
        f.attacker.setCanPvp(true)
        f.target.setCanPvp(true)

        for (attackerBlock in listOf(0, 1)) {
            for (targetBlock in listOf(0, 1)) {
                VarPlayerIntMapSetter.set(f.attacker, "varbit.teleblock_cycles", attackerBlock)
                VarPlayerIntMapSetter.set(f.target, "varbit.teleblock_cycles", targetBlock)
                assertFalse(f.permits())
                assertFalse(f.permission.permits(f.target, f.attacker))
            }
        }
        f.target.coords = CoordGrid(3130, 3630)
        assertFalse(f.permits())
        f.attacker.coords = f.target.coords
        assertFalse(f.permits())
    }

    @Test
    fun `all rotations preserve source depth and grant only within the same allocated copy`() {
        for (rotation in 0..3) {
            val f = Fixture()
            val region = f.region()
            val normal = CoordGrid(3001, 3906)
            f.attacker.coords = f.copy(region, normal, rotation, destinationLevel = rotation)
            f.target.coords = f.attacker.coords.translate(1, 0)

            assertEquals(normal, f.areas.normalizedCoords(f.attacker.coords))
            assertEquals(49, normal.wildernessLevel(f.areas))
            assertEquals(49, f.attacker.coords.wildernessLevel(f.areas), "rotation=$rotation")
            assertTrue(f.permits(), "rotation=$rotation")
        }
    }

    @Test
    fun `normal dungeon and source plane formulas survive a copy on another physical plane`() {
        val cases = listOf(
            CoordGrid(3010, 10120) to 24,
            CoordGrid(3330, 10050) to 17,
            CoordGrid(1750, 11540) to 21,
            CoordGrid(1620, 11540) to 29,
            CoordGrid(3001, 3906, 1) to 1,
        )
        for ((normal, expected) in cases) {
            val f = Fixture()
            assertEquals(normal, f.areas.normalizedCoords(normal))
            assertEquals(expected, normal.wildernessLevel(f.areas))
            val copied = f.copy(f.region(), normal, rotation = 3, destinationLevel = 2)
            assertEquals(normal, f.areas.normalizedCoords(copied))
            assertEquals(expected, copied.wildernessLevel(f.areas), "source=$normal")
        }
    }

    @Test
    fun `matching source coordinates in distinct allocated copies do not grant combat`() {
        val f = Fixture()
        f.attacker.coords = f.copy(f.region(), WILDERNESS)
        f.target.coords = f.copy(f.region(), WILDERNESS)
        assertEquals(f.areas.normalizedCoords(f.attacker.coords), f.areas.normalizedCoords(f.target.coords))
        assertTrue(f.areas.inArea("area.wilderness", f.attacker.coords))
        assertTrue(f.areas.inArea("area.wilderness", f.target.coords))
        assertFalse(f.permits())
        assertFalse(f.permission.permits(f.target, f.attacker))
    }

    @Test
    fun `a copied Wilderness and its original source never share permission`() {
        val f = Fixture()
        f.attacker.coords = f.copy(f.region(), WILDERNESS)
        f.target.coords = WILDERNESS
        assertFalse(f.permits())
        assertFalse(f.permission.permits(f.target, f.attacker))
    }

    @Test
    fun `unmapped zones and stale copied map entries without a live allocation are denied`() {
        val f = Fixture()
        val region = f.region()
        val copied = f.copy(region, WILDERNESS)
        f.attacker.coords = copied
        f.target.coords = copied.translate(1, 0)
        assertTrue(f.permits())

        val unmapped = region.southWest.translate(8, 0)
        assertEquals(CoordGrid.NULL, f.areas.normalizedCoords(unmapped))
        assertEquals(-1, unmapped.wildernessLevel(f.areas))
        f.target.coords = unmapped
        assertFalse(f.permits())

        f.target.coords = copied.translate(1, 0)
        // Deliberately retain the copied-zone map while invalidating the live allocation.
        f.smallRegions.remove(region.slot)
        assertTrue(f.areas.inArea("area.wilderness", f.attacker.coords))
        assertTrue(f.areas.inArea("area.wilderness", f.target.coords))
        assertFalse(f.permits())
    }

    @Test
    fun `copied safe and Ferox tiles remain excluded even within one allocated region`() {
        for (normal in listOf(SAFE, CoordGrid(3130, 3630))) {
            val f = Fixture()
            f.attacker.coords = f.copy(f.region(), normal, rotation = 2)
            f.target.coords = f.attacker.coords.translate(1, 0)
            assertEquals(normal, f.areas.normalizedCoords(f.attacker.coords))
            assertFalse(f.permits())
            assertEquals(-1, f.attacker.coords.wildernessLevel(f.areas))
        }
    }

    @Test
    fun `Ferox reachable upper floors shoulder and bridged basement reject both attack directions`() {
        val safeTiles = listOf(
            CoordGrid(3132, 3621, 1), CoordGrid(3137, 3621, 1),
            CoordGrid(3140, 3637, 1), CoordGrid(3141, 3637, 2),
            CoordGrid(3134, 3641, 1), CoordGrid(3137, 3644, 1),
            CoordGrid(3150, 10015), CoordGrid(3156, 10024),
        )
        for (safe in safeTiles) {
            val f = Fixture()
            f.attacker.coords = CoordGrid(3183, 3609, safe.level)
            f.target.coords = safe
            f.attacker.setCanPvp(true)
            f.target.setCanPvp(true)
            VarPlayerIntMapSetter.set(f.attacker, "varbit.teleblock_cycles", 1)
            VarPlayerIntMapSetter.set(f.target, "varbit.teleblock_cycles", 1)
            val expectedArea = if (safe.level == 0) "area.ferox_safe_zone" else "area.ferox_enclave"
            assertTrue(f.areas.inArea(expectedArea, safe), "Native reachable tile=$safe")
            assertEquals(-1, safe.wildernessLevel(f.areas))
            assertFalse(f.permits(), "Cannot attack into safe tile=$safe")
            assertFalse(f.permission.permits(f.target, f.attacker), "Cannot attack out of safe tile=$safe")
            f.attacker.coords = safe
            assertFalse(f.permits(), "No local safe-area combat at $safe")
        }
    }

    @Test
    fun `Ferox extension preserves adjacent Wilderness tower and basement boundary permissions`() {
        val f = Fixture()
        for (wild in listOf(CoordGrid(3183, 3609, 1), CoordGrid(3183, 3609, 2), CoordGrid(3200, 10040), CoordGrid(3122, 3628))) {
            f.attacker.coords = wild
            f.target.coords = wild
            assertFalse(f.areas.inArea("area.ferox_safe_zone", wild), "Safe volume must not swallow adjacent Wilderness=$wild")
            assertTrue(f.permits(), "Distinct players on the same Wilderness tile remain eligible: $wild")
        }
        f.attacker.coords = CoordGrid(3200, 10040)
        f.target.coords = CoordGrid(3199, 10040)
        assertFalse(f.permits(), "Inclusive basement edge must reject an attack crossing the boundary")
        assertFalse(f.permission.permits(f.target, f.attacker))
        f.target.coords = f.attacker.coords
        assertTrue(f.permits(), "The first tile outside the basement extension remains Wilderness")

        // The new safety volume deliberately does not rewrite the existing ground apron model.
        assertFalse(f.areas.inArea("area.ferox_safe_zone", CoordGrid(3134, 3641)))
        f.attacker.coords = CoordGrid(3134, 3641)
        f.target.coords = f.attacker.coords
        assertTrue(f.permits())
    }

    @Test
    fun `all copied rotations retain Ferox source safety without masking the separate tower`() {
        val cases = listOf(
            CoordGrid(3132, 3621, 1) to false,
            CoordGrid(3141, 3637, 2) to false,
            CoordGrid(3134, 3641, 1) to false,
            CoordGrid(3137, 3644, 1) to false,
            CoordGrid(3150, 10015) to false,
            CoordGrid(3156, 10024) to false,
            CoordGrid(3183, 3609, 1) to true,
            CoordGrid(3183, 3609, 2) to true,
        )
        for (rotation in 0..3) {
            for ((normal, allowed) in cases) {
                val f = Fixture()
                f.attacker.coords = f.copy(f.region(), normal, rotation, destinationLevel = (normal.level + rotation + 1) % 4)
                f.target.coords = f.attacker.coords
                assertEquals(normal, f.areas.normalizedCoords(f.attacker.coords))
                assertEquals(allowed, f.permits(), "Source=$normal rotation=$rotation physical=${f.attacker.coords}")
                assertEquals(normal.wildernessLevel(f.areas), f.attacker.coords.wildernessLevel(f.areas))
            }
        }
    }

    private class Fixture {
        val attacker = player(WILDERNESS)
        val target = player(WILDERNESS.translate(1, 0))
        val smallRegions = RegionListSmall()
        private val collision = CollisionFlagMap()
        private val locs = LocZoneStorage()
        val regions = RegionRegistry(
            smallRegions, RegionListLarge(), RegionListWorldEntity(),
            LocRegistryNormal(ZoneUpdateMap(), collision, locs), collision, locs,
            mockk(), mockk(), ZonePlayerActivityBitSet(),
        )
        val areas = AreaChecker(regions, areaIndex)
        val permission = WildernessPvPPermission(areas, regions)

        fun permits(): Boolean = permission.permits(attacker, target)

        fun region(): Region = (regions.registerSmall() as RegionRegistryResult.Add.Success).region

        fun copy(region: Region, normal: CoordGrid, rotation: Int = 0, destinationLevel: Int = 0): CoordGrid {
            val destination = region.southWest.copy(level = destinationLevel)
            regions.registerZone(region, ZoneKey.from(destination), RegionZoneCopy(ZoneKey.from(normal), rotation, null))
            val offset = RegionRotations.translateCoords(rotation, ZoneGrid.from(normal))
            return destination.translate(offset)
        }
    }

    companion object {
        private val WILDERNESS = CoordGrid(3201, 3554)
        private val SAFE = CoordGrid(3222, 3218)
        private lateinit var areaIndex: AreaIndex

        @JvmStatic
        @BeforeAll
        fun cache() {
            val cache = ServerCacheManager.init(241)
            try {
                areaIndex = AreaIndex()
                for (region in cache.archives(MAPS)) {
                    if (region > 25286 || 7 !in cache.files(MAPS, region)) continue
                    if (cache.data(MAPS, region, 0) == null || cache.data(MAPS, region, 1) == null) continue
                    val data = checkNotNull(cache.data(MAPS, region, 7)) {
                        "Declared area metadata is missing for map square $region"
                    }
                    MapAreaDefinitions.putAreas(areaIndex, MapSquareKey(region), MapAreaDecoder.decode(InlineByteBuf(data)))
                }
            } finally {
                cache.close()
            }
        }

        private fun player(coords: CoordGrid): Player = Player().apply {
            worldType = WorldType.MAIN
            this.coords = coords
        }
    }
}
