package org.rsmod.api.game.process.player

import dev.openrune.ServerCacheManager
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.Unpooled
import net.rsprot.protocol.api.util.ZonePartialEnclosedCacheBuffer
import net.rsprot.protocol.game.outgoing.zone.payload.ObjAdd
import net.rsprot.protocol.game.outgoing.zone.payload.ObjCount
import net.rsprot.protocol.game.outgoing.zone.payload.ObjDel
import net.rsprot.protocol.message.ZoneProt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.registry.loc.LocRegistry
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.registry.obj.ObjRegistryResult
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.registry.zone.ZoneUpdateTransformer
import org.rsmod.api.repo.EntityLifecycleProcess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.utils.zone.SharedZoneEnclosedBuffers
import org.rsmod.game.MapClock
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.obj.Obj
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid

@ResourceLock("ServerCacheManager")
class PersonalGroundItemTest {
    @Test
    fun `personal rewards do not inherit group ownership or observer-id collisions`() {
        val owner = player(101)
        val teammate = player(102)
        val outsider = player(GROUP_ID, observer = 999)
        val obj = Obj.fromPersonal(owner, owner.coords, "obj.grace", 1)
        assertTrue(obj.isVisibleTo(owner))
        assertTrue(obj.isOriginalOwner(owner))
        assertFalse(obj.isVisibleTo(teammate))
        assertFalse(obj.isOriginalOwner(teammate))
        assertFalse(obj.isVisibleTo(outsider))
        assertFalse(obj.isVisibleTo(GROUP_ID))
        assertFalse(obj.isVisibleTo(null))
        owner.observerUUID = 1000
        assertTrue(obj.isVisibleTo(owner), "a group change cannot transfer a personal reward")
        assertTrue(obj.isOriginalOwner(owner))
    }

