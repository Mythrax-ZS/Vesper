package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.ObjEvents
import org.rsmod.api.player.hook.PlayerObjTakeValidator
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onProtectedEvent
import org.rsmod.api.script.onWorldTypeChanged
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.entity.player.ProtectedAccessLostException
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.obj.Obj
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class BirdSnareScript @Inject constructor(
    private val snares: BirdSnareManager,
    private val takeValidator: PlayerObjTakeValidator,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpHeld1(BirdSnares.SNARE) { laySnare(it.slot) }
        onProtectedEvent<ObjEvents.Ap4>(BirdSnares.item(BirdSnares.SNARE).id) {
            if (coords != it.obj.coords) {
                apRange(0)
                return@onProtectedEvent
            }
            layGroundSnare(it.obj)
        }
        onOpLoc1(BirdSnares.OPEN) { useSnare(LocInfo(it.loc.layer, it.loc.coords, it.loc.entity), SnareAction.DISMANTLE) }
        onOpLoc2(BirdSnares.OPEN) {
            val trap = snares.trapAt(it.loc.coords)
            mes(if (trap?.owner === player) "You have set this bird snare." else "This bird snare belongs to someone else.")
        }
        onOpLoc1(BirdSnares.BROKEN) { useSnare(LocInfo(it.loc.layer, it.loc.coords, it.loc.entity), SnareAction.DISMANTLE) }
        onOpLoc2(BirdSnares.BROKEN) { useSnare(LocInfo(it.loc.layer, it.loc.coords, it.loc.entity), SnareAction.RESET) }
        for (bird in BirdSnares.BIRDS) {
            onOpLoc1(bird.caught) { useSnare(LocInfo(it.loc.layer, it.loc.coords, it.loc.entity), SnareAction.CHECK) }
            onOpLoc2(bird.caught) { useSnare(LocInfo(it.loc.layer, it.loc.coords, it.loc.entity), SnareAction.RESET) }
        }
        onEvent<GameLifecycle.StartCycle> { snares.tick() }
        onEvent<NpcStateEvents.Delete> { snares.invalidateBird(npc) }
        onEvent<NpcStateEvents.Respawn> { snares.invalidateBird(npc) }
        onPlayerLogout { snares.logout(player) }
        onWorldTypeChanged { snares.logout(player) }
    }

    internal suspend fun ProtectedAccess.laySnare(slot: Int) {
        val expected = inv[slot] ?: return
        val tile = coords
        snares.lay(player, slot, expected, tile)?.let { report(it); return }
        anim(BirdSnares.LAY_ANIM)
    }

    internal fun ProtectedAccess.layGroundSnare(source: Obj) {
        snares.canLayGround(player, source)?.let { report(it); return }
        takeValidator.validate(player, source, BirdSnares.item(BirdSnares.SNARE))?.let { mes(it); return }
        snares.layGround(player, source)?.let { report(it); return }
        anim(BirdSnares.LAY_ANIM)
    }

    internal suspend fun ProtectedAccess.useSnare(loc: LocInfo, action: SnareAction) {
        snares.validate(player, loc, action)?.let { report(it); return }
        val expected = snares.trapAt(loc.coords)
        try {
            faceSquare(loc.coords)
            val animation = if (action == SnareAction.RESET) BirdSnares.LAY_ANIM else BirdSnares.PICKUP_ANIM
            anim(animation)
            waitSnareAnimation(animation)
            if (snares.trapAt(loc.coords) !== expected) return
            snares.interact(player, loc, action)?.let { report(it) }
        } finally {
            resetAnim()
        }
    }

    private suspend fun ProtectedAccess.waitSnareAnimation(sequence: String) {
        val finish = player.currentMapClock + BirdSnares.animationTicks(sequence)
        coroutine.pause { player.processedMapClock >= finish }
        if (player.activeCoroutine !== coroutine || player.isBusy) throw ProtectedAccessLostException()
    }

    private fun ProtectedAccess.report(failure: SnareFailure) {
        val message = when (failure) {
            SnareFailure.LEVEL -> "You need a higher Hunter level to do that."
            SnareFailure.LIMIT -> "You have already set as many traps as you can."
            SnareFailure.TILE -> "You can't set a bird snare here."
            SnareFailure.INPUT -> "You no longer have that bird snare."
            SnareFailure.OWNER -> "This bird snare belongs to someone else."
            SnareFailure.STALE -> "That bird snare is no longer available."
            SnareFailure.BUSY -> "You can't do that while the trap is catching a bird."
            SnareFailure.SPACE -> "You don't have enough inventory space to collect this trap."
        }
        mes(message)
    }
}
