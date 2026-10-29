package org.rsmod.content.skills.agility

import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.map.GameMapBuilder
import dev.openrune.map.GameMapDecoder
import dev.openrune.map.loc.MapLocListDecoder
import dev.openrune.map.tile.MapTileDecoder
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.rscm.RSCM.asRSCM
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.game.process.player.PlayerMovementProcessor
import org.rsmod.api.game.process.player.PlayerRunUpdateProcessor
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.route.RouteFactory
import org.rsmod.api.route.StepFactory
import org.rsmod.api.table.agility.AgilityCoursesRow
import org.rsmod.api.table.agility.AgilityMarkSpawnsRow
import org.rsmod.api.table.agility.AgilityObstaclesRow
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.client.Client
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocZoneKey
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.RouteFinding
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class AgilityCourseTest {
    @Test
    fun `every obstacle has a native usable entrance and an unblocked landing tile`() {
        for (obstacle in OBSTACLES) {
            val loc = NATIVE_LOCS.single {
                it.id == obstacle.data.loc.id && it.coords == obstacle.data.locCoord
            }
            for (offset in 0..obstacle.data.entrySpan) {
                val entry = obstacle.data.entry.translate(offset, 0)
                val exit = obstacle.exitFrom(entry)
                for (tile in listOf(entry, exit)) {
                    assertEquals(0, NATIVE_COLLISION[tile.x, tile.z, tile.level] and FLOOR_BLOCKERS,
                        "${obstacle.data.rowId}: blocked $tile")
                }
                val route = RouteFinding(NATIVE_COLLISION).findRoute(
                    level = entry.level, srcX = entry.x, srcZ = entry.z,
                    destX = loc.x, destZ = loc.z, destWidth = loc.width, destLength = loc.length,
                    locShape = loc.shapeId, locAngle = loc.angleId,
                    blockAccessFlags = loc.forceApproachFlags, moveNear = false,
                )
                assertTrue(route.success, "Native op must be reachable from $entry: $loc")
            }
        }
    }

    @Test
    fun `each mark spawn is walkable from a completed obstacle on its native rooftop`() {
        for (spawn in AgilityMarkSpawnsRow.all()) {
            val tile = spawn.coord
            assertEquals(0, NATIVE_COLLISION[tile.x, tile.z, tile.level] and FLOOR_BLOCKERS)
            val arrivals = OBSTACLES.filter {
                it.course.rowId == spawn.course.rowId && it.data.exit.level == tile.level
            }
            assertTrue(arrivals.any {
                RouteFinding(NATIVE_COLLISION).findRoute(
                    level = tile.level, srcX = it.data.exit.x, srcZ = it.data.exit.z,
                    destX = tile.x, destZ = tile.z, moveNear = false,
                ).success
            }, "Unreachable personal mark spawn: $tile")
        }
    }

    @Test
    fun `a level one player can walk and traverse complete native Gnome and Draynor laps`() {
        for ((course, expectedXp) in listOf("gnome" to 1105, "draynor" to 1200)) {
            val obstacles = course(course)
            val f = Fixture(obstacles.first().data.entry)
            for (obstacle in obstacles) {
                f.walkTo(obstacle.data.entry)
                f.start(obstacle)
                f.finish()
                assertEquals(obstacle.data.exit, f.player.coords)
                assertTrue(f.result!!.getOrThrow())
                assertEquals(0, f.player.agilityRecoveryStart)
            }
            assertEquals(expectedXp, f.xp())
            assertEquals(0, f.player.agilityLapStep)
            assertEquals(0, f.player.agilityLapCourse)
            assertEquals(1, f.laps)
            assertFalse(f.player.forcedRoute)
        }
    }

    @Test
    fun `alternate nets and both pipes preserve the approach lane and finish at the safe exit`() {
        for (obstacle in OBSTACLES.filter { it.data.entrySpan > 0 || it.kind == AgilityTraversal.CRAWL }) {
            for (offset in 0..obstacle.data.entrySpan) {
                val entry = obstacle.data.entry.translate(offset, 0)
                val f = Fixture(entry)
                f.start(obstacle)
                f.finish()
                assertTrue(f.result!!.getOrThrow())
                assertEquals(obstacle.exitFrom(entry), f.player.coords)
                assertEquals(obstacle.data.xpTenths, f.xp())
            }
        }
    }

    @Test
    fun `the final obstacle pays its lap bonus once and only after every preceding step`() {
        for (name in listOf("gnome", "draynor")) {
            val obstacles = course(name)
            val last = obstacles.last()
            val f = Fixture(last.data.entry)
            f.start(last)
            f.finish()
            assertEquals(last.data.xpTenths, f.xp())
            for (obstacle in obstacles.dropLast(1)) f.player.finishAgilityObstacle(obstacle)
            f.player.coords = last.data.entry
            f.start(last)
            f.finish()
            val completedXp = last.data.xpTenths * 2 + last.course.bonusTenths
            assertEquals(completedXp, f.xp())
            repeat(2) {
                f.player.coords = last.data.entry
                f.start(last)
                f.finish()
            }
            assertEquals(completedXp + last.data.xpTenths * 2, f.xp())
            assertEquals(1, f.laps)
        }
    }

    @Test
    fun `skipping and repeating a middle obstacle cannot manufacture lap credit`() {
        val obstacles = course("gnome")
        for (sequence in listOf(listOf(0, 2, 3, 4, 5, 6), listOf(0, 1, 1, 2, 3, 4, 5, 6))) {
            val f = Fixture(obstacles.first().data.entry)
            for (index in sequence) assertFalse(f.player.finishAgilityObstacle(obstacles[index]))
            assertEquals(0, f.player.agilityLapStep)
        }
    }

    @Test
    fun `switching courses discards the old lap and restarting builds a fresh sequence`() {
        val gnome = course("gnome")
        val draynor = course("draynor")
        val f = Fixture(gnome.first().data.entry)
        gnome.take(6).forEach { assertFalse(f.player.finishAgilityObstacle(it)) }
        assertFalse(f.player.finishAgilityObstacle(draynor.first()))
        assertFalse(f.player.finishAgilityObstacle(gnome.last()))
        assertEquals(0, f.player.agilityLapStep)
        gnome.dropLast(1).forEach { assertFalse(f.player.finishAgilityObstacle(it)) }
        assertTrue(f.player.finishAgilityObstacle(gnome.last()))
    }

    @Test
    fun `forced move completion waits until the full crossing has elapsed before awarding XP`() {
        val log = obstacle("gnome_log")
        val f = Fixture(log.data.entry)
        f.start(log)
        assertEquals(log.data.exit, f.player.coords)
        assertNotNull(f.player.pendingExactMove)
        assertTrue(f.player.isDelayed)
        f.advance(log.totalTicks(log.data.entry) - 1)
        assertEquals(0, f.xp())
        assertEquals(0, f.player.agilityLapStep)
        f.advance(1)
        assertEquals(100, f.xp())
        assertEquals(1, f.player.agilityLapStep)
    }

    @Test
    fun `cancelling a real traversal restores its entry and invalidates unfinished lap credit`() {
        val gnome = course("gnome")
        val rope = obstacle("gnome_rope")
        val f = Fixture(rope.data.entry)
        gnome.take(3).forEach { f.player.finishAgilityObstacle(it) }
        f.start(rope)
        f.advance(1)
        f.player.cancelActiveCoroutine()
        f.advance(rope.totalTicks(rope.data.entry) + 2)
        assertEquals(rope.data.entry, f.player.coords)
        assertEquals(0, f.xp())
        assertEquals(0, f.player.agilityLapStep)
        assertEquals(0, f.player.agilityRecoveryStart)
        assertNull(f.player.pendingExactMove)
    }

    @Test
    fun `real obstacle crossings pause recovery until completion or cancellation`() {
        val log = obstacle("gnome_log")
        val processor = PlayerRunUpdateProcessor(setOf(AgilityRunRecoveryHook()))
        for (cancel in listOf(false, true)) {
            val f = Fixture(log.data.entry)
            f.player.runEnergy = 5000
            f.start(log)
            assertTrue(f.player.agilityRecoveryStart != 0)
            processor.process(f.player)
            assertEquals(5000, f.player.runEnergy)
            f.advance(1)
            processor.process(f.player)
            assertEquals(5000, f.player.runEnergy)
            if (cancel) f.player.cancelActiveCoroutine()
            f.advance(log.totalTicks(log.data.entry) + 2)
            assertEquals(0, f.player.agilityRecoveryStart)
            processor.process(f.player)
            assertEquals(5015, f.player.runEnergy)
        }
    }

    @Test
    fun `interrupted climbing cannot change floors or award experience`() {
        val wall = obstacle("draynor_wall_up")
        val f = Fixture(wall.data.entry)
        f.start(wall)
        f.advance(1)
        f.player.cancelActiveCoroutine()
        f.advance(wall.totalTicks(wall.data.entry) + 2)
        assertEquals(wall.data.entry, f.player.coords)
        assertEquals(0, f.xp())
    }

    @Test
    fun `logout recovery restores an unfinished crossing and clears completed session steps`() {
        val rope = obstacle("gnome_rope")
        val f = Fixture(rope.data.exit)
        f.player.agilityLapCourse = rope.course.key
        f.player.agilityLapStep = rope.data.step - 1
        f.player.agilityRecoveryStart = rope.data.entry.packed + 1
        f.player.agilityRecoveryEnd = rope.data.exit.packed + 1
        f.player.recoverAgility(f.collision)
        assertEquals(rope.data.entry, f.player.coords)
        assertEquals(0, f.player.agilityLapStep)
        assertEquals(0, f.player.agilityRecoveryStart)
        f.player.recoverAgility(f.collision)
        assertEquals(rope.data.entry, f.player.coords)
        assertEquals(0, f.xp())
    }

    @Test
    fun `prelogin recovery restores saved position without altering shared collision`() {
        val rope = obstacle("gnome_rope")
        val f = Fixture(rope.data.exit)
        f.player.slotId = PathingEntity.INVALID_SLOT
        val entry = rope.data.entry
        val exit = rope.data.exit
        f.collision.add(exit.x, exit.z, exit.level, CollisionFlag.BLOCK_PLAYERS)
        val entryFlags = f.collision[entry.x, entry.z, entry.level]
        val exitFlags = f.collision[exit.x, exit.z, exit.level]
        f.player.agilityRecoveryStart = entry.packed + 1
        f.player.agilityRecoveryEnd = exit.packed + 1
        f.player.recoverAgility(f.collision)
        assertEquals(entry, f.player.coords)
        assertEquals(entryFlags, f.collision[entry.x, entry.z, entry.level])
        assertEquals(exitFlags, f.collision[exit.x, exit.z, exit.level])
    }

    @Test
    fun `an external displacement aborts the obstacle without pulling the player back`() {
        val log = obstacle("gnome_log")
        val f = Fixture(log.data.entry)
        f.start(log)
        val elsewhere = CoordGrid(2480, 3427)
        f.player.coords = elsewhere
        f.finish()
        assertFalse(f.result!!.getOrThrow())
        assertEquals(elsewhere, f.player.coords)
        assertEquals(0, f.xp())
        assertEquals(0, f.player.agilityRecoveryStart)
    }

    @Test
    fun `wrong floor and wrong side do not move the player or award XP`() {
        val log = obstacle("gnome_log")
        for (position in listOf(log.data.exit, log.data.entry.copy(level = 1))) {
            val f = Fixture(position)
            f.start(log)
            f.finish()
            assertFalse(f.result!!.getOrThrow())
            assertEquals(position, f.player.coords)
            assertEquals(0, f.xp())
        }
    }

    @Test
    fun `obstructed approach does not force a player through collision or pay XP`() {
        val wall = obstacle("draynor_wall_up")
        val position = wall.data.entry.translate(1, 0)
        val f = Fixture(position)
        f.collision.add(wall.data.entry.x, wall.data.entry.z, 0, CollisionFlag.BLOCK_WALK)
        f.start(wall)
        f.finish()
        assertFalse(f.result!!.getOrThrow())
        assertEquals(position, f.player.coords)
        assertEquals(0, f.xp())
    }

    @OptIn(InternalApi::class)
    private class Fixture(start: CoordGrid) {
        val collision = CollisionFlagMap().apply { copyFrom(NATIVE_COLLISION) }
        private val events = EventBus()
        private val movement = PlayerMovementProcessor(
            collision, RouteFactory(collision), StepFactory(collision), events,
        )
        val player = Player().apply {
            client = SilentClient()
            uuid = 917L
            slotId = 1
            assignUid()
            coords = start
            currentMapClock = 100
            processedMapClock = 100
            inv = inventory("inv.inv", 28)
            worn = inventory("inv.worn", 14)
            statMap.setBaseLevel("stat.agility", 99)
            statMap.setCurrentLevel("stat.agility", 1)
            xpRate = 1.0
            globalXpRate = 1.0
        }
        private var coroutine = GameCoroutine()
        var result: Result<Boolean>? = null
        var laps = 0

        fun start(obstacle: AgilityObstacle) {
            player.lastMovement = 0
            result = null
            coroutine = GameCoroutine()
            player.activeCoroutine = coroutine
            val access = ProtectedAccess(player, coroutine,
                ProtectedAccessContextFactory.empty().copy(
                    getEventBus = { events }, getCollision = { collision },
                ))
            val block: suspend () -> Boolean = {
                access.crossAgilityObstacle(obstacle, collision) { _, _ -> laps++ }
            }
            block.startCoroutine(object : Continuation<Boolean> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Boolean>) { this@Fixture.result = result }
            })
            result?.getOrThrow()
        }

        fun advance(ticks: Int) {
            repeat(ticks) {
                player.previousCoords = player.coords
                player.currentMapClock++
                player.processedMapClock = player.currentMapClock
                movement.process(player)
                coroutine.advance()
                result?.exceptionOrNull()?.let { if (it !is CancellationException) throw it }
            }
        }

        fun finish() {
            repeat(80) { if (result == null) advance(1) }
            assertNotNull(result, "Obstacle did not finish")
        }

        fun walkTo(destination: CoordGrid) {
            assertEquals(destination.level, player.level)
            val route = RouteFinding(collision).findRoute(
                level = player.level, srcX = player.x, srcZ = player.z,
                destX = destination.x, destZ = destination.z, moveNear = false,
            )
            assertTrue(route.success, "Cannot reach next obstacle from ${player.coords} to $destination")
            player.routeDestination.addAll(route.map { CoordGrid(it.x, it.z, destination.level) })
            player.moveSpeed = MoveSpeed.Walk
            repeat(80) { if (player.coords != destination) advance(1) }
            assertEquals(destination, player.coords)
        }

        fun xp(): Int = player.statMap.getFineXP("stat.agility")

        private fun inventory(name: String, size: Int) = Inventory(
            checkNotNull(ServerCacheManager.getInventory(name.asRSCM())), arrayOfNulls(size),
        )
    }

    private class SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private const val FLOOR_BLOCKERS =
            CollisionFlag.BLOCK_WALK or CollisionFlag.LOC or CollisionFlag.GROUND_DECOR
        private lateinit var NATIVE_COLLISION: CollisionFlagMap
        private lateinit var NATIVE_LOCS: List<BoundLocInfo>
        private lateinit var OBSTACLES: List<AgilityObstacle>

        private fun obstacle(key: String) = OBSTACLES.single {
            it.data.rowId == "dbrow.agility_$key".asRSCM()
        }

        private fun course(key: String): List<AgilityObstacle> {
            val course = AgilityCoursesRow.getRow("dbrow.agility_$key")
            return (1..course.steps).map { step ->
                OBSTACLES.first { it.course.key == course.key && it.data.step == step }
            }
        }

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            val cache = ServerCacheManager.init(241)
            try {
                NATIVE_COLLISION = CollisionFlagMap()
                val builder = GameMapBuilder()
                for ((mx, mz) in listOf(38 to 53, 38 to 54, 48 to 50, 48 to 51)) {
                    val group = (mx shl 8) or mz
                    val tiles = MapTileDecoder.decode(InlineByteBuf(checkNotNull(cache.data(MAPS, group, 0))))
                    val locs = MapLocListDecoder.decode(InlineByteBuf(checkNotNull(cache.data(MAPS, group, 1))))
                    for (level in 0..3) for (x in 0 until 64 step 8) for (z in 0 until 64 step 8) {
                        NATIVE_COLLISION.allocateIfAbsent(mx * 64 + x, mz * 64 + z, level)
                    }
                    val square = MapSquareKey(mx, mz)
                    GameMapDecoder.putMaps(NATIVE_COLLISION, square, tiles)
                    GameMapDecoder.putLocs(builder, NATIVE_COLLISION, square, tiles, locs)
                }
                NATIVE_LOCS = buildList {
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
                val courses = AgilityCoursesRow.all().associateBy { it.key }
                OBSTACLES = AgilityObstaclesRow.all().map {
                    AgilityObstacle(it, checkNotNull(courses[it.course]))
                }
            } finally {
                cache.close()
            }
        }
    }
}