    @Test
    fun `personal stack merging conserves counts and separates players and ordinary group loot`() {
        val registry = ObjRegistry(ZoneUpdateMap())
        val owner = player(101)
        val teammate = player(102)
        val first = Obj.fromPersonal(owner, owner.coords, "obj.grace", 1)
        val second = Obj.fromPersonal(teammate, owner.coords, "obj.grace", 2)
        val shared = Obj.fromOwner(owner, owner.coords, "obj.grace", 3)
        registry.add(first)
        registry.add(second)
        registry.add(shared)
        val merged = registry.add(Obj.fromPersonal(owner, owner.coords, "obj.grace", 4))
        assertSame(first, (merged as ObjRegistryResult.Add.Merge).merged)
        assertEquals(listOf(2, 3, 5), registry.findAll(owner.coords).map { it.count }.sorted().toList())
        assertEquals(5, registry.findAll(owner.coords).filter { it.isVisibleTo(teammate) }.sumOf { it.count })
        assertEquals(10, registry.findAll(owner.coords).sumOf { it.count })

        registry.add(Obj.fromPersonal(owner, owner.coords, "obj.grace", Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE.toLong() + 10, registry.findAll(owner.coords).sumOf { it.count.toLong() })
        assertTrue(registry.findAll(owner.coords).all { it.count > 0 })
        assertEquals(4, registry.findAll(owner.coords).count())
    }

    @Test
    fun `split nonstackable personal objects retain ownership and timers`() {
        val registry = ObjRegistry(ZoneUpdateMap())
        val owner = player(101)
        val teammate = player(102)
        val obj = Obj.fromPersonal(owner, owner.coords, "obj.bronze_sword", 3)
        obj.setTimers(20, 25, 40)
        val split = registry.add(obj) as ObjRegistryResult.Add.Split
        assertEquals(3, split.split.size)
        for (copy in split.split) {
            assertEquals(1, copy.count)
            assertEquals(owner.uuid, copy.personalReceiverId)
            assertEquals(40, copy.despawnCycle)
            assertEquals(Obj.NO_TIMER, copy.revealCycle)
            assertTrue(copy.isVisibleTo(owner))
            assertFalse(copy.isVisibleTo(teammate))
        }
    }

    @Test
    fun `personal rewards never reveal even when tradeable and refresh expiry on stacking`() {
        val updates = ZoneUpdateMap()
        val registry = ObjRegistry(updates)
        val clock = MapClock(10)
        val repository = ObjRepository(clock, registry)
        val lifecycle = EntityLifecycleProcess(mockk(relaxed = true), mockk(relaxed = true), repository, mockk(relaxed = true))
        val owner = player(101)
        val teammate = player(102)
        val obj = Obj.fromPersonal(owner, owner.coords, "obj.coins", 1)
        assertTrue(repository.add(obj, duration = 100, reveal = 1))
        assertEquals(Obj.NO_TIMER, obj.revealCycle)
        assertTrue(obj.neverBecomesPublic)
        updates.clear()
        clock.cycle = 11
        lifecycle.process()
        registry.reveal(obj)
        obj.reveal()
        assertTrue(obj.isPrivate)
        assertFalse(obj.isVisibleTo(teammate))
        assertTrue(updates.updatedZones.isEmpty())

        clock.cycle = 50
        assertTrue(repository.add(Obj.fromPersonal(owner, owner.coords, "obj.coins", 2), duration = 100, reveal = 1))
        assertEquals(3, obj.count)
        assertEquals(150, obj.despawnCycle)
        assertEquals(Obj.NO_TIMER, obj.revealCycle)
        clock.cycle = 110
        lifecycle.process()
        assertEquals(3, registry.findAll(owner.coords).sumOf { it.count })
        clock.cycle = 150
        lifecycle.process()
        assertTrue(registry.findAll(owner.coords).none())
    }

    @Test
    fun `ordinary group-owned drops remain shared and reveal normally`() {
        val owner = player(101)
        val teammate = player(102)
        val outsider = player(103, observer = 999)
        val obj = Obj.fromOwner(owner, owner.coords, "obj.coins", 1)
        assertTrue(obj.isVisibleTo(teammate))
        assertTrue(obj.isOriginalOwner(teammate))
        assertFalse(obj.isVisibleTo(outsider))
        obj.reveal()
        assertTrue(obj.isVisibleTo(outsider))
        assertFalse(obj.isOriginalOwner(outsider))
    }

    @Test
    fun `personal add count and delete packets reach only the player on initial and later zone updates`() {
        val updates = ZoneUpdateMap()
        val registry = ObjRegistry(updates)
        val locs = mockk<LocRegistry>()
        every { locs.findAllSpawned(any()) } returns emptySequence()
        val encoded = mutableListOf<ZoneProt>()
        val buffers = mockk<ZonePartialEnclosedCacheBuffer>()
        every { buffers.computeZoneForClient(any(), any<Collection<ZoneProt>>()) } answers {
            encoded += secondArg<Collection<ZoneProt>>()
            Unpooled.EMPTY_BUFFER
        }
        val processor = PlayerZoneUpdateProcessor(updates, locs, registry, SharedZoneEnclosedBuffers(updates, buffers))
        val owner = player(101)
        val teammate = player(102)
        fun packets(player: Player): List<ZoneProt> {
            encoded.clear()
            processor.process(player)
            return encoded.toList()
        }

        val obj = Obj.fromPersonal(owner, owner.coords, "obj.grace", 1)
        registry.add(obj)
        assertEquals(1, packets(owner).filterIsInstance<ObjAdd>().size)
        assertTrue(packets(teammate).isEmpty())
        assertNull(ZoneUpdateTransformer.toPersistentObjAdd(obj, GROUP_ID, 0))
        updates.clear()

        registry.add(Obj.fromPersonal(owner, owner.coords, "obj.grace", 2))
        assertEquals(1, packets(owner).filterIsInstance<ObjCount>().size)
        assertTrue(packets(teammate).isEmpty())
        updates.clear()

        registry.del(obj)
        assertEquals(1, packets(owner).filterIsInstance<ObjDel>().size)
        assertTrue(packets(teammate).isEmpty())
        updates.clear()

        registry.add(Obj.fromPersonal(owner, owner.coords, "obj.grace", 1))
        assertEquals(1, packets(owner).filterIsInstance<ObjAdd>().size)
        assertTrue(packets(teammate).isEmpty())
    }

    private fun player(id: Long, observer: Long = GROUP_ID): Player = Player().apply {
        uuid = id
        observerUUID = observer
        worldType = WorldType.MAIN
        coords = CoordGrid(3200, 3200)
        buildArea = CoordGrid(3152, 3152)
        currentMapClock = 0
        client = SilentClient
    }

    private object SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private const val GROUP_ID = 77L

        @JvmStatic @BeforeAll fun cache() { ServerCacheManager.init(241).close() }
    }
}
