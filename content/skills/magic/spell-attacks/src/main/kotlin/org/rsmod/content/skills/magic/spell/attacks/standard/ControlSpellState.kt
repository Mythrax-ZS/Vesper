package org.rsmod.content.skills.magic.spell.attacks.standard

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.combat.commons.CombatEffects
import org.rsmod.api.death.DEATH_KILLER_ATTR
import org.rsmod.api.death.PlayerDeathCleanupHook
import org.rsmod.api.death.PlayerDeathPreDropHook
import org.rsmod.api.player.death.DEATH_CAUSE_ATTR
import org.rsmod.api.player.death.DeathCause
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.player.vars.typePlayerUidVarp
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList

/** Session-only status. Native Tele Block UI reserves the final 100 ticks for immunity. */
@Singleton
class ControlSpellState @Inject constructor(
    private val players: PlayerList,
    private val areas: AreaChecker,
) : PlayerDeathPreDropHook, PlayerDeathCleanupHook {
    internal var Player.teleblockCycles by intVarBit("varbit.teleblock_cycles")
    private var Player.teleblockCasterUuidLow by intVarp("varp.standard_teleblock_caster_uuid_low")
    private var Player.teleblockCasterUuidHigh by intVarp("varp.standard_teleblock_caster_uuid_high")
    private val Player.teleblockCasterUuid: Long
        get() = (teleblockCasterUuidHigh.toLong() shl 32) or (teleblockCasterUuidLow.toLong() and 0xffffffffL)
    private var Player.teleblockLastTick by intVarp("varp.standard_teleblock_last_tick")
    private var Player.bindCaster by typePlayerUidVarp("varp.standard_bind_caster")
    private var Player.bindEndTick by intVarp("varp.standard_bind_end_tick")

    private val pendingBindings = mutableSetOf<PendingBinding>()

    internal class PendingBinding(val caster: Player, val target: PathingEntity) {
        var valid: Boolean = true
    }

    internal fun reserveBinding(caster: Player, target: PathingEntity): PendingBinding =
        PendingBinding(caster, target).also { pendingBindings += it }

    internal fun finishBinding(binding: PendingBinding) { pendingBindings.remove(binding) }

    internal fun invalidateNpc(npc: Npc) {
        pendingBindings.filter { it.target === npc }.forEach { it.valid = false }
    }

    internal fun canTeleblock(target: Player): Boolean = target.teleblockCycles == 0

    internal fun inWilderness(player: Player): Boolean = areas.inArea("area.wilderness", player.coords)

    internal fun teleblock(caster: Player, target: Player, duration: Int): Boolean {
        if (!canTeleblock(target) || !target.isValidTarget() || !inWilderness(target)) return false
        val casterUuid = checkNotNull(caster.uuid)
        target.teleblockCasterUuidLow = casterUuid.toInt()
        target.teleblockCasterUuidHigh = (casterUuid ushr 32).toInt()
        target.teleblockLastTick = target.currentMapClock
        target.teleblockCycles = duration + IMMUNITY_TICKS
        val seconds = duration * 3 / 5
        target.mes("A Tele Block has been cast on you by ${caster.displayName}. It will expire in ${seconds / 60} minutes and ${seconds % 60} seconds.")
        return true
    }

    internal fun bind(caster: Player, target: Player, duration: Int): Boolean {
        if (!CombatEffects.freeze(target, duration)) return false
        target.bindCaster = caster.uid
        target.bindEndTick = target.currentMapClock + duration
        return true
    }

    internal fun tick() {
        for (player in players) tick(player)
    }

    internal fun tick(player: Player) {
        // Account swaps quiesce gameplay while awaiting acknowledgment; a rejected swap must
        // not remove an existing status. Absolute deadlines still advance when processing resumes.
        if (player.worldTypeSwitching) return
        val cycles = player.teleblockCycles
        if (cycles > 0) {
            if (!player.isValidTarget() || !inWilderness(player)) {
                clearTeleblock(player)
            } else {
                val elapsed = (player.currentMapClock - player.teleblockLastTick).coerceAtLeast(0)
                if (elapsed > 0) {
                    val remaining = (cycles - elapsed).coerceAtLeast(0)
                    player.teleblockLastTick = player.currentMapClock
                    player.teleblockCycles = remaining
                    if (cycles > IMMUNITY_TICKS + WARNING_TICKS && remaining <= IMMUNITY_TICKS + WARNING_TICKS && remaining > IMMUNITY_TICKS) {
                        player.mes("Your Tele Block will expire in 30 seconds.")
                    }
                    if (cycles > IMMUNITY_TICKS && remaining <= IMMUNITY_TICKS) {
                        player.mes("Your Tele Block has expired.")
                        player.teleblockCasterUuidLow = 0
                        player.teleblockCasterUuidHigh = 0
                    }
                    if (remaining == 0) clearTeleblock(player)
                }
            }
        }
        if (player.bindEndTick != 0) {
            val caster = player.bindCaster?.resolve(players)
            if (!player.isFrozen || player.currentMapClock >= player.bindEndTick) {
                clearBindTracking(player)
            } else if (caster?.worldTypeSwitching == true) {
                // Successful swaps clean up through WorldTypeChanged; failure resumes this bind.
                return
            } else if (caster == null || !caster.isValidTarget() || caster.worldType != player.worldType ||
                caster.coords.level != player.coords.level || caster.coords.chebyshevDistance(player.coords) > BIND_DISTANCE) {
                CombatEffects.unfreeze(player)
                clearBindTracking(player)
            }
        }
    }

    /** Killing this caster only clears the killer's own block, never other victims' blocks. */
    override fun beforeDrops(player: Player) {
        val killer = (player.attr[DEATH_CAUSE_ATTR] as? DeathCause.ByPlayer)?.killer
            ?: player.attr[DEATH_KILLER_ATTR] ?: return
        if (killer.teleblockCycles > IMMUNITY_TICKS && killer.teleblockCasterUuid == player.uuid) {
            clearTeleblock(killer)
            killer.mes("Your Tele Block has been removed because you killed ${player.displayName}.")
        }
    }

    override fun cleanup(player: Player) {
        pendingBindings.filter { it.target === player || it.caster === player }.forEach { it.valid = false }
        clearTeleblock(player)
        if (player.bindEndTick != 0) {
            player.frozen = false
            player.freezeImmune = false
            player.clearTimer("timer.combat_freeze")
            player.clearTimer("timer.combat_freeze_immunity")
            clearBindTracking(player)
        }
    }

    private fun clearTeleblock(player: Player) {
        player.teleblockCycles = 0
        player.teleblockCasterUuidLow = 0
        player.teleblockCasterUuidHigh = 0
        player.teleblockLastTick = 0
    }

    private fun clearBindTracking(player: Player) {
        player.bindCaster = null
        player.bindEndTick = 0
    }

    internal companion object {
        const val IMMUNITY_TICKS = 100
        const val WARNING_TICKS = 50
        const val BIND_DISTANCE = 10
    }
}
