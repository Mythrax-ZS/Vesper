package org.rsmod.content.skills.hunter

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.*
import org.rsmod.annotations.InternalApi
import org.rsmod.api.game.process.npc.NpcMovementProcessor
import org.rsmod.api.game.process.player.PlayerInteractionProcessor
import org.rsmod.api.game.process.player.PlayerMovementProcessor
import org.rsmod.api.hunt.Hunt
import org.rsmod.api.player.hook.PlayerObjTakeValidateHook
import org.rsmod.api.player.hook.PlayerObjTakeValidator
import org.rsmod.api.player.interact.ObjInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.loc.LocRegistry
import org.rsmod.api.registry.loc.LocRegistryNormal
import org.rsmod.api.registry.npc.NpcRegistry
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.route.RouteFactory
import org.rsmod.api.route.StepFactory
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.obj.Obj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

@OptIn(InternalApi::class)
internal class SnareWorld(takeHooks: Set<PlayerObjTakeValidateHook> = emptySet()) {
    val events = EventBus()
    val clock = MapClock(100)
    val collision = CollisionFlagMap()
    val updates = spyk(ZoneUpdateMap())
    private val zones = LocZoneStorage()
    val locRegistry = LocRegistry(zones, LocRegistryNormal(updates, collision, zones), mockk())
    val locs = spyk(LocRepository(clock, locRegistry, mockk()))
    val npcList = NpcList()
    val npcRegistry = NpcRegistry(npcList, collision, events)
    val npcs = NpcRepository(clock, npcRegistry, npcList)
    val objRegistry = ObjRegistry(updates)
    val objs = spyk(ObjRepository(clock, objRegistry))
    private val hunt = Hunt(RayCastValidator(collision), mockk(), npcRegistry, objRegistry, locRegistry)
    private val movement = NpcMovementProcessor(collision, StepFactory(collision), events)
    private val objInteractions = ObjInteractions(events)
    private val playerMovement = PlayerMovementProcessor(collision, RouteFactory(collision), StepFactory(collision), events)
    private val playerInteraction by lazy {
        val factory = mockk<ProtectedAccessContextFactory> {
            every { create() } returns ProtectedAccessContextFactory.empty().copy(getEventBus = { events })
        }
        PlayerInteractionProcessor(events, locRegistry, objRegistry, BoundValidator(collision), RayCastValidator(collision),
            mockk(), mockk(), mockk(), mockk(), objInteractions, mockk(), mockk(), mockk(),
            ProtectedAccessLauncher(factory), playerMovement)
    }
    val random = Rolls()
    val manager = BirdSnareManager(clock, hunt, locs, npcs, objs, WorldRepository(updates), collision, RayCastValidator(collision),
        random, InvisibleLevels(emptySet()), XpModifiers(emptySet()))
    val script = BirdSnareScript(manager, PlayerObjTakeValidator(takeHooks))
    val tile = CoordGrid(3200, 3200)
    val player = player(1)
    private var coroutine: GameCoroutine? = null
    private var result: Result<Unit>? = null
    var advanceActions = true

    init {
        for (dx in -16..16 step 8) for (dz in -16..16 step 8) {
            collision.allocateIfAbsent(tile.x + dx, tile.z + dz, tile.level)
        }
        with(script) { ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup() }
    }

    fun player(id: Long): Player = Player().apply {
        uuid = id
        observerUUID = id
        worldType = WorldType.MAIN
        coords = tile
        currentMapClock = clock.cycle
        processedMapClock = clock.cycle
        client = SilentClient
        inv = Inventory.create("inv.inv")
        inv.owner = this
        worn = Inventory.create("inv.worn")
        statMap.setBaseLevel("stat.hunter", 99)
        statMap.setCurrentLevel("stat.hunter", 99)
        xpRate = 1.0
        globalXpRate = 1.0
    }

    fun lay(at: CoordGrid = tile, owner: Player = player, count: Int = 1): BirdSnare {
        owner.coords = at
        owner.inv[0] = InvObj(BirdSnares.SNARE, count)
        assertNull(manager.lay(owner, 0, owner.inv[0]!!, at))
        owner.coords = at.translate(-1, 0)
        return checkNotNull(manager.trapAt(at))
    }

    fun spawn(bird: BirdSnareData = BirdSnares.BIRDS.first(), at: CoordGrid = tile.translate(2, 0)): Npc =
        Npc(bird.npc, at).also { npcs.add(it, Int.MAX_VALUE) }

    fun caught(data: BirdSnareData = BirdSnares.BIRDS.first()): BirdSnare {
        val trap = lay()
        spawn(data)
        until { trap.phase == SnarePhase.FULL }
        return trap
    }

    fun until(limit: Int = 30, predicate: () -> Boolean) {
        repeat(limit) { if (predicate()) return; tick() }
        assertTrue(predicate(), "Lifecycle did not reach expected phase within $limit ticks")
    }

    fun tick(count: Int = 1) {
        repeat(count) {
            clock.tick()
            player.currentMapClock = clock.cycle
            player.processedMapClock = clock.cycle
            manager.tick()
            for (npc in npcList) {
                npc.currentMapClock = clock.cycle
                npc.processedMapClock = clock.cycle
                if (npc.lifecycleRespawnCycle == clock.cycle) npcRegistry.respawn(npc)
                if (!npc.canProcess) continue
                val previous = ZoneKey.from(npc.coords)
                movement.process(npc)
                val next = ZoneKey.from(npc.coords)
                if (previous != next) {
                    npcRegistry.change(npc, previous, next)
                    npc.lastProcessedZone = next
                }
            }
            if (advanceActions) coroutine?.advance()
            result?.exceptionOrNull()?.let { if (it !is CancellationException) throw it }
        }
    }

    fun start(action: suspend ProtectedAccess.() -> Unit) {
        result = null
        val coroutine = GameCoroutine()
        this.coroutine = coroutine
        player.activeCoroutine = coroutine
        val access = ProtectedAccess(player, coroutine, ProtectedAccessContextFactory.empty().copy(getEventBus = { events }))
        val block: suspend () -> Unit = { access.action() }
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { this@SnareWorld.result = result }
        })
        result?.getOrThrow()
    }

    fun cancel() {
        assertFalse(player.isDelayed)
        player.clearPendingAction(events)
        assertNull(player.activeCoroutine)
    }

    fun fill() { for (slot in player.inv.indices) player.inv[slot] = InvObj("obj.bronze_dagger") }

    fun groundSnare(count: Int = 1, owner: Player = player): Obj =
        Obj.fromOwner(owner, tile, BirdSnares.SNARE, count).also { assertTrue(objs.add(it, 300, 100)) }

    fun approachGround(source: Obj) { objInteractions.interact(player, source, InteractionOp.Op4) }

    fun processPlayerInteraction() { tick(); playerInteraction.process(player) }

    fun count(symbol: String): Int = player.inv.physicalCount(symbol)

    fun xp(): Int = player.statMap.getFineXP("stat.hunter")

    fun groundCount(): Int = objRegistry.findAll(tile).sumOf { it.count }

    class Rolls : GameRandom {
        var successRoll = 0.0
        var rolls = 0
        override fun of(maxExclusive: Int): Int = 0
        override fun of(minInclusive: Int, maxInclusive: Int): Int = minInclusive
        override fun randomDouble(): Double { rolls++; return successRoll }
    }

    private object SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }
}
