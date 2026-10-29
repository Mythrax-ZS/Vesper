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

class MultiHitMeleeWeapons @Inject constructor() : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        val weapon = GlacialTemotliFamily(manager)
        register("obj.glacial_temotli", weapon)
        register("obj.sulphur_blades", weapon)
        register("obj.earthbound_tecpatl", weapon)
        register("obj.barrows_torag_weapon", weapon)
        register("obj.barrows_torag_weapon_100", weapon)
        register("obj.barrows_torag_weapon_75", weapon)
        register("obj.barrows_torag_weapon_50", weapon)
        register("obj.barrows_torag_weapon_25", weapon)
    }

    private class GlacialTemotliFamily(private val manager: WeaponAttackManager) : MeleeWeapon {
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
            var totalDamage = 0
            for (hitMax in intArrayOf(firstMax, wholeMax - firstMax)) {
                val accurate = manager.rollMeleeAccuracy(this, target, attack.type, attack.style, attack.type, 1.0)
                val damage =
                    if (accurate) manager.rollSuccessfulDamage(this, 0..hitMax, damagingAttack = wholeMax > 0)
                    else 0
                val hit = manager.queueMeleeHit(this, target, damage)
                totalDamage += if (target is Npc) hit.damage else damage
            }
            return totalDamage
        }
    }
}
