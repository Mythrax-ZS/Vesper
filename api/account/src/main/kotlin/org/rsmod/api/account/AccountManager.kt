package org.rsmod.api.account

import jakarta.inject.Inject
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.main.CharacterAccountRepository
import org.rsmod.api.account.loader.AccountLoaderService
import org.rsmod.api.account.loader.request.AccountLoadAuth
import org.rsmod.api.account.loader.request.AccountLoadCallback
import org.rsmod.api.account.loader.request.AccountLoadRequest
import org.rsmod.api.account.saver.AccountSavingService
import org.rsmod.api.account.saver.CharacterSaveSnapshot
import org.rsmod.api.account.saver.request.AccountSaveCallback
import org.rsmod.api.account.saver.request.AccountSaveRequest
import org.rsmod.api.account.saver.request.AccountSaveResponse
import org.rsmod.game.entity.Player
import org.rsmod.game.world.WorldType

public class AccountManager
@Inject
constructor(
    private val loader: AccountLoaderService,
    private val saver: AccountSavingService,
    private val repository: CharacterAccountRepository,
    private val pipelines: Set<CharacterDataStage.Pipeline>,
) {
    public fun capture(player: Player): CharacterSaveSnapshot = CharacterSaveSnapshot(
        accountId = player.accountId,
        characterId = player.characterId,
        worldType = player.worldType,
        main = repository.capture(player),
        segments = java.util.Map.copyOf(pipelines.associate { it.javaClass.name to it.capture(player) }),
    )

    public fun restore(player: Player, snapshot: CharacterSaveSnapshot) {
        require(player.accountId == snapshot.accountId && player.characterId == snapshot.characterId)
        val suspended = player.persistenceSuspended
        player.persistenceSuspended = true
        try {
            player.worldType = snapshot.worldType
            repository.restore(player, snapshot.main)
            for (pipeline in pipelines) pipeline.restore(player, snapshot.segments.getValue(pipeline.javaClass.name))
        } finally {
            player.persistenceSuspended = suspended
        }
    }

    public fun save(player: Player, callback: AccountSaveCallback) {
        if (player.persistenceInvalidated) {
            callback(AccountSaveResponse.InvalidState(player))
            return
        }
        saver.queue(AccountSaveRequest(player, capture(player), callback))
    }

    public fun save(player: Player, snapshot: CharacterSaveSnapshot, callback: AccountSaveCallback) {
        if (player.persistenceInvalidated) {
            callback(AccountSaveResponse.InvalidState(player))
            return
        }
        require(capture(player) == snapshot) { "Cannot enqueue a stale character checkpoint" }
        saver.queue(AccountSaveRequest(player = player, snapshot = snapshot, callback = callback))
    }

    public fun load(
        auth: AccountLoadAuth,
        accountName: String,
        callback: AccountLoadCallback,
    ): Boolean {
        val loadRequest =
            AccountLoadRequest.StrictSearch(auth, accountName, WorldType.DEFAULT, callback)
        return loader.queue(loadRequest)
    }

    public fun loadOrCreate(
        auth: AccountLoadAuth,
        accountName: String,
        hashedPassword: () -> String,
        callback: AccountLoadCallback,
    ): Boolean {
        val loadRequest =
            AccountLoadRequest.SearchOrCreateWithPassword(
                hashedPassword,
                auth,
                accountName,
                WorldType.DEFAULT,
                callback,
            )
        return loader.queue(loadRequest)
    }

    /** Loads [worldType]'s save, pinning the mode instead of resolving it from the preference. */
    public fun loadWorldTypeSwitch(
        accountName: String,
        worldType: WorldType,
        callback: AccountLoadCallback,
    ): Boolean {
        val loadRequest =
            AccountLoadRequest.WorldTypeSwitch(
                auth = AccountLoadAuth.UnknownDevice,
                accountName = accountName,
                worldType = worldType,
                callback = callback,
            )
        return loader.queue(loadRequest)
    }

    public fun isLoaderShuttingDown(): Boolean = loader.isShuttingDown()

    public fun isLoaderRejectingRequests(): Boolean = loader.isTemporarilyRejectingRequests()
}
