package org.rsmod.content.quest.area.draynor.vampyreslayer

import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.map.GameMapBuilder
import dev.openrune.map.GameMapDecoder
import dev.openrune.map.loc.MapLocListDecoder
import dev.openrune.map.tile.MapTileDecoder
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.rscm.RSCM.asRSCM
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocZoneKey
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.RouteFinding
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

@ResourceLock("ServerCacheManager")
class VampyreSlayerNativeMapTest {
    @Test fun `Count emerges on a walkable cellar tile beside the actual native coffin`() {
        val cache = ServerCacheManager.init(241)
        try {
            val square = MapSquareKey(48, 152)
            val group = (48 shl 8) or 152
            val tiles = MapTileDecoder.decode(InlineByteBuf(checkNotNull(cache.data(MAPS, group, 0))))
            val spawns = MapLocListDecoder.decode(InlineByteBuf(checkNotNull(cache.data(MAPS, group, 1))))
            val collision = CollisionFlagMap()
            for (level in 0..3) for (x in 3072..3135 step 8) for (z in 9728..9791 step 8) {
                collision.allocateIfAbsent(x, z, level)
            }
            val builder = GameMapBuilder()
            GameMapDecoder.putMaps(collision, square, tiles)
            GameMapDecoder.putLocs(builder, collision, square, tiles, spawns)
            val locs = buildList {
                for ((packed, zone) in builder.zoneBuilders) {
                    val base = ZoneKey(packed).toCoords()
                    for (entry in zone.build().byte2IntEntrySet()) {
                        val key = LocZoneKey(entry.byteKey)
                        val entity = LocEntity(entry.intValue)
                        add(BoundLocInfo(LocInfo(key.layer, base.translate(key.x, key.z), entity),
                            checkNotNull(ServerCacheManager.getObject(entity.id))))
                    }
                }
            }
            val coffin = locs.single { it.id == "loc.vampcoffin".asRSCM() }
            assertEquals(CoordGrid(3077, 9775), coffin.coords)
            assertEquals(10, coffin.shapeId)
            assertEquals(3, coffin.angleId)
            val emergence = coffin.countEmergenceTile()
            assertEquals(CoordGrid(3077, 9773), emergence)
            assertEquals(0, collision[emergence.x, emergence.z, emergence.level] and
                (CollisionFlag.BLOCK_WALK or CollisionFlag.LOC or CollisionFlag.GROUND_DECOR))
            val route = RouteFinding(collision).findRoute(level = 0,
                srcX = emergence.x, srcZ = emergence.z, destX = coffin.x, destZ = coffin.z,
                destWidth = coffin.width, destLength = coffin.length, locShape = coffin.shapeId,
                locAngle = coffin.angleId, blockAccessFlags = coffin.forceApproachFlags, moveNear = false)
            assertTrue(route.success, "The Count's emergence tile must connect to the coffin approach")
            assertEquals(3, checkNotNull(ServerCacheManager.getAnim("seq.vampireslayer_coffin_open".asRSCM())).tickDuration)
        } finally {
            cache.close()
        }
    }
}
