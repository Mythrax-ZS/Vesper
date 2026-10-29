package org.rsmod.content.other.treasuretrails

import dev.openrune.ServerCacheManager
import dev.openrune.definition.type.widget.IfEvent
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.aconverted.interf.IfButtonOp
import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.invtx.invClear
import org.rsmod.api.player.output.UpdateInventory
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onIfClose
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.obj.Obj
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class BeginnerCasketScript @Inject constructor(
    private val random: GameRandom,
    private val objects: ObjRepository,
) : PluginScript() {
    override fun ScriptContext.startup() {
        BeginnerRewards.rows // Fail startup if the packed distribution is incomplete.
        onOpHeld1(BEGINNER_CASKET) {
            // Close any other reward screen before producing a new display snapshot.
            ifClose()
            if (player.invMap[BEGINNER_PENDING]?.isNotEmpty() == true) {
                if (!player.deliver()) mes("Your previous rewards could not be delivered yet.")
                return@onOpHeld1
            }
            val opened = player.openBeginnerCasket(it.slot, it.obj) { BeginnerRewards.roll(random) }
            val failure = opened.failure
            if (failure != null) {
                report(failure)
                return@onOpHeld1
            }
            val snapshot = player.storeBeginnerRewardPresentation(opened.rewards)
            player.deliver()
            mes("You have completed ${player.beginnerCasketsOpened} beginner Treasure Trails.")
            // Never insert already-delivered display loot in inv.trail_rewardinv. Barrows owns
            // that server container and transfers its contents to the player on logout.
            UpdateInventory.updateInvFull(player, player.beginnerRewardPresentation())
            ifOpenMainModal(REWARD_INTERFACE)
            ifSetEvents("component.trail_rewardscreen:items", 0 until snapshot.size, IfEvent.Op10)
        }
        onIfModalButton("component.trail_rewardscreen:items") {
            if (it.op == IfButtonOp.Op10) {
                objExamine(player.invMap.getOrPut(BEGINNER_DISPLAY), it.comsub)
            }
        }
        onIfClose(REWARD_INTERFACE) {
            player.invMap[BEGINNER_DISPLAY]?.let { player.invClear(it) }
        }
        onPlayerLogin { player.deliver() }
    }

    private fun Player.deliver(): Boolean = deliverBeginnerRewards { reward ->
        objects.add(Obj.fromOwner(this, coords, reward), lootDropDuration ?: constants.lootdrop_duration)
    }

    private fun ProtectedAccess.report(failure: CasketFailure) {
        when (failure) {
            CasketFailure.INPUT -> Unit
            CasketFailure.ENTRANA -> mes("You cannot open this on Entrana, the monks are watching you too closely.")
            CasketFailure.ACCOUNT_AGE_UNVERIFIED ->
                mes("Beginner casket rewards for accounts with less than ten hours played are not available yet. Your casket has been kept.")
            CasketFailure.PENDING -> mes("Your previous rewards must be delivered first.")
            CasketFailure.COUNTER_LIMIT -> mes("You have reached the Treasure Trail completion limit.")
            CasketFailure.SPACE -> mes("Your casket could not be opened. It has been kept.")
        }
    }

    private companion object { const val REWARD_INTERFACE = "interface.trail_rewardscreen" }
}

/** Detached native inventory packet source; intentionally absent from [Player.invMap]. */
internal fun Player.beginnerRewardPresentation(): Inventory {
    val type = checkNotNull(ServerCacheManager.getInventory(NATIVE_REWARD_INV.asRSCM()))
    val snapshot = invMap.getOrPut(BEGINNER_DISPLAY)
    return Inventory(type, snapshot.objs.copyOf())
}

/** Display-only values, never owned items: retain certificates and combine repeated reward rolls. */
internal fun Player.storeBeginnerRewardPresentation(rewards: List<InvObj>): Inventory {
    val snapshot = invMap.getOrPut(BEGINNER_DISPLAY)
    val combined = rewards.groupBy { it.id }.values.map { items ->
        val count = items.sumOf { it.count.toLong() }
        check(count in 1..Int.MAX_VALUE.toLong())
        items.first().copy(count = count.toInt())
    }
    check(combined.size <= snapshot.size)
    snapshot.fillNulls()
    combined.forEachIndexed { slot, item -> snapshot[slot] = item }
    return snapshot
}
