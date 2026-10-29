package org.rsmod.api.game.process.player

import dev.openrune.ServerCacheManager
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.util.EnumMap
import net.rsprot.protocol.api.util.ZonePartialEnclosedCacheBuffer
import net.rsprot.protocol.common.client.OldSchoolClientType
import net.rsprot.protocol.game.outgoing.zone.header.UpdateZoneFullFollows
import net.rsprot.protocol.game.outgoing.zone.payload.ObjAdd
import net.rsprot.protocol.game.outgoing.zone.payload.ObjCount
import net.rsprot.protocol.game.outgoing.zone.payload.ObjDel
import net.rsprot.protocol.message.ZoneProt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.registry.loc.LocRegistry
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.EntityLifecycleProcess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.utils.zone.SharedZoneEnclosedBuffers
import org.rsmod.game.MapClock
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.obj.Obj
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid

@ResourceLock("ServerCacheManager")
class WorldTypeGroundItemTest {
    @Test
    fun `player factories retain item mode while static and unscoped server spawns stay shared`() {
        val owner = player(1)
        val foreign = player(1, WorldType.RAGING_ECHOES_LEAGUE)
        val npc = mockk<Npc>()
        every { npc.coords } returns owner.coords
        val item = InvObj("obj.coins", 5)
        val items = listOf(
            Obj.fromOwner(owner, owner.coords, item),
            Obj.fromPersonal(owner, owner.coords, item),
            Obj.fromPvn(owner, npc, item),
            Obj.fromPvp(foreign, owner, item),
        )
        for (obj in items) {
            assertEquals(WorldType.MAIN, obj.worldType)
            assertFalse(obj.isVisibleTo(foreign))
            obj.reveal()
            assertFalse(obj.isVisibleTo(foreign))
        }
        val shared = Obj.fromServer(MapClock(), owner.coords, item)
        assertNull(shared.worldType)
        assertTrue(shared.isVisibleTo(owner))
        assertTrue(shared.isVisibleTo(foreign))
        val scopedPublic = Obj.fromServer(MapClock(), owner.coords, item, WorldType.MAIN)
        assertTrue(scopedPublic.isPublic)
        assertTrue(scopedPublic.isVisibleTo(owner))
        assertFalse(scopedPublic.isVisibleTo(foreign))
    }

    @Test
    fun `outgoing mode can be captured before save replacement without refunding into the new save`() {
        val owner = player(1)
        val modeAtPlacement = owner.worldType
        owner.worldType = WorldType.RAGING_ECHOES_LEAGUE
        val trapDrop = Obj.fromOwner(owner, owner.coords, "obj.hunting_ojibway_bird_snare", 1, modeAtPlacement)
        assertFalse(trapDrop.isVisibleTo(owner))
        assertFalse(trapDrop.isOriginalOwner(owner))
        owner.worldType = WorldType.MAIN
        assertTrue(trapDrop.isVisibleTo(owner))
        assertTrue(trapDrop.isOriginalOwner(owner))
    }

    @Test
    fun `same character stacks merge only within their original mode and conserve overflow`() {
        val f = Fixture()
        val owner = player(1)
        val first = Obj.fromOwner(owner, owner.coords, "obj.coins", 7)
        assertTrue(f.repo.add(first, 100))
        owner.worldType = WorldType.RAGING_ECHOES_LEAGUE
        val second = Obj.fromOwner(owner, owner.coords, "obj.coins", 11)
        assertTrue(f.repo.add(second, 100))
        assertTrue(f.repo.add(Obj.fromOwner(owner, owner.coords, "obj.coins", 13), 100))
        assertEquals(7, first.count)
        assertEquals(24, second.count)
        assertTrue(f.repo.add(Obj.fromOwner(owner, owner.coords, "obj.coins", Int.MAX_VALUE), 100))
        assertEquals(3, f.registry.findAll(owner.coords).count())
        assertEquals(Int.MAX_VALUE.toLong() + 31, f.registry.findAll(owner.coords).sumOf { it.count.toLong() })
        assertEquals(Int.MAX_VALUE.toLong() + 24, f.registry.findAll(owner.coords).filter { it.isVisibleTo(owner) }.sumOf { it.count.toLong() })
    }

    @Test
    fun `nonstackable splitting retains mode ownership and expiry on every object`() {
        val f = Fixture()
        val owner = player(1)
        val foreign = player(1, WorldType.RAGING_ECHOES_LEAGUE)
        val item = Obj.fromOwner(owner, owner.coords, "obj.bronze_sword", 3)
        assertTrue(f.repo.add(item, 40, 10))
        val split = f.registry.findAll(owner.coords).toList()
        assertEquals(3, split.size)
        for (obj in split) {
            assertEquals(WorldType.MAIN, obj.worldType)
            assertEquals(1, obj.count)
            assertEquals(140, obj.despawnCycle)
            assertFalse(obj.isVisibleTo(foreign))
            f.registry.reveal(obj, 110)
            assertTrue(obj.isVisibleTo(player(2)))
            assertFalse(obj.isVisibleTo(foreign))
        }
        f.clock.cycle = 140
        f.lifecycle.process()
        assertTrue(f.registry.findAll(owner.coords).none())
    }

