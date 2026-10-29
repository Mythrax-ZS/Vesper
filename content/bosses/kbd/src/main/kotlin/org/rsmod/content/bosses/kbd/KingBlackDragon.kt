package org.rsmod.content.bosses.kbd

import jakarta.inject.Inject
import org.rsmod.api.bosses.dsl.*
import org.rsmod.api.bosses.runtime.BossDeps
import org.rsmod.api.bosses.runtime.BossPluginScript
import org.rsmod.api.bosses.spec.DamageExpr
import org.rsmod.api.bosses.spec.Effect
import org.rsmod.api.combat.commons.types.MeleeAttackType

class KingBlackDragon @Inject constructor(
    deps: BossDeps,
    private val breaths: KbdBreathDamage,
) : BossPluginScript(deps) {

    override val spec = boss("npc.king_dragon") {

        stats(attackRate = 4)

        val melee = ability("melee") {
            anim("seq.dragon_attack")
            hit {
                damage(Accuracy(npcMaxHit(MeleeAttackType.Stab), meleeAttackType = MeleeAttackType.Stab))
                type(Melee)
            }
        }

        val dragonFire = ability("dragonfire") {
            anim("seq.dragon_firebreath_all_attack")
            projectile(
                spotanim = "spotanim.dragon_ranged_fire_attack",
                travel = "projanim.dragonfire",
                hit = Effect.Hit(damage = DamageExpr.Custom { _, player -> breaths.roll(player, special = false) }, type = Typeless),
            )
        }

        val poisonBreath = ability("poison_breath") {
            anim("seq.dragon_firebreath_all_attack")
            projectile(
                spotanim = "spotanim.dragon_ranged_toxic_attack",
                travel = "projanim.dragonfire",
                hit = specialBreath(),
            )
            poison(damage = 8, chance = 1, outOf = 3)
        }

        val freezeBreath = ability("freeze_breath") {
            anim("seq.dragon_firebreath_all_attack")
            projectile(
                spotanim = "spotanim.dragon_ranged_ice_attack",
                travel = "projanim.dragonfire",
                hit = specialBreath(),
            )
            freeze(ticks = 10, chance = 1, outOf = 3)
        }

        val shockBreath = ability("shock_breath") {
            anim("seq.dragon_firebreath_all_attack")
            projectile(
                spotanim = "spotanim.dragon_ranged_lightning_attack",
                travel = "projanim.dragonfire",
                hit = specialBreath(),
            )
            statDrain {
                +stat("stat.attack") {
                    amount(1)
                    odds(1, 3)
                }
                +stat("stat.strength")
                +stat("stat.defence")
                +stat("stat.ranged")
                +stat("stat.magic")
                amount(2)
                odds(1, 3)
            }
        }

        phase("combat") {
            weightedSelectorRandom {
                +random(melee, weight = 3, requires = WithinMeleeRange)
                +random(dragonFire, weight = 3)
                +random(poisonBreath, weight = 1)
                +random(freezeBreath, weight = 1)
                +random(shockBreath, weight = 1)
            }
        }
    }

    private fun specialBreath() = Effect.Hit(
        damage = DamageExpr.Custom { _, player -> breaths.roll(player, special = true) },
        type = Typeless,
    )
}
