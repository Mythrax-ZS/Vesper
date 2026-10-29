package org.rsmod.content.skills.magic.lunar

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.death.PlayerDeathCleanupHook
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList

/** Native UI bits share permanent storage with the spellbook; never reset their whole base varp. */
@Singleton
class VengeanceState @Inject constructor(
    private val players: PlayerList,
    private val restrictedAreas: Set<@JvmSuppressWildcards VengeanceRestrictedArea>,
) : PlayerDeathCleanupHook {
    private var Player.active by boolVarBit("varbit.vengeance_rebound")
    private var Player.cooldown by boolVarBit("varbit.vengeance_timelimit")
    private var Player.deadline by intVarp("varp.vengeance_cooldown_deadline")

    public fun isActive(player: Player): Boolean = player.active

    public fun activate(player: Player) { player.active = true }

    /** Called before emitting reflected damage, including zero-delay or recursive hit paths. */
    public fun consume(player: Player): Boolean {
        if (!player.active) return false
        player.active = false
        return true
    }

    public fun clearActive(player: Player) { player.active = false }

    public fun isCoolingDown(player: Player): Boolean = player.deadline > player.currentMapClock

    public fun startCooldown(player: Player) {
        player.deadline = player.currentMapClock + COOLDOWN_TICKS
        player.cooldown = true
    }

    public fun restricted(player: Player): Boolean = restrictedAreas.any { it.contains(player) }

    public fun tick() { for (player in players) tick(player) }

    public fun tick(player: Player) {
        // A pending save-mode swap is not a logout. Failed swaps must retain status and deadline.
        if (player.worldTypeSwitching) return
        if (player.active && restricted(player)) clearActive(player)
        if (player.deadline != 0 && !isCoolingDown(player)) {
            player.deadline = 0
            player.cooldown = false
        }
    }

    /** Session-reset policy; logout cooldown retention is not established by native UI metadata. */
    override fun cleanup(player: Player) {
        player.active = false
        player.cooldown = false
        player.deadline = 0
    }

    public companion object {
        public const val COOLDOWN_TICKS: Int = 50
    }
}
