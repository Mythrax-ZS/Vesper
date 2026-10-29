package org.rsmod.content.skills.magic.spell.attacks.standard

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatEffects
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.MagicRuneManager.Companion.isFailure
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.spells.attack.SpellAttack
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackMap
import org.rsmod.api.spells.attack.SpellAttackRepository
import org.rsmod.api.table.StandardControlSpellsRow
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isType
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.game.type.getOrNull

/** Native requirements come from MagicSpell; outcome mechanics come from the server cache table. */
class StandardControlSpells @Inject constructor(
    private val runes: MagicRuneManager,
    private val random: GameRandom,
    private val worldQueues: WorldQueueList,
    private val state: ControlSpellState,
) : SpellAttackMap {
    override fun SpellAttackRepository.register(manager: SpellAttackManager) {
        for (row in StandardControlSpellsRow.all()) register(checkNotNull(RSCM.getReverseMapping(RSCMType.OBJ, row.spell.id)), ControlAttack(manager, row))
    }

    private inner class ControlAttack(
        private val manager: SpellAttackManager,
        private val row: StandardControlSpellsRow,
    ) : SpellAttack {
        private val teleblock = row.kind == "TELEBLOCK"

        override suspend fun ProtectedAccess.attack(target: Npc, attack: CombatAttack.Spell) {
            if (teleblock) {
                mes("This spell can only be cast on players.")
                manager.stopCombat(this)
                return
            }
            cast(target, attack)
        }

        override suspend fun ProtectedAccess.attack(target: Player, attack: CombatAttack.Spell) {
            cast(target, attack)
        }

        private fun ProtectedAccess.cast(target: PathingEntity, attack: CombatAttack.Spell) {
            val rejection = rejection(target)
            if (rejection != null) {
                mes(rejection)
                manager.stopCombat(this)
                return
            }
            // This family has outcome-specific cast XP, so do not also award native baseline XP.
            val payment = runes.attemptCast(player, attack.spell)
            if (payment.isFailure()) {
                manager.stopCombat(this)
                return
            }
            val staff = getOrNull(attack.weapon)?.isCategoryType("category.staff") == true
            player.anim(if (staff) row.staffAnimation else row.animation, priority = 6)
            row.launch?.let { spotanim(it, height = 92) }
            val splash = manager.rollSplash(this, target, attack, payment)
            statAdvance("stat.magic", (if (splash) row.missXpTenths else row.hitXpTenths) / 10.0)
            val travel = if (splash) row.missTravel ?: row.travel else row.travel
            val projectile = if (travel != null) {
                manager.spawnProjectile(this, target, travel, row.projectile)
            } else when (target) {
                is Npc -> ProjAnim.fromBoundsToNpc(player.bounds(), target, -1, row.projectile)
                is Player -> ProjAnim.fromBoundsToPlayer(player.bounds(), target, -1, row.projectile)
            }
            val (serverDelay, clientDelay) = projectile.durations
            if (splash) {
                manager.playSplashFx(this, target, clientDelay, row.castSound, soundRadius = 8)
                manager.queueSplashHit(this, target, attack.spell.obj, clientDelay, serverDelay)
            } else {
                manager.playHitFx(this, target, clientDelay, row.castSound, soundRadius = 8,
                    hitSpot = row.impact, hitSpotHeight = 0, hitSound = row.hitSound)
                if (teleblock) {
                    val victim = target as Player
                    val protected = victim.vars["varbit.prayer_protectfrommagic"] == 1
                    state.teleblock(player, victim, if (protected) row.duration / 2 else row.duration)
                } else {
                    val damage = if (row.maxHit == 0) 0 else manager.rollMaxHit(this, target, attack, payment, row.maxHit)
                    manager.giveCombatXp(this, target, attack, damage)
                    manager.queueMagicHit(this, target, attack.spell.obj, damage, clientDelay, serverDelay)
                    val bonus = swampbarkPieces(player) * row.swampbarkBonus
                    queueBind(player, target, row.duration + bonus, serverDelay)
                }
            }
            manager.continueCombatIfAutocast(this, target)
        }

        private fun ProtectedAccess.rejection(target: PathingEntity): String? {
            if (target is Player && (!target.isValidTarget() || target === player || player.worldType != target.worldType)) {
                return "You can't cast this spell on that target."
            }
            if (target is Npc && target.hitpoints <= 0) return "It is too late to cast that spell."
            if (teleblock) {
                val victim = target as? Player ?: return "This spell can only be cast on players."
                if (!state.inWilderness(player) || !state.inWilderness(victim)) return "You can't cast Tele Block here."
                if (!state.canTeleblock(victim)) return "That player is already teleblocked or temporarily immune."
            } else when (target) {
                is Player -> {
                    if (target.isFrozen) return "Your target is already held by a magical force."
                    if (target.freezeImmune) return "The target is currently immune to that spell."
                }
                is Npc -> {
                    if (CombatEffects.isFrozen(target)) return "Your target is already held by a magical force."
                    if (target.vars["varn.freeze_immunity_end_clock"] > target.currentMapClock) return "The target is currently immune to that spell."
                }
            }
            return null
        }
    }

    private fun queueBind(caster: Player, target: PathingEntity, duration: Int, delay: Int) {
        val casterUid = caster.uid
        val casterMode = caster.worldType
        val stillValid = when (target) {
            is Npc -> {
                val uid = target.uid
                val check: () -> Boolean = { target.uid == uid && target.isSlotAssigned && target.hitpoints > 0 }
                check
            }
            is Player -> {
                val uid = target.uid
                val mode = target.worldType
                val check: () -> Boolean = { target.uid == uid && target.worldType == mode && target.isValidTarget() }
                check
            }
        }
        val pending = state.reserveBinding(caster, target)
        fun complete() {
            if (!pending.valid || caster.uid != casterUid || caster.worldType != casterMode) {
                state.finishBinding(pending)
                return
            }
            // Global queues keep running while account swaps quiesce an individual player.
            // Keep the paid cast pending through an aborted swap; successful swaps invalidate it.
            if (caster.worldTypeSwitching || (target is Player && target.worldTypeSwitching)) {
                worldQueues.add(1, ::complete)
                return
            }
            state.finishBinding(pending)
            if (!caster.isValidTarget() || !stillValid()) return
            when (target) {
                is Player -> if (caster.coords.level == target.coords.level &&
                    caster.coords.chebyshevDistance(target.coords) <= ControlSpellState.BIND_DISTANCE) {
                    state.bind(caster, target, duration)
                }
                is Npc -> {
                    val resistance = target.visType.paramOrNull(params.freeze_resistance) ?: 0
                    if (resistance <= 0 || random.of(100) >= resistance) CombatEffects.freeze(target, duration)
                }
            }
        }
        worldQueues.add(delay, ::complete)
    }

    private fun swampbarkPieces(player: Player): Int = player.worn.objs.count { obj ->
        obj != null && (obj.isType("obj.swampbark_helm") || obj.isType("obj.swampbark_body") || obj.isType("obj.swampbark_legs"))
    }
}
