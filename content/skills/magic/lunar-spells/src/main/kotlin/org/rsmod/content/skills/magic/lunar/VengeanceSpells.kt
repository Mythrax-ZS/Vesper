package org.rsmod.content.skills.magic.lunar

import jakarta.inject.Inject
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.combat.commons.magic.MagicSpell
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.MagicRuneManager.Companion.isFailure
import org.rsmod.api.player.ironman.isAnyIronman
import org.rsmod.api.player.ironman.isSoloIronman
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.mapMultiway
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.output.soundSynth
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseDefenceLvl
import org.rsmod.api.player.stat.baseMagicLvl
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.game.entity.Player

/** Friendly Lunar casts: requirements and payment use the same native spell model as combat. */
class VengeanceSpells @Inject constructor(
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val state: VengeanceState,
    private val areas: AreaChecker,
    private val regions: RegionRegistry,
    private val aidPermissions: Set<@JvmSuppressWildcards VengeanceAidPermission>,
) {
    public fun castSelf(access: ProtectedAccess): Boolean {
        val player = access.player
        val spell = spell(SELF)
        if (!preflight(player, spell) || !canActivate(player, player)) return false
        if (runes.attemptCast(player, spell).isFailure()) return false
        state.activate(player)
        state.startCooldown(player)
        access.statAdvance("stat.magic", spell.castXp)
        // Native separates the movement-preserving animation from the PvP variant.
        val pvpArea = areas.inArea("area.wilderness", player.coords)
        player.anim(if (pvpArea) "seq.vengeance_spell_anim_stalling" else "seq.vengeance_spell_anim_nostalling")
        player.spotanim("spotanim.quest_lunar_spellbook_vengeance_spot_anim")
        player.soundSynth("synth.lunar_vengence")
        startBuff(player)
        return true
    }

    public fun castOther(access: ProtectedAccess, target: Player): Boolean {
        val caster = access.player
        val spell = spell(OTHER)
        if (!preflight(caster, spell) || !validTarget(caster, target)) return false
        if (!access.isWithinDistance(target, CAST_RANGE)) return false
        if (!canActivate(caster, target)) return false
        if (!acceptsAid(caster, target)) {
            caster.mes("That player is not accepting your aid.")
            return false
        }
        val singleWilderness =
            (areas.inArea("area.wilderness", caster.coords) && !caster.mapMultiway(areas)) ||
                (areas.inArea("area.wilderness", target.coords) && !target.mapMultiway(areas))
        if (singleWilderness) {
            caster.mes("You can't cast Vengeance Other in a single-way Wilderness area.")
            return false
        }
        if (runes.attemptCast(caster, spell).isFailure()) return false
        state.activate(target)
        // Native cooldown describes when this caster can cast again, not the recipient.
        state.startCooldown(caster)
        access.statAdvance("stat.magic", spell.castXp)
        caster.anim("seq.quest_lunar_pushing_magic_animation")
        target.spotanim("spotanim.quest_lunar_spellbook_vengeance_other_spot_anim")
        target.soundSynth("synth.lunar_vengence")
        target.mes("You have the power of vengeance!")
        startBuff(caster)
        return true
    }

    public fun spell(symbol: String): MagicSpell =
        spells.allSpells().first { it.obj.isType(symbol) }

    private fun preflight(player: Player, spell: MagicSpell): Boolean {
        if (!player.isValidTarget()) return false
        if (!runes.canCastSpell(player, spell)) return false
        if (player.baseMagicLvl < 65 || player.baseDefenceLvl < 40) {
            player.mes("You need a base Magic level of 65 and Defence level of 40 to cast Lunar spells.")
            return false
        }
        if (state.isCoolingDown(player)) {
            player.mes("You can only cast Vengeance once every 30 seconds.")
            return false
        }
        return true
    }

    private fun canActivate(caster: Player, target: Player): Boolean {
        if (state.restricted(caster) || state.restricted(target)) {
            caster.mes("You can't cast Vengeance here.")
            return false
        }
        // Preserve an existing charge. Exact live recast/payment behavior remains unverified.
        if (state.isActive(target)) {
            caster.mes(if (caster === target) "You already have the power of vengeance." else "That player already has the power of vengeance.")
            return false
        }
        return true
    }

    private fun acceptsAid(caster: Player, target: Player): Boolean {
        if (target.vars["varbit.option_acceptaid"] == 0 || target.isSoloIronman) return false
        return !target.isAnyIronman || aidPermissions.any { it.permits(caster, target) }
    }

    private fun validTarget(caster: Player, target: Player): Boolean {
        if (caster === target || !target.isValidTarget() || caster.worldType != target.worldType ||
            caster.coords.level != target.coords.level
        ) return false
        if (!RegionRegistry.inWorkingArea(caster.coords) && !RegionRegistry.inWorkingArea(target.coords)) return true
        val instance = regions[caster.coords] ?: return false
        return instance === regions[target.coords]
    }

    private fun startBuff(player: Player) {
        player.runClientScript(
            BUFF_BAR_START_CLIENTSCRIPT,
            VENGEANCE_COOLDOWN_STRUCT,
            player.currentMapClock,
        )
    }

    public companion object {
        public const val SELF: String = "obj.94_vengeance"
        public const val OTHER: String = "obj.93_vengeance_other"

        // Existing native spell-target approach range; exact Other-specific range is unverified.
        public const val CAST_RANGE: Int = 10
        private const val BUFF_BAR_START_CLIENTSCRIPT: Int = 5931
        private const val VENGEANCE_COOLDOWN_STRUCT: Int = 3084
    }
}
