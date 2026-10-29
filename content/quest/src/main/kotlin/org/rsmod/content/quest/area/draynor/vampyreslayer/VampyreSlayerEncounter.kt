package org.rsmod.content.quest.area.draynor.vampyreslayer

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import jakarta.inject.Inject
import org.rsmod.api.death.NpcAttackValidateHook
import org.rsmod.api.death.NpcAttackValidateResult
import org.rsmod.api.enums.NamedEnums.vampyre_slayer_hammers
import org.rsmod.api.invtx.invDel
import org.rsmod.api.npc.heal
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.npc.owner.assignSpawnOwner
import org.rsmod.api.npc.owner.isSpawnOwnedBy
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onNpcHit
import org.rsmod.api.script.onNpcQueue
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onPlayerCoordsChanged
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.interact.InteractionNpcOp
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class VampyreSlayerEncounter
@Inject
constructor(
    private val script: VampyreSlayer,
    private val npcRepo: NpcRepository,
    private val locRepo: LocRepository,
    private val npcs: NpcList,
    private val players: PlayerList,
    private val interactions: AiPlayerInteractions,
    private val launcher: ProtectedAccessLauncher,
) : PluginScript(), NpcAttackValidateHook {
    override fun ScriptContext.startup() {
        val count = checkNotNull(ServerCacheManager.getNpc(CountDraynor.asRSCM()))
        onOpLoc1("loc.vampcoffin") { openCoffin(it.loc) }
        onOpLoc1("loc.garliccupboardshut") {
            arriveDelay()
            locRepo.change(it.loc, "loc.garliccupboardopen", Int.MAX_VALUE)
            mes("You open the cupboard.")
        }
        onOpLoc1("loc.garliccupboardopen") {
            arriveDelay()
            if (invAdd(inv, Garlic).success) mes("You take a clove of garlic.")
        }
        onOpLoc2("loc.garliccupboardopen") {
            arriveDelay()
            locRepo.change(it.loc, "loc.garliccupboardshut", Int.MAX_VALUE)
            mes("You close the cupboard.")
        }

        onNpcHit(count) {
            if (npc.hitpoints != 0) return@onNpcHit
            val player = if (hit.isFromPlayer) hit.resolvePlayerSource(players) else npc.findHero(players)
            if (player == null || !commitStake(player, npc)) {
                npc.clearQueue("queue.death")
                npc.heal(npc.baseHitpointsLvl, showHitsplat = true)
            }
        }
        onNpcQueue(count, "queue.death") {
            // This quest NPC has no loot or ordinary respawn. A committed reward is persisted on
            // the player before this coroutine can suspend or the NPC can be removed.
            npc.noneMode()
            npc.hideAllOps()
            npcRepo.del(npc, Int.MAX_VALUE)
        }
        onPlayerSoftTimer(VampyreSettlementTimer) {
            if (player.vampyreCompletionPending == 0) {
                player.clearSoftTimer(VampyreSettlementTimer)
            } else {
                launcher.launch(player) { script.settle(this) }
            }
        }
        onPlayerLogin {
            if (player.vampyreCompletionPending != 0) player.softTimer(VampyreSettlementTimer, 1)
        }
        onPlayerLogout { removeOwnedCounts(player) }
        onPlayerCoordsChanged {
            // Leaving the normal cellar or dying must not strand a quest-owned attacker in the world.
            for (npc in ownedCounts(player).toList()) {
                if (player.coords.mx != npc.spawnCoords.mx || player.coords.mz != npc.spawnCoords.mz ||
                    player.coords.level != npc.spawnCoords.level) npcRepo.del(npc, Int.MAX_VALUE)
            }
        }
    }

    override fun validate(player: Player, npc: Npc): NpcAttackValidateResult {
        if (!npc.isType(CountDraynor)) return NpcAttackValidateResult.Pass
        if (!script.quest.isQuestInProgress(player) || player.vampyreCompletionPending != 0) {
            return NpcAttackValidateResult.Deny("You have no reason to fight this vampyre.")
        }
        // The native auto-attack action checks garlic even while waiting for its attack delay.
        // Spell-on-NPC is a different interaction and deliberately does not apply this debuff.
        val attack = player.interaction as? InteractionNpcOp
        if (attack?.target === npc && attack.op == InteractionOp.Op2 && player.inv.contains(Garlic)) {
            if (weakenWithGarlic(npc)) player.mes("The vampyre seems to be weakened by the garlic you're carrying.")
        }
        return NpcAttackValidateResult.Pass
    }

    private suspend fun ProtectedAccess.openCoffin(coffin: BoundLocInfo) {
        arriveDelay()
        if (!script.quest.isQuestInProgress(player) || player.vampyreCompletionPending != 0) {
            mes("The coffin is sealed shut.")
            return
        }
        if (ownedCounts(player).any()) {
            mes("The vampyre is already awake.")
            return
        }
        val opening = checkNotNull(ServerCacheManager.getAnim("seq.vampireslayer_coffin_open".asRSCM()))
        locRepo.change(coffin, "loc.vampcoffin_animated", opening.tickDuration.coerceAtLeast(1))
        soundSynth("synth.coffin_open")
        delay(opening)

        if (!script.quest.isQuestInProgress(player) || player.vampyreCompletionPending != 0 || ownedCounts(player).any()) return

        // Spawn in the shared cellar. Each opener can awaken a Count; this is not an instance.
        val count = Npc(CountDraynor, coffin.countEmergenceTile())
        count.assignSpawnOwner(player, mapClock)
        npcRepo.add(count, Int.MAX_VALUE)
        count.respawns = false
        count.regenClock = count.regenRate
        count.opPlayer2(player, interactions)
    }

    internal fun commitStake(player: Player, npc: Npc): Boolean {
        if (!npc.isType(CountDraynor) || npc.hitpoints != 0 || !script.quest.isQuestInProgress(player)) return false
        if (player.vampyreCompletionPending != 0) return false
        if (!player.inv.contains(Stake)) {
            player.mes("<col=ff0000>The vampyre seems to regenerate!</col>")
            return false
        }
        if (!hasHammer(player)) {
            player.mes("<col=ff0000>You're unable to push the stake far enough in!</col>")
            player.mes("<col=ff0000>The vampyre seems to regenerate!</col>")
            return false
        }
        if (player.invDel(player.inv, Stake.asRSCM(), 1).failure) return false
        player.vampyreCompletionPending = 1
        player.softTimer(VampyreSettlementTimer, 1)
        player.anim("seq.human_stake3")
        player.mes("<col=ff0000>You hammer the stake into the vampyre's chest!</col>")
        npc.noneMode()
        npc.hideAllOps()
        return true
    }

    private fun ownedCounts(player: Player): Sequence<Npc> =
        npcs.asSequence().filter { it.isType(CountDraynor) && it.isSpawnOwnedBy(player) && it.isSlotAssigned }

    private fun removeOwnedCounts(player: Player) {
        ownedCounts(player).toList().forEach { npcRepo.del(it, Int.MAX_VALUE) }
    }

    internal companion object {
        fun hasHammer(player: Player): Boolean =
            vampyre_slayer_hammers.backing.values.any { item ->
                item != null && (player.inv.contains(item) || player.worn.contains(item))
            }

        /** Each stat has its own full-stat guard; garlic is never consumed and never slows regen. */
        fun weakenWithGarlic(npc: Npc): Boolean {
            val message = npc.attackLvl == npc.baseAttackLvl
            if (message) npc.attackLvl = (npc.attackLvl - 10).coerceAtLeast(0)
            if (npc.strengthLvl == npc.baseStrengthLvl) npc.strengthLvl = (npc.strengthLvl - 10).coerceAtLeast(0)
            if (npc.defenceLvl == npc.baseDefenceLvl) npc.defenceLvl = (npc.defenceLvl - 40).coerceAtLeast(0)
            if (npc.hitpoints == npc.baseHitpointsLvl) npc.hitpoints = (npc.hitpoints - 10).coerceAtLeast(0)
            return message
        }
    }
}

internal fun BoundLocInfo.countEmergenceTile() = coords.translateZ(-2)
