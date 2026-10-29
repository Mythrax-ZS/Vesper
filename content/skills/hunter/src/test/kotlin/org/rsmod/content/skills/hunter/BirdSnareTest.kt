package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.types.NpcMode
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.player.interact.PlayerDropTimers
import org.rsmod.api.player.ironman.PlayerGamemode
import org.rsmod.content.other.ironman.IronmanObjTakeHook
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.player.WorldTypeChangedEvent
import org.rsmod.game.interact.InteractionNpcOp
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.obj.Obj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.flag.CollisionFlag

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class BirdSnareTest {
    @Test
    fun `ground lay consumes one snare and preserves source identity ownership timers and full inventory`() {
        val f = SnareWorld()
        val source = f.groundSnare(Int.MAX_VALUE)
        val timers = listOf(source.creationCycle, source.timerCycle, source.revealCycle, source.despawnCycle)
        val owner = source.ownerId
        f.fill()
        val inventory = f.player.inv.objs.toList()
        with(f.script) { f.start { layGroundSnare(source) } }
        assertEquals(Int.MAX_VALUE - 1, source.count)
        assertSame(source, f.objRegistry.findAll(f.tile).single())
        assertEquals(timers, listOf(source.creationCycle, source.timerCycle, source.revealCycle, source.despawnCycle))
        assertEquals(owner, source.ownerId)
        assertEquals(WorldType.MAIN, source.worldType)
        assertEquals(inventory, f.player.inv.objs.toList())
        assertEquals(1, f.manager.count(f.player))
        verify(exactly = 1) { f.updates.objCount(source, Int.MAX_VALUE - 1, Int.MAX_VALUE) }
        with(f.script) { f.start { layGroundSnare(source) } }
        assertEquals(Int.MAX_VALUE - 1, source.count)
        assertEquals(1, f.manager.count(f.player))
    }

    @Test
    fun `ground lay world insertion or source consumption failure leaves source and inventory unchanged`() {
        for (sourceFailure in listOf(false, true)) {
            val f = SnareWorld()
            val source = f.groundSnare(2)
            if (sourceFailure) every { f.objs.take(source, 1) } returns false
            else every { f.locs.add(any(), any(), any()) } returns false
            with(f.script) { f.start { layGroundSnare(source) } }
            assertSame(source, f.objRegistry.findAll(f.tile).single())
            assertEquals(2, source.count)
            assertNull(f.manager.trapAt(f.tile))
            assertNull(f.locs.findExact(f.tile, BirdSnares.loc(BirdSnares.OPEN)))
            assertEquals(0, f.count(BirdSnares.SNARE))
        }
    }

    @Test
    fun `failed ground take and failed rollback retain source and retry only the uncommitted location`() {
        for (replaced in listOf(false, true)) {
            val f = SnareWorld()
            val source = f.groundSnare(2)
            every { f.objs.take(source, 1) } returns false
            every { f.locs.del(any<LocInfo>(), Int.MAX_VALUE) } returns false
            with(f.script) { f.start { layGroundSnare(source) } }
            val orphan = checkNotNull(f.locs.findExact(f.tile, BirdSnares.loc(BirdSnares.OPEN)))
            assertNull(f.manager.trapAt(f.tile))
            assertEquals(SnareFailure.STALE, f.manager.interact(f.player, orphan, SnareAction.DISMANTLE))
            assertEquals(SnareFailure.TILE, f.manager.canLayGround(f.player, source))
            f.tick()
            assertEquals(orphan, f.locs.findExact(f.tile, orphan.shape))
            assertSame(source, f.objRegistry.findAll(f.tile).single())
            assertEquals(2, source.count)
            val replacement = orphan.copy(entity = LocEntity(BirdSnares.loc(BirdSnares.BROKEN).id, orphan.shapeId, orphan.angleId))
            if (replaced) assertTrue(f.locs.add(replacement, Int.MAX_VALUE))
            every { f.locs.del(any<LocInfo>(), Int.MAX_VALUE) } answers { callOriginal() }
            f.tick()
            assertEquals(if (replaced) replacement else null, f.locs.findExact(f.tile, orphan.shape))
            assertNull(f.manager.trapAt(f.tile))
            assertSame(source, f.objRegistry.findAll(f.tile).single())
            assertEquals(2, source.count)
            assertEquals(0, f.count(BirdSnares.SNARE))
            assertEquals(0, f.xp())
            if (replaced) verify(exactly = 0) { f.locs.del(replacement, Int.MAX_VALUE) }
        }
    }

    @Test
    fun `registered ground count removal rejects stale invalid and excessive requests without changing updates`() {
        val f = SnareWorld()
        val source = f.groundSnare(3)
        val stale = Obj.fromOwner(f.player, f.tile, BirdSnares.SNARE, 3)
        f.updates.clear()
        for (count in listOf(0, -1, 4, Int.MAX_VALUE)) assertFalse(f.objs.take(source, count))
        assertFalse(f.objs.take(stale, 1))
        assertFalse(f.objs.take(stale, 3))
        assertEquals(3, source.count)
        assertEquals(0, f.updates.updatedZones.size)
        assertTrue(f.objs.take(source, 2))
        assertEquals(1, source.count)
        verify(exactly = 1) { f.updates.objCount(source, 1, 3) }
        assertTrue(f.objs.take(source, 1))
        assertTrue(f.objRegistry.findAll(f.tile).none())
        assertFalse(f.objs.take(source, 1))
    }

    @Test
    fun `foreign private expired cross mode and replaced ground sources cannot become traps`() {
        for (reason in listOf("private", "expired", "mode", "replaced")) {
            val f = SnareWorld()
            val source = f.groundSnare(1, if (reason == "private") f.player(2) else f.player)
            when (reason) {
                "expired" -> source.setTimers(source.timerCycle, source.revealCycle, f.clock.cycle)
                "mode" -> f.player.worldType = WorldType.TWISTED_LEAGUE
                "replaced" -> { assertTrue(f.objs.del(source)); f.groundSnare() }
            }
            with(f.script) { f.start { layGroundSnare(source) } }
            assertNull(f.manager.trapAt(f.tile), reason)
            assertEquals(1, f.groundCount(), reason)
            assertEquals(0, f.count(BirdSnares.SNARE))
        }
    }

    @Test
    fun `ground lay respects current level trap limit and exact placement tile`() {
        val f = SnareWorld()
        val source = f.groundSnare()
        f.player.statMap.setCurrentLevel("stat.hunter", 0)
        assertEquals(SnareFailure.LEVEL, f.manager.layGround(f.player, source))
        f.player.statMap.setCurrentLevel("stat.hunter", 1)
        f.player.coords = f.tile.translate(-1, 0)
        assertEquals(SnareFailure.STALE, f.manager.layGround(f.player, source))
        f.lay(f.tile.translate(0, 1))
        f.lay(f.tile.translate(0, 2))
        f.player.coords = f.tile
        assertEquals(SnareFailure.LIMIT, f.manager.layGround(f.player, source))
        assertEquals(1, source.count)
        assertSame(source, f.objRegistry.findAll(f.tile).single())
    }

    @Test
    fun `ground lay runs real ironman take restrictions before consuming public foreign property`() {
        val f = SnareWorld(setOf(IronmanObjTakeHook()))
        f.player.gamemode = PlayerGamemode.IRONMAN
        val source = f.groundSnare(1, f.player(2))
        source.reveal()
        with(f.script) { f.start { layGroundSnare(source) } }
        assertNull(f.manager.trapAt(f.tile))
        assertSame(source, f.objRegistry.findAll(f.tile).single())
        assertEquals(1, source.count)
        assertTrue(f.objs.del(source))
        val own = f.groundSnare()
        with(f.script) { f.start { layGroundSnare(own) } }
        assertNotNull(f.manager.trapAt(f.tile))
        assertEquals(0, f.groundCount())
        assertEquals(0, f.count(BirdSnares.SNARE))
    }

    @Test
    fun `native ground approach walks onto the source before laying and never lays through collision`() {
        for (blocked in listOf(false, true)) {
            val f = SnareWorld()
            val source = f.groundSnare()
            f.player.coords = f.tile.translate(-3, 0)
            if (blocked) f.collision.add(f.tile.x, f.tile.z, f.tile.level, CollisionFlag.BLOCK_WALK)
            f.approachGround(source)
            f.processPlayerInteraction()
            assertNull(f.manager.trapAt(f.tile))
            assertEquals(1, source.count)
            repeat(5) { f.processPlayerInteraction() }
            if (blocked) {
                assertNull(f.manager.trapAt(f.tile))
                assertEquals(1, f.groundCount())
                assertNotEquals(f.tile, f.player.coords)
            } else {
                assertEquals(f.tile, f.player.coords)
                assertNotNull(f.manager.trapAt(f.tile))
                assertEquals(0, f.groundCount())
            }
        }
    }

    @Test
    fun `cancelled or stale ground approach cannot consume the source or a replacement stack`() {
        for (replace in listOf(false, true)) {
            val f = SnareWorld()
            val source = f.groundSnare()
            f.player.coords = f.tile.translate(-3, 0)
            f.approachGround(source)
            f.processPlayerInteraction()
            if (replace) { assertTrue(f.objs.del(source)); f.groundSnare() } else f.cancel()
            repeat(5) { f.processPlayerInteraction() }
            assertNull(f.manager.trapAt(f.tile))
            assertEquals(1, f.groundCount())
            assertEquals(0, f.count(BirdSnares.SNARE))
        }
    }

    @Test
    fun `laying consumes exactly one stack item and stale or repeated requests cannot create extra traps`() {
        val f = SnareWorld()
        f.player.inv[0] = InvObj(BirdSnares.SNARE, 2)
        val before = f.player.inv[0]!!
        assertNull(f.manager.lay(f.player, 0, before, f.tile))
        assertEquals(1, f.count(BirdSnares.SNARE))
        assertEquals(1, f.manager.count(f.player))
        assertNotNull(f.locs.findExact(f.tile, BirdSnares.loc(BirdSnares.OPEN)))
        assertNotNull(f.manager.lay(f.player, 0, before, f.tile))
        f.player.coords = f.tile.translate(0, 1)
        assertEquals(SnareFailure.INPUT, f.manager.lay(f.player, 0, before, f.player.coords))
        f.player.inv[0] = null
        assertEquals(SnareFailure.INPUT, f.manager.lay(f.player, 0, before, f.player.coords))
        assertEquals(1, f.manager.count(f.player))
    }

    @Test
    fun `failed world insertion and occupied or blocked tile never consume the snare`() {
        val f = SnareWorld()
        val before = InvObj(BirdSnares.SNARE)
        f.player.inv[0] = before
        every { f.locs.add(any(), any(), any()) } returns false
        assertEquals(SnareFailure.TILE, f.manager.lay(f.player, 0, before, f.tile))
        assertEquals(before, f.player.inv[0])
        assertEquals(0, f.manager.count(f.player))
        f.collision.add(f.tile.x, f.tile.z, f.tile.level, CollisionFlag.BLOCK_WALK)
        assertEquals(SnareFailure.TILE, f.manager.canLay(f.player, 0, before, f.tile))
    }

    @Test
    fun `visible Hunter boosts unlock trap limits without changing the base level`() {
        val f = SnareWorld()
        f.player.statMap.setBaseLevel("stat.hunter", 1)
        for ((level, count) in listOf(1 to 2, 39 to 2, 40 to 3, 59 to 3, 60 to 4, 79 to 4, 80 to 5)) {
            f.player.statMap.setCurrentLevel("stat.hunter", level.toByte())
            assertEquals(count, f.manager.limit(f.player))
        }
        f.player.statMap.setCurrentLevel("stat.hunter", 1)
        f.lay()
        f.lay(f.tile.translate(0, 1))
        f.player.coords = f.tile.translate(0, 2)
        f.player.inv[0] = InvObj(BirdSnares.SNARE)
        assertEquals(SnareFailure.LIMIT, f.manager.lay(f.player, 0, f.player.inv[0]!!, f.player.coords))
        assertEquals(1, f.count(BirdSnares.SNARE))
    }

    @Test
    fun `placement and walking away can occur in the same tick without restoring or losing a snare`() {
        val f = SnareWorld()
        f.player.inv[0] = InvObj(BirdSnares.SNARE)
        with(f.script) { f.start { laySnare(0) } }
        assertNotNull(f.manager.trapAt(f.tile))
        assertEquals(0, f.count(BirdSnares.SNARE))
        assertFalse(f.player.isDelayed)
        f.cancel()
        f.player.coords = f.tile.translate(-1, 0)
        assertNotNull(f.manager.trapAt(f.tile))
        assertEquals(0, f.count(BirdSnares.SNARE))
    }

    @Test
    fun `no NPC or a bird beyond range or on another plane never creates loot or XP`() {
        val f = SnareWorld()
        val trap = f.lay()
        f.spawn(at = f.tile.translate(3, 0))
        f.spawn(at = f.tile.copy(level = 1))
        f.tick(20)
        assertEquals(SnarePhase.OPEN, trap.phase)
        assertNull(trap.catch)
        assertEquals(0, f.random.rolls)
        assertEquals(0, f.xp())
        assertEquals(0, f.count(BirdSnares.MEAT))
    }

    @Test
    fun `standing on the trap and insufficient current Hunter level prevent attraction`() {
        val f = SnareWorld()
        val data = BirdSnares.BIRDS.maxBy { it.level }
        val trap = f.lay()
        f.spawn(data)
        f.player.coords = f.tile
        f.tick(6)
        assertEquals(SnarePhase.OPEN, trap.phase)
        f.player.coords = f.tile.translate(-1, 0)
        f.player.statMap.setCurrentLevel("stat.hunter", (data.level - 1).toByte())
        f.tick(6)
        assertEquals(SnarePhase.OPEN, trap.phase)
        f.player.statMap.setCurrentLevel("stat.hunter", data.level.toByte())
        f.until { trap.phase == SnarePhase.FULL }
        assertEquals(data, trap.catch)
    }

    @Test
    fun `real bird walks to a single reserved trap before any success roll and respawns on configured clock`() {
        val f = SnareWorld()
        val first = f.lay()
        val second = f.lay(f.tile.translate(0, 1))
        val bird = f.spawn()
        val origin = bird.coords
        f.tick(BirdSnares.SEARCH_INTERVAL)
        assertEquals(SnarePhase.APPROACHING, first.phase)
        assertEquals(SnarePhase.OPEN, second.phase)
        assertNotEquals(origin, bird.coords)
        assertEquals(1, bird.coords.chebyshevDistance(origin))
        assertEquals(0, f.random.rolls)
        f.until { first.phase == SnarePhase.FULL }
        assertEquals(f.tile, bird.coords)
        assertTrue(bird.hidden)
        assertEquals(1, f.random.rolls)
        assertEquals(SnarePhase.OPEN, second.phase)
        val respawn = bird.lifecycleRespawnCycle
        assertTrue(respawn > f.clock.cycle)
        f.tick(respawn - f.clock.cycle)
        assertFalse(bird.hidden)
        assertEquals(bird.spawnCoords, bird.coords)
    }

    @Test
    fun `removed or interrupted reserved birds release without fabricating a catch`() {
        val f = SnareWorld()
        val trap = f.lay()
        val bird = f.spawn()
        f.tick(BirdSnares.SEARCH_INTERVAL)
        assertEquals(SnarePhase.APPROACHING, trap.phase)
        f.npcs.del(bird, Int.MAX_VALUE)
        f.tick()
        assertEquals(SnarePhase.OPEN, trap.phase)
        assertNull(trap.bird)
        assertNull(trap.catch)
        assertEquals(0, f.random.rolls)
        f.spawn()
        f.until { trap.phase == SnarePhase.FULL }
    }

    @Test
    fun `exhausted and newly blocked approach routes release the bird without a timeout`() {
        for (blocked in listOf(false, true)) {
            val f = SnareWorld()
            val trap = f.lay()
            val bird = f.spawn()
            val originalMode = bird.mode
            f.tick(BirdSnares.SEARCH_INTERVAL)
            assertEquals(SnarePhase.APPROACHING, trap.phase)
            if (blocked) {
                f.collision.add(f.tile.x, f.tile.z, f.tile.level, CollisionFlag.BLOCK_WALK)
            } else {
                bird.abortRoute()
            }
            val position = bird.coords
            f.tick()
            assertEquals(SnarePhase.OPEN, trap.phase, "blocked=$blocked")
            assertNull(trap.bird)
            assertEquals(position, bird.coords)
            assertTrue(bird.routeDestination.isEmpty())
            assertEquals(originalMode, bird.mode)
            assertEquals(0, f.random.rolls)
        }
    }

    @Test
    fun `reservation cleanup does not overwrite an NPC that was removed and registered again`() {
        val f = SnareWorld()
        val trap = f.lay()
        val bird = f.spawn()
        f.tick(BirdSnares.SEARCH_INTERVAL)
        val oldUid = bird.uid
        f.npcs.del(bird, Int.MAX_VALUE)
        assertNull(trap.bird)
        assertEquals(SnarePhase.OPEN, trap.phase)
        f.npcs.add(bird, Int.MAX_VALUE)
        bird.mode = NpcMode.Patrol
        assertNotEquals(oldUid, bird.uid)
        f.tick()
        assertEquals(SnarePhase.OPEN, trap.phase)
        assertNull(trap.bird)
        assertEquals(NpcMode.Patrol, bird.mode)
        assertEquals(0, f.random.rolls)
    }

    @Test
    fun `respawn invalidates a landing reservation even when slot type and NPC reference remain unchanged`() {
        val f = SnareWorld()
        val trap = f.lay()
        val bird = f.spawn()
        f.until { trap.phase == SnarePhase.LANDING }
        val uid = bird.uid
        f.npcRegistry.respawn(bird)
        assertEquals(uid, bird.uid)
        assertNull(trap.bird)
        assertEquals(SnarePhase.OPEN, trap.phase)
        bird.mode = NpcMode.None
        bird.walk(f.tile.translate(5, 0))
        val route = bird.routeDestination.toList()
        f.manager.tick()
        assertEquals(route, bird.routeDestination.toList())
        assertEquals(NpcMode.None, bird.mode)
        assertFalse(bird.hidden)
        assertEquals(0, f.random.rolls)
        assertNull(trap.catch)
    }

    @Test
    fun `reservation cleanup preserves a newly acquired interaction and external movement mode`() {
        val f = SnareWorld()
        val trap = f.lay()
        val bird = f.spawn()
        f.tick(BirdSnares.SEARCH_INTERVAL)
        assertEquals(SnarePhase.APPROACHING, trap.phase)
        val target = f.spawn(at = f.tile.translate(10, 0))
        val interaction = InteractionNpcOp(InteractionOp.Op2, target, true, false)
        bird.interaction = interaction
        bird.mode = NpcMode.Patrol
        bird.walk(f.tile.translate(5, 0))
        val route = bird.routeRequest
        f.manager.tick()
        assertEquals(SnarePhase.OPEN, trap.phase)
        assertNull(trap.bird)
        assertSame(interaction, bird.interaction)
        assertSame(route, bird.routeRequest)
        assertEquals(NpcMode.Patrol, bird.mode)
        assertEquals(0, f.random.rolls)
    }

    @Test
    fun `landing cannot capture a bird claimed by an external interaction or mode`() {
        for (hasInteraction in listOf(false, true)) {
            val f = SnareWorld()
            val trap = f.lay()
            val bird = f.spawn()
            f.until { trap.phase == SnarePhase.LANDING }
            val target = f.spawn(at = f.tile.translate(10, 0))
            val interaction = if (hasInteraction) InteractionNpcOp(InteractionOp.Op2, target, true, false) else null
            bird.interaction = interaction
            bird.mode = NpcMode.Patrol
            f.tick(trap.transitionAt - f.clock.cycle)
            assertEquals(SnarePhase.OPEN, trap.phase)
            assertNull(trap.bird)
            assertSame(interaction, bird.interaction)
            assertEquals(NpcMode.Patrol, bird.mode)
            assertFalse(bird.hidden)
            assertEquals(0, f.random.rolls)
            assertNull(trap.catch)
        }
    }

    @Test
    fun `all five native bird catches award one fixed set of products and fractional base XP once`() {
        for (data in BirdSnares.BIRDS) {
            val f = SnareWorld()
            val trap = f.caught(data)
            val expected = trap.loc
            assertNull(f.manager.interact(f.player, expected, SnareAction.CHECK))
            assertEquals(1, f.count(BirdSnares.SNARE))
            assertEquals(1, f.count(BirdSnares.BONES))
            assertEquals(1, f.count(BirdSnares.MEAT))
            assertEquals(data.row.feathersMin, f.count(data.feather.internalName))
            assertEquals(data.row.xpTenths, f.xp())
            val contents = f.player.inv.objs.toList()
            assertEquals(SnareFailure.STALE, f.manager.interact(f.player, expected, SnareAction.CHECK))
            assertEquals(contents, f.player.inv.objs.toList())
            assertEquals(data.row.xpTenths, f.xp())
        }
    }

    @Test
    fun `failure leaves the bird alive and broken reset works with a full inventory and no item transaction`() {
        val f = SnareWorld()
        f.player.statMap.setCurrentLevel("stat.hunter", 1)
        f.random.successRoll = 0.999
        val trap = f.lay()
        val bird = f.spawn()
        f.until { trap.phase == SnarePhase.BROKEN }
        assertFalse(bird.hidden)
        assertNull(trap.catch)
        assertEquals(0, f.xp())
        val expected = trap.loc
        f.fill()
        val contents = f.player.inv.objs.toList()
        assertNull(f.manager.interact(f.player, expected, SnareAction.RESET))
        assertEquals(SnarePhase.OPEN, trap.phase)
        assertEquals(contents, f.player.inv.objs.toList())
        assertEquals(SnareFailure.STALE, f.manager.interact(f.player, expected, SnareAction.RESET))
        assertEquals(0, f.xp())
    }

    @Test
    fun `full reset claims loot once and retains exactly one placed snare`() {
        val f = SnareWorld()
        val trap = f.caught()
        val expected = trap.loc
        val xp = trap.catch!!.row.xpTenths
        assertNull(f.manager.interact(f.player, expected, SnareAction.RESET))
        assertEquals(0, f.count(BirdSnares.SNARE))
        assertEquals(1, f.manager.count(f.player))
        assertEquals(SnarePhase.OPEN, trap.phase)
        assertEquals(xp, f.xp())
        assertEquals(1, f.count(BirdSnares.MEAT))
        assertEquals(SnareFailure.STALE, f.manager.interact(f.player, expected, SnareAction.RESET))
        assertEquals(xp, f.xp())
    }

    @Test
    fun `failed full reset world replacement leaves every product inventory and XP untouched`() {
        val f = SnareWorld()
        val trap = f.caught()
        val caught = trap.catch
        val feathers = trap.feathers
        val expected = trap.loc
        val contents = f.player.inv.objs.toList()
        every { f.locs.add(any(), any(), any()) } returns false
        assertEquals(SnareFailure.STALE, f.manager.interact(f.player, expected, SnareAction.RESET))
        assertEquals(contents, f.player.inv.objs.toList())
        assertSame(caught, trap.catch)
        assertEquals(feathers, trap.feathers)
        assertEquals(expected, trap.loc)
        assertEquals(SnarePhase.FULL, trap.phase)
        assertEquals(0, f.xp())
        every { f.locs.add(any(), any(), any()) } answers { callOriginal() }
        assertNull(f.manager.interact(f.player, expected, SnareAction.RESET))
        assertEquals(caught!!.row.xpTenths, f.xp())
        assertEquals(1, f.count(BirdSnares.MEAT))
    }

    @Test
    fun `full inventory and feather overflow preserve full trap products and XP for retry`() {
        val f = SnareWorld()
        val trap = f.caught()
        val feather = trap.catch!!.feather
        val expected = trap.loc
        f.fill()
        val full = f.player.inv.objs.toList()
        for (action in listOf(SnareAction.CHECK, SnareAction.RESET)) {
            assertEquals(SnareFailure.SPACE, f.manager.interact(f.player, expected, action))
            assertEquals(full, f.player.inv.objs.toList())
            assertEquals(SnarePhase.FULL, trap.phase)
            assertEquals(0, f.xp())
        }
        f.player.inv.fillNulls()
        f.player.inv[0] = InvObj(feather, Int.MAX_VALUE)
        val overflow = f.player.inv.objs.toList()
        assertEquals(SnareFailure.SPACE, f.manager.interact(f.player, expected, SnareAction.CHECK))
        assertEquals(overflow, f.player.inv.objs.toList())
        assertEquals(0, f.xp())
        f.player.inv[0] = null
        assertNull(f.manager.interact(f.player, expected, SnareAction.CHECK))
        assertEquals(BirdSnares.BIRDS.first().row.feathersMin, f.count(feather.internalName))
        assertEquals(1, f.random.rolls, "Inventory retries must not reroll the bird")
    }

    @Test
    fun `snare stack overflow and failed loc deletion do not duplicate dismantled traps`() {
        val f = SnareWorld()
        val trap = f.lay()
        f.player.inv[0] = InvObj(BirdSnares.SNARE, Int.MAX_VALUE)
        assertEquals(SnareFailure.SPACE, f.manager.interact(f.player, trap.loc, SnareAction.DISMANTLE))
        assertEquals(Int.MAX_VALUE, f.count(BirdSnares.SNARE))
        f.player.inv[0] = null
        every { f.locs.del(trap.loc, Int.MAX_VALUE) } returns false
        assertEquals(SnareFailure.STALE, f.manager.interact(f.player, trap.loc, SnareAction.DISMANTLE))
        assertEquals(0, f.count(BirdSnares.SNARE))
        assertSame(trap, f.manager.trapAt(f.tile))
    }

    @Test
    fun `other players and stale world locs cannot collect a trap`() {
        val f = SnareWorld()
        val trap = f.caught()
        val other = f.player(2).apply { coords = f.player.coords }
        assertEquals(SnareFailure.OWNER, f.manager.interact(other, trap.loc, SnareAction.CHECK))
        assertEquals(0, other.inv.occupiedSpace())
        assertTrue(f.locs.del(trap.loc, Int.MAX_VALUE))
        assertEquals(SnareFailure.STALE, f.manager.interact(f.player, trap.loc, SnareAction.CHECK))
        f.tick()
        assertNull(f.manager.trapAt(f.tile))
        assertEquals(0, f.xp())
    }

    @Test
    fun `movement cancellation and inventory filling during check preserve the caught bird`() {
        val f = SnareWorld()
        val trap = f.caught()
        with(f.script) { f.start { useSnare(trap.loc, SnareAction.CHECK) } }
        f.cancel()
        f.tick(BirdSnares.animationTicks(BirdSnares.PICKUP_ANIM))
        assertEquals(SnarePhase.FULL, trap.phase)
        assertEquals(0, f.xp())
        with(f.script) { f.start { useSnare(trap.loc, SnareAction.CHECK) } }
        f.fill()
        f.tick(BirdSnares.animationTicks(BirdSnares.PICKUP_ANIM))
        assertEquals(SnarePhase.FULL, trap.phase)
        assertEquals(0, f.xp())
    }

    @Test
    fun `suspended check cannot collect a different full trap with identical type and tile`() {
        val f = SnareWorld()
        val first = f.caught()
        val expected = first.loc
        with(f.script) { f.start { useSnare(expected, SnareAction.CHECK) } }
        f.advanceActions = false
        assertNull(f.manager.interact(f.player, expected, SnareAction.CHECK))
        val second = f.caught()
        assertEquals(expected, second.loc)
        assertNotSame(first, second)
        val contents = f.player.inv.objs.toList()
        val xp = f.xp()
        f.advanceActions = true
        f.tick()
        assertSame(second, f.manager.trapAt(f.tile))
        assertEquals(SnarePhase.FULL, second.phase)
        assertEquals(contents, f.player.inv.objs.toList())
        assertEquals(xp, f.xp())
    }

    @Test
    fun `open full and broken traps expire after one minute from their own state transition`() {
        for (phase in listOf(SnarePhase.OPEN, SnarePhase.FULL, SnarePhase.BROKEN)) {
            val f = SnareWorld()
            f.player.statMap.setCurrentLevel("stat.hunter", 1)
            f.random.successRoll = if (phase == SnarePhase.BROKEN) 0.999 else 0.0
            val trap = f.lay()
            if (phase != SnarePhase.OPEN) { f.spawn(); f.until { trap.phase == phase } }
            assertEquals(f.clock.cycle + BirdSnares.TRAP_LIFETIME, trap.expiresAt)
            f.tick(BirdSnares.TRAP_LIFETIME - 1)
            assertSame(trap, f.manager.trapAt(f.tile))
            f.tick()
            assertNull(f.manager.trapAt(f.tile))
            assertEquals(1, f.groundCount())
            assertEquals(0, f.count(BirdSnares.SNARE))
            assertEquals(0, f.xp())
        }
    }

    @Test
    fun `logout emits one ground snare with shared drop timers and never refunds inventory or rewards`() {
        val f = SnareWorld()
        val trap = f.caught()
        f.fill()
        val contents = f.player.inv.objs.toList()
        repeat(2) { f.manager.logout(f.player) }
        f.tick()
        assertNull(f.manager.trapAt(f.tile))
        assertEquals(contents, f.player.inv.objs.toList())
        assertEquals(1, f.groundCount())
        assertEquals(0, f.xp())
        val obj = f.objRegistry.findAll(f.tile).single()
        val timers = PlayerDropTimers.resolve(BirdSnares.item(BirdSnares.SNARE))
        assertEquals(f.player.observerUUID, obj.ownerId)
        assertEquals(obj.creationCycle + timers.duration, obj.despawnCycle)
        assertEquals(obj.creationCycle + timers.reveal, obj.revealCycle)
        assertEquals(SnareFailure.STALE, f.manager.interact(f.player, trap.loc, SnareAction.CHECK))
    }

    @Test
    fun `post switch collapse keeps the snare in its original save mode even after public reveal`() {
        val f = SnareWorld()
        f.lay()
        f.player.worldType = WorldType.TWISTED_LEAGUE
        f.events.publish(WorldTypeChangedEvent(f.player, WorldType.MAIN, WorldType.TWISTED_LEAGUE))
        assertNull(f.manager.trapAt(f.tile))
        val snare = f.objRegistry.findAll(f.tile).single()
        assertEquals(WorldType.MAIN, snare.worldType)
        assertFalse(snare.isVisibleTo(f.player))
        assertEquals(0, f.count(BirdSnares.SNARE))
        snare.reveal()
        assertFalse(snare.isVisibleTo(f.player))
        f.player.worldType = WorldType.MAIN
        assertTrue(snare.isVisibleTo(f.player))
        assertEquals(1, snare.count)
    }

    @Test
    fun `failed ground insertion retains the trap and failed loc removal cannot emit a second ground snare`() {
        val f = SnareWorld()
        val trap = f.lay()
        every { f.objs.add(any<Obj>(), any(), any()) } returns false
        f.manager.logout(f.player)
        assertSame(trap, f.manager.trapAt(f.tile))
        assertFalse(trap.groundIssued)
        assertEquals(0, f.groundCount())
        every { f.objs.add(any<Obj>(), any(), any()) } answers { callOriginal() }
        every { f.locs.del(trap.loc, Int.MAX_VALUE) } returns false
        f.manager.logout(f.player)
        assertTrue(trap.groundIssued)
        assertEquals(1, f.groundCount())
        repeat(3) { f.manager.logout(f.player) }
        assertEquals(1, f.groundCount())
        every { f.locs.del(trap.loc, Int.MAX_VALUE) } answers { callOriginal() }
        f.manager.logout(f.player)
        assertNull(f.manager.trapAt(f.tile))
        assertEquals(1, f.groundCount())
    }

    companion object {
        private val RESTORATIONS = mutableListOf<() -> Unit>()

        @JvmStatic @BeforeAll
        fun loadCache() {
            ServerCacheManager.init(241).close()
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val previous = field.get(null)
                RESTORATIONS += { field.set(null, previous) }
            }
            val storage = InvVirtualStorageHolder.instance
            RESTORATIONS += { InvVirtualStorageHolder.instance = storage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic @AfterAll
        fun restore() { RESTORATIONS.asReversed().forEach { it() }; RESTORATIONS.clear() }
    }
}
