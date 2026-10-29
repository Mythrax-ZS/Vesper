package org.rsmod.content.other.special.weapons.melee

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.weapons.MeleeWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

class DualMacuahuitlWeapons @Inject constructor() : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register("obj.dual_macuahuitl", DualMacuahuitl(manager))
    }

    private class DualMacuahuitl(private val manager: WeaponAttackManager) : MeleeWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            manager.playWeaponFx(this, attack)
            val totalDamage = rollAndQueueHits(target, attack)
            manager.giveCombatXp(this, target, attack, totalDamage)
            manager.continueCombat(this, target)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            manager.playWeaponFx(this, attack)
            val totalDamage = rollAndQueueHits(target, attack)
            manager.giveCombatXp(this, target, attack, totalDamage)
            manager.continueCombat(this, target)
            return true
        }

        private fun ProtectedAccess.rollAndQueueHits(
            target: PathingEntity,
            attack: CombatAttack.Melee,
        ): Int {
            val wholeMax = manager.calculateMeleeMaxHit(this, target, attack.type, attack.style, 1.0)
            val firstMax = wholeMax / 2
            var previousAccurate = true
            var totalDamage = 0
            for ((index, hitMax) in intArrayOf(firstMax, wholeMax - firstMax).withIndex()) {
                // The second accuracy check is only attempted when the first check succeeded.
                // Damage can still become zero later through armour, immunity, or protection.
                val accurate = previousAccurate && manager.rollMeleeAccuracy(this, target, attack.type, attack.style, attack.type, 1.0)
                val damage =
                    if (accurate) manager.rollSuccessfulDamage(this, 0..hitMax, damagingAttack = wholeMax > 0)
                    else 0
                previousAccurate = accurate
                val hit = manager.queueMeleeHit(this, target, damage, delay = index + 1)
                totalDamage += if (target is Npc) hit.damage else damage
            }
            return totalDamage
        }
    }
}