    @Test
    fun `personal rewards survive reconnect and group changes without crossing saves or revealing`() {
        val f = Fixture()
        val owner = player(1, observer = 77)
        val personal = Obj.fromPersonal(owner, owner.coords, "obj.coins", 3)
        assertTrue(f.repo.add(personal, 100, 1))
        val reconnected = player(1, observer = 88)
        val groupMate = player(2, observer = 77)
        val foreign = player(1, WorldType.RAGING_ECHOES_LEAGUE, observer = 88)
        assertTrue(personal.isVisibleTo(reconnected))
        assertTrue(personal.isOriginalOwner(reconnected))
        assertFalse(personal.isVisibleTo(groupMate))
        assertFalse(personal.isVisibleTo(foreign))
        assertFalse(personal.isOriginalOwner(foreign))
        f.clock.cycle++
        f.lifecycle.process()
        f.registry.reveal(personal)
        assertTrue(personal.isPrivate)
        assertEquals(Obj.NO_TIMER, personal.revealCycle)
        assertTrue(f.repo.add(Obj.fromPersonal(foreign, foreign.coords, "obj.coins", 5), 100))
        assertEquals(2, f.registry.findAll(owner.coords).count())
    }

    @Test
    fun `ordinary group ownership and public reveal are limited to the item mode`() {
        val owner = player(1, observer = 77)
        val groupMate = player(2, observer = 77)
        val stranger = player(3)
        val foreign = player(2, WorldType.RAGING_ECHOES_LEAGUE, observer = 77)
        val obj = Obj.fromOwner(owner, owner.coords, "obj.coins", 1)
        assertTrue(obj.isVisibleTo(groupMate))
        assertTrue(obj.isOriginalOwner(groupMate))
        assertFalse(obj.isVisibleTo(stranger))
        assertFalse(obj.isVisibleTo(foreign))
        obj.reveal()
        assertTrue(obj.isVisibleTo(stranger))
        assertFalse(obj.isOriginalOwner(stranger))
        assertFalse(obj.isVisibleTo(foreign))
        assertFalse(obj.isOriginalOwner(foreign))
    }

    @Test
    fun `a visible same-type stack cannot validate a stale or foreign interaction target`() {
        val f = Fixture()
        val owner = player(1)
        val foreign = player(1, WorldType.RAGING_ECHOES_LEAGUE)
        val old = Obj.fromOwner(owner, owner.coords, "obj.coins", 1)
        val other = Obj.fromOwner(foreign, foreign.coords, "obj.coins", 1)
        f.repo.add(old, 100)
        f.repo.add(other, 100)
        assertTrue(f.registry.isValid(owner, old))
        assertFalse(f.registry.isValid(owner, other))
        assertFalse(f.registry.isPublicAndValid(other))
        f.repo.del(old)
        val replacement = Obj.fromOwner(owner, owner.coords, "obj.coins", 1)
        f.repo.add(replacement, 100)
        assertFalse(f.registry.isValid(owner, old))
        assertTrue(f.registry.isValid(owner, replacement))
    }

    @Test
    fun `initial scenes filter private and public scoped loot while retaining shared spawns`() {
        val f = Fixture()
        val owner = player(1)
        val foreign = player(1, WorldType.RAGING_ECHOES_LEAGUE)
        f.repo.add(Obj.fromOwner(owner, owner.coords, "obj.coins", 3), 100)
        f.repo.add(Obj.fromServer(f.clock, owner.coords, "obj.coins", 5, WorldType.MAIN), 100)
        f.repo.add(Obj.fromServer(f.clock, owner.coords, "obj.coins", 7), 100)
        assertEquals(listOf(3, 5, 7), f.packets(owner).filterIsInstance<ObjAdd>().map { it.quantity }.sorted())
        assertEquals(listOf(7), f.packets(foreign).filterIsInstance<ObjAdd>().map { it.quantity })
    }

    @Test
    fun `transient private add count and delete never reach another mode with matching UUID`() {
        val f = Fixture()
        val owner = player(1)
        val foreign = player(1, WorldType.RAGING_ECHOES_LEAGUE)
        f.packets(owner)
        f.packets(foreign)
        val obj = Obj.fromOwner(owner, owner.coords, "obj.coins", 3)
        f.repo.add(obj, 100)
        assertEquals(1, f.packets(owner).filterIsInstance<ObjAdd>().size)
        assertTrue(f.packets(foreign).isEmpty())
        f.updates.clear()
        f.repo.add(Obj.fromOwner(owner, owner.coords, "obj.coins", 4), 100)
        assertEquals(1, f.packets(owner).filterIsInstance<ObjCount>().size)
        assertTrue(f.packets(foreign).isEmpty())
        f.updates.clear()
        f.repo.del(obj)
        assertEquals(1, f.packets(owner).filterIsInstance<ObjDel>().size)
        assertTrue(f.packets(foreign).isEmpty())
    }

