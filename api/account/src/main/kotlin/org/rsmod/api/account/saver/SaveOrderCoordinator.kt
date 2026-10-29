package org.rsmod.api.account.saver

import org.rsmod.api.account.saver.request.AccountSaveRequest
import org.rsmod.game.world.WorldType

internal class SaveOrderCoordinator {
    private val characters = HashMap<Int, PendingCharacter>()
    private var nextSequence = 0L

    @Synchronized
    fun register(request: AccountSaveRequest) {
        check(request.sequence == 0L)
        request.sequence = ++nextSequence
        characters.getOrPut(request.snapshot.characterId) { PendingCharacter() }.requests++
    }

    @Synchronized
    fun isSuperseded(request: AccountSaveRequest): Boolean =
        state(request).modes.getOrDefault(request.snapshot.worldType, 0) > request.sequence

    @Synchronized
    fun writesIdentity(request: AccountSaveRequest): Boolean = state(request).identity <= request.sequence

    @Synchronized
    fun committed(request: AccountSaveRequest) {
        val state = state(request)
        state.modes[request.snapshot.worldType] = maxOf(state.modes.getOrDefault(request.snapshot.worldType, 0), request.sequence)
        state.identity = maxOf(state.identity, request.sequence)
    }

    @Synchronized
    fun complete(request: AccountSaveRequest): Boolean {
        if (request.completed) return false
        request.completed = true
        val state = state(request)
        check(state.requests > 0)
        if (--state.requests == 0) characters.remove(request.snapshot.characterId)
        return true
    }

    private fun state(request: AccountSaveRequest): PendingCharacter = characters.getValue(request.snapshot.characterId)

    private class PendingCharacter {
        var requests = 0
        var identity = 0L
        val modes = HashMap<WorldType, Long>()
    }
}
