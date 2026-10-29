package org.rsmod.api.game.process.player

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.rsmod.annotations.InternalApi
import org.rsmod.api.account.AccountManager
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.CharacterDataTransformer
import org.rsmod.api.account.character.inv.CharacterInventoryApplier
import org.rsmod.api.account.character.inv.CharacterInventoryPipeline
import org.rsmod.api.account.character.main.CharacterMainSaveData
import org.rsmod.api.account.loader.request.AccountLoadCallback
import org.rsmod.api.account.loader.request.AccountLoadResponse
import org.rsmod.api.account.saver.CharacterSaveSnapshot
import org.rsmod.api.account.saver.request.AccountSaveCallback
import org.rsmod.api.account.saver.request.AccountSaveResponse
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.realm.Realm
import org.rsmod.events.EventBus
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.WorldTypeChangedEvent
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

@OptIn(InternalApi::class)
internal class SwitchWorldFixture(val player: Player = Player()) {
    val accounts = mockk<AccountManager>()
    val players = PlayerList()
    val events = EventBus()
    val realm = mockk<Realm>()
    val collision = CollisionFlagMap()
    val queueCache = EngineQueueCache()
    val contextFactory = mockk<ProtectedAccessContextFactory> {
        every { create() } returns ProtectedAccessContextFactory.empty()
    }
    val engineQueues = PlayerEngineQueueProcessor(events, queueCache, ProtectedAccessLauncher(contextFactory))
    val service = WorldTypeSwitchService(accounts, players, events, collision, realm, engineQueues)
    val saves = mutableListOf<Save>()
    val loads = mutableListOf<Load>()
    val changed = mutableListOf<WorldTypeChangedEvent>()
    val inventories = CharacterInventoryPipeline(CharacterInventoryApplier())
    var acceptLoads = true
    var restoreFails = false

    data class Save(val player: Player, val snapshot: CharacterSaveSnapshot, val callback: AccountSaveCallback)
    data class Load(val target: WorldType, val callback: AccountLoadCallback)

    init {
        player.accountId = 10
        player.characterId = 20
        player.uuid = 20
        player.observerUUID = 20
        player.slotId = 1
        player.assignUid()
        player.worldType = WorldType.MAIN
        player.username = "switch-test"
        player.currentMapClock = 1000
        player.processedMapClock = 1000
        if (player.coords == CoordGrid.ZERO) player.coords = CoordGrid(3200, 3200)
        player.inv = player.invMap.getOrPut("inv.inv")
        player.inv.owner = player
        player.worn = player.invMap.getOrPut("inv.worn")
        player.xpRate = 1.0
        players[1] = player
        every { realm.config.devMode } returns false
        every { accounts.capture(any()) } answers { capture(firstArg()) }
        every { accounts.save(any(), any<CharacterSaveSnapshot>(), any()) } answers {
            saves += Save(firstArg(), secondArg(), thirdArg())
        }
        every { accounts.loadWorldTypeSwitch(any(), any(), any()) } answers {
            if (acceptLoads) loads += Load(secondArg(), thirdArg())
            acceptLoads
        }
        every { accounts.restore(any(), any()) } answers {
            if (restoreFails) error("restore failure")
            val target = firstArg<Player>()
            val snapshot = secondArg<CharacterSaveSnapshot>()
            target.worldType = snapshot.worldType
            target.coords = snapshot.main.coords
            target.runEnergy = snapshot.main.runEnergy
            target.vars.backing.clear()
            target.vars.backing.putAll(snapshot.main.varps)
            inventories.restore(target, snapshot.segments.getValue("inventory"))
        }
        events.subscribeUnbound(WorldTypeChangedEvent::class.java) { changed += this }
    }

    fun capture(target: Player): CharacterSaveSnapshot = CharacterSaveSnapshot(
        target.accountId, target.characterId, target.worldType,
        CharacterMainSaveData(target.displayName, target.members, target.coords, target.lastLogin,
            target.runEnergy, 100, false, target.vars.backing.toMap(), emptyMap()),
        mapOf("inventory" to inventories.capture(target)),
    )

    fun start(target: WorldType = WorldType.RAGING_ECHOES_LEAGUE) {
        assertEquals(WorldTypeSwitchService.Result.Started,
            service.switch(player, target, WorldType.entries))
        service.processPendingSwitches()
    }

    fun saved(index: Int = saves.lastIndex, response: AccountSaveResponse = AccountSaveResponse.Success(saves[index].player)) {
        saves[index].callback(response)
        service.processPendingSwitches()
    }

    fun loaded(index: Int = loads.lastIndex, effect: (Player) -> Unit = { it.inv[0] = InvObj("obj.coins", 3) }) {
        loads[index].callback(response(loads[index].target, effect))
        service.processPendingSwitches()
    }

    fun response(target: WorldType, effect: (Player) -> Unit = {}): AccountLoadResponse.Ok.LoadAccount {
        val response = mockk<AccountLoadResponse.Ok.LoadAccount>()
        every { response.worldType } returns target
        every { response.account.accountId } returns player.accountId
        every { response.account.characterData.characterId } returns player.characterId
        every { response.firstVisitToWorldType } returns false
        val applier = object : CharacterDataStage.Applier<TestSegment> {
            override fun apply(player: Player, data: TestSegment) = effect(player)
        }
        every { response.transforms } returns listOf(CharacterDataTransformer(applier, TestSegment))
        return response
    }

    private object TestSegment : CharacterDataStage.Segment

    class SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }
}
