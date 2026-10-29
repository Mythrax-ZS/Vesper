package org.rsmod.api.player.bonus

import dev.openrune.util.Wearpos
import kotlin.math.max
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.hands
import org.rsmod.api.player.hat
import org.rsmod.api.player.legs
import org.rsmod.api.player.righthand
import org.rsmod.api.player.torso
import org.rsmod.api.player.worn.EquipmentChecks
import org.rsmod.game.entity.Player
import org.rsmod.game.type.getInvObj
import org.rsmod.game.type.getOrNull

public class WornBonuses {
    public fun strengthBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.meleeStr
    }

    public fun rangedStrengthBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.rangedStr
    }

    /**
     * Returns the player's base magic damage bonus.
     * This includes Elite Void's set bonus; [equipmentMagicDamageBonus] excludes that addition.
     *
     * **Note:** This does **not** include [Bonuses.magicDmgMultiplier] or
     * [Bonuses.magicDmgAdditive]. Those values are used for display purposes in the bonus
     * interface, but often have conditional restrictions that must be enforced in the combat
     * formulas themselves.
     *
     * For example, Virtus equipment displays a `+3%` magic damage bonus even when the player is not
     * on the Ancient spellbook - a restriction that should be applied in the actual combat formula.
     */
    public fun magicDamageBonusBase(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.magicDmg
    }

    /**
     * Returns only the equipped items' magic damage bonus, in tenths of a percent.
     *
     * This excludes Elite Void's set bonus and display-only modifiers. Combat formulas that
     * multiply or cap equipment bonuses can add [eliteVoidMagicDamageBonus] afterwards without
     * multiplying the set bonus. [magicDamageBonusBase] retains its existing combined result.
     */
    public fun equipmentMagicDamageBonus(player: Player): Int =
        calculate(player, applyShadowDisplay = false, includeEliteVoid = false).magicDmg

    /** Returns Elite Void's magic set bonus in tenths of a percent, independently of equipment. */
    public fun eliteVoidMagicDamageBonus(player: Player): Int =
        if (player.isWearingEliteMageVoid()) 50 else 0

    public fun offensiveStabBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.offStab
    }

    public fun offensiveSlashBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.offSlash
    }

    public fun offensiveCrushBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.offCrush
    }

    public fun offensiveRangedBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.offRange
    }

    public fun offensiveMagicBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.offMagic
    }

    /**
     * Returns equipment magic accuracy before Shadow's display multiplier, including negatives.
     *
     * Combat callers must apply attack-specific passives themselves. Merely holding a Shadow
     * does not establish whether the attack is its built-in spell, a manual spell, or PvP.
     */
    public fun offensiveMagicBonusBase(player: Player): Int =
        calculate(player, applyShadowDisplay = false, includeEliteVoid = false).offMagic

    public fun defensiveCrushBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.defCrush
    }

    public fun defensiveStabBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.defStab
    }

    public fun defensiveSlashBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.defSlash
    }

    public fun defensiveMagicBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.defMagic
    }

    public fun defensiveRangedBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.defRange
    }

    public fun prayerBonus(player: Player): Int {
        val bonuses = calculate(player)
        return bonuses.prayer
    }

    public fun calculate(player: Player): Bonuses =
        calculate(player, applyShadowDisplay = true, includeEliteVoid = true)

    private fun calculate(
        player: Player,
        applyShadowDisplay: Boolean,
        includeEliteVoid: Boolean,
    ): Bonuses {
        var offStab = 0
        var offSlash = 0
        var offCrush = 0
        var offMagic = 0
        var offRange = 0
        var defStab = 0
        var defSlash = 0
        var defCrush = 0
        var defRange = 0
        var defMagic = 0
        var meleeStr = 0
        var rangedStr = 0
        var magicDmg = 0
        var prayer = 0
        var undead = 0
        var slayer = 0
        var magicDmgAdditive = 0
        var magicDmgMultiplier = 1.0
        var undeadMeleeOnly = false
        var slayerMeleeOnly = false

        val weapon = getOrNull(player.righthand)

        val usingChargebow = weapon != null && weapon.isCategoryType("category.chargebow")
        val usingThrown = weapon != null && weapon.isCategoryType("category.throwing_weapon")
        val ignoreQuiverBonuses = usingChargebow || usingThrown

        for (wearpos in Wearpos.entries) {
            val obj = player.worn[wearpos.slot] ?: continue

            if (wearpos == Wearpos.Quiver && ignoreQuiverBonuses) {
                continue
            }

            val type = getInvObj(obj)
            offStab += type.param(params.attack_stab)
            offSlash += type.param(params.attack_slash)
            offCrush += type.param(params.attack_crush)
            offMagic += type.param(params.attack_magic)
            offRange += type.param(params.attack_ranged)
            defStab += type.param(params.defence_stab)
            defSlash += type.param(params.defence_slash)
            defCrush += type.param(params.defence_crush)
            defRange += type.param(params.defence_ranged)
            defMagic += type.param(params.defence_magic)
            meleeStr += type.param(params.melee_strength)
            rangedStr += type.param(params.ranged_strength)
            rangedStr += type.param(params.additive_ranged_strength)
            magicDmg += type.param(params.magic_damage)
            prayer += type.param(params.item_prayer_bonus)
            undead += type.param(params.bonus_undead_buff)
            slayer += type.param(params.bonus_slayer_buff)
            undeadMeleeOnly = type.param(params.bonus_undead_meleeonly)
            slayerMeleeOnly = type.param(params.bonus_slayer_meleeonly)
        }

        // TODO: Apply toxic blowpipe dart bonuses.

        if (applyShadowDisplay && EquipmentChecks.isTumekensShadow(player.righthand)) {
            // TODO: 4.0 while in tombs of amascut. This is purely for the visual bonus, the actual
            //  combat formula should use a separate resolved multiplier that matches.
            val multiplier = 3.0
            magicDmgMultiplier = multiplier

            // Legacy display transformation; combat uses offensiveMagicBonusBase and resolves
            // the passive for the actual attack and target separately.
            offMagic = (offMagic * multiplier).toInt()
        }

        val attackStyle = player.vars["varp.com_mode"]
        val usingDinhsBulwark = weapon != null && weapon.isCategoryType("category.dinhs_bulwark")
        if (usingDinhsBulwark && attackStyle == constants.dinhs_attackstyle_pummel) {
            val relativeDefenceBonuses = defStab + defSlash + defCrush + defRange
            val meleeStrIncrease = ((relativeDefenceBonuses - 800) / 12) - 38
            meleeStr += max(0, meleeStrIncrease)
        }

        // Note: The Virtus modifiers use `magicDmgAdditive`, which is applied only for visual
        // purposes. Although the modifier has an "Ancient spellbook" restriction in combat,
        // the bonus is always shown in the bonus interface regardless of that restriction.

        if (EquipmentChecks.isVirtusMask(player.hat)) {
            magicDmgAdditive += 3
        }

        if (EquipmentChecks.isVirtusRobeTop(player.torso)) {
            magicDmgAdditive += 3
        }

        if (EquipmentChecks.isVirtusRobeBottom(player.legs)) {
            magicDmgAdditive += 3
        }

        if (includeEliteVoid) {
            magicDmg += eliteVoidMagicDamageBonus(player)
        }

        // TODO: +10 off ranged and +1 ranged str with dizana's quiver.
        //  Verify if this is visible in equipment bonus interface. If it's not then it can be
        //  handled purely in combat formulae and not here.

        return Bonuses(
            offStab = offStab,
            offSlash = offSlash,
            offCrush = offCrush,
            offMagic = offMagic,
            offRange = offRange,
            defStab = defStab,
            defSlash = defSlash,
            defCrush = defCrush,
            defRange = defRange,
            defMagic = defMagic,
            meleeStr = meleeStr,
            rangedStr = rangedStr,
            magicDmg = magicDmg,
            prayer = prayer,
            undead = undead,
            slayer = slayer,
            magicDmgAdditive = magicDmgAdditive,
            magicDmgMultiplier = (magicDmgMultiplier * 10).toInt(),
            undeadMeleeOnly = undeadMeleeOnly,
            slayerMeleeOnly = slayerMeleeOnly,
        )
    }

    private fun Player.isWearingEliteMageVoid(): Boolean =
        EquipmentChecks.isVoidMageHelm(hat) &&
            EquipmentChecks.isEliteVoidTop(torso) &&
            EquipmentChecks.isEliteVoidRobe(legs) &&
            EquipmentChecks.isVoidGloves(hands)

    public data class Bonuses(
        val offStab: Int,
        val offSlash: Int,
        val offCrush: Int,
        val offMagic: Int,
        val offRange: Int,
        val defStab: Int,
        val defSlash: Int,
        val defCrush: Int,
        val defRange: Int,
        val defMagic: Int,
        val meleeStr: Int,
        val rangedStr: Int,
        val magicDmg: Int,
        val prayer: Int,
        val undead: Int,
        val slayer: Int,
        val magicDmgAdditive: Int,
        val magicDmgMultiplier: Int,
        val undeadMeleeOnly: Boolean,
        val slayerMeleeOnly: Boolean,
    ) {
        val multipliedMagicDmg: Int
            get() = magicDmg * (magicDmgMultiplier / 10)
    }
}