    @Test
    fun `reveal public delete and public spawn remain scoped and never enter shared encoded buffers`() {
        val f = Fixture()
        val owner = player(1)
        val stranger = player(2)
        val foreign = player(3, WorldType.RAGING_ECHOES_LEAGUE)
        val obj = Obj.fromOwner(owner, owner.coords, "obj.coins", 3)
        f.repo.add(obj, 100, 1)
        listOf(owner, stranger, foreign).forEach { f.packets(it) }
        f.updates.clear()
        f.clock.cycle++
        f.lifecycle.process()
        assertTrue(obj.isPublic)
        assertTrue(f.packets(owner).isEmpty())
        assertEquals(1, f.packets(stranger).filterIsInstance<ObjAdd>().size)
        assertTrue(f.packets(foreign).isEmpty())
        f.assertNoSharedPackets()
        f.updates.clear()
        f.repo.del(obj)
        assertEquals(1, f.packets(owner).filterIsInstance<ObjDel>().size)
        assertEquals(1, f.packets(stranger).filterIsInstance<ObjDel>().size)
        assertTrue(f.packets(foreign).isEmpty())
        f.assertNoSharedPackets()
        f.updates.clear()
        f.repo.add(Obj.fromServer(f.clock, owner.coords, "obj.coins", 4, WorldType.MAIN), 100)
        assertEquals(1, f.packets(stranger).filterIsInstance<ObjAdd>().size)
        assertTrue(f.packets(foreign).isEmpty())
        f.assertNoSharedPackets()
    }

    @Test
    fun `mode switch scene reset removes old mode and resends only target mode ground items`() {
        val f = Fixture()
        val owner = player(1)
        val switching = SwitchWorldFixture(owner)
        val foreign = player(checkNotNull(owner.uuid), WorldType.RAGING_ECHOES_LEAGUE)
        f.repo.add(Obj.fromOwner(owner, owner.coords, "obj.coins", 3), 100)
        f.repo.add(Obj.fromOwner(foreign, foreign.coords, "obj.coins", 5), 100)
        assertEquals(listOf(3), f.packets(owner).filterIsInstance<ObjAdd>().map { it.quantity })
        f.updates.clear()
        switching.start()
        switching.saved()
        switching.loaded(effect = {})
        assertFalse(owner.forceDisconnect, "The mode swap must complete rather than hitting its error recovery")
        assertEquals(WorldType.RAGING_ECHOES_LEAGUE, owner.worldType)
        assertTrue(owner.regionRebuildPending)
        val client = owner.client as CapturingClient
        client.messages.clear()
        assertEquals(listOf(5), f.packets(owner).filterIsInstance<ObjAdd>().map { it.quantity })
        assertTrue(client.messages.any { it is UpdateZoneFullFollows })
        assertEquals(8, f.registry.findAll(owner.coords).sumOf { it.count })
    }

    private class Fixture {
        val clock = MapClock(100)
        val updates = ZoneUpdateMap()
        val registry = ObjRegistry(updates)
        val repo = ObjRepository(clock, registry)
        val lifecycle = EntityLifecycleProcess(mockk(relaxed = true), mockk(relaxed = true), repo, mockk(relaxed = true))
        private val encoded = mutableListOf<ZoneProt>()
        private val sharedEncoded = mutableListOf<ZoneProt>()
        private val cache = mockk<ZonePartialEnclosedCacheBuffer>()
        private val locs = mockk<LocRegistry>()
        private val buffers = SharedZoneEnclosedBuffers(updates, cache)
        private val processor = PlayerZoneUpdateProcessor(updates, locs, registry, buffers)

        init {
            every { locs.findAllSpawned(any()) } returns emptySequence()
            every { cache.computeZoneForClient(any(), any<Collection<ZoneProt>>()) } answers {
                encoded += secondArg<Collection<ZoneProt>>()
                Unpooled.EMPTY_BUFFER
            }
            every { cache.computeZone(any<Collection<ZoneProt>>()) } answers {
                sharedEncoded += firstArg<Collection<ZoneProt>>()
                EnumMap<OldSchoolClientType, ByteBuf>(OldSchoolClientType::class.java)
            }
        }

        fun packets(player: Player): List<ZoneProt> {
            encoded.clear()
            processor.process(player)
            return encoded.toList()
        }

        fun assertNoSharedPackets() {
            sharedEncoded.clear()
            processor.computeEnclosedBuffers()
            assertTrue(sharedEncoded.isEmpty(), "Scoped public updates cannot use a buffer sent to all modes")
        }
    }

    private fun player(id: Long, mode: WorldType = WorldType.MAIN, observer: Long = id): Player = Player().apply {
        uuid = id
        characterId = id.toInt()
        username = "mode-test-$id"
        observerUUID = observer
        worldType = mode
        coords = CoordGrid(3200, 3200)
        buildArea = CoordGrid(3152, 3152)
        currentMapClock = 100
        client = CapturingClient()
    }

    private class CapturingClient : Client<Any, Any> {
        val messages = mutableListOf<Any>()
        override fun write(message: Any) { messages += message }
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        @JvmStatic @BeforeAll fun cache() { ServerCacheManager.init(241).close() }
    }
}
