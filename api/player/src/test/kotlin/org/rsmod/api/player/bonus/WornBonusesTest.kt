package org.rsmod.api.player.bonus

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.config.refs.params
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.world.WorldType

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class WornBonusesTest {
    private val bonuses = WornBonuses()

    @Test
    fun `charged Shadow keeps legacy display bonuses separate from raw combat inputs`() {
        val player = player()
        val gear = player.equip("obj.tumekens_shadow", "obj.occult_necklace", "obj.ancestral_robe_top")
        val rawAccuracy = gear.sumOf { it.param(params.attack_magic) }
        val rawDamage = gear.sumOf { it.param(params.magic_damage) }
        assertTrue(rawAccuracy > 0)
        assertTrue(rawDamage > 0)

        assertEquals(rawAccuracy, bonuses.offensiveMagicBonusBase(player))
        assertEquals(rawDamage, bonuses.equipmentMagicDamageBonus(player))
        assertEquals(0, bonuses.eliteVoidMagicDamageBonus(player))
        assertEquals(rawDamage, bonuses.magicDamageBonusBase(player))

        // Generic UI callers retain their established result. Combat chooses whether the
        // built-in cast is eligible; manual spells and PvP can use the unmultiplied inputs.
        val display = bonuses.calculate(player)
        assertEquals(rawAccuracy * 3, display.offMagic)
        assertEquals(display.offMagic, bonuses.offensiveMagicBonus(player))
        assertEquals(30, display.magicDmgMultiplier)
        assertEquals(rawDamage * 3, display.multipliedMagicDmg)
    }

    @Test
    fun `uncharged Shadow and ordinary staves expose native equipment values without a display multiplier`() {
        for (weapon in listOf("obj.tumekens_shadow_uncharged", "obj.staff_of_air")) {
            val player = player()
            val gear = player.equip(weapon, "obj.occult_necklace", "obj.ancestral_robe_top")
            val accuracy = gear.sumOf { it.param(params.attack_magic) }
            val damage = gear.sumOf { it.param(params.magic_damage) }

            assertEquals(accuracy, bonuses.offensiveMagicBonusBase(player), weapon)
            assertEquals(accuracy, bonuses.offensiveMagicBonus(player), weapon)
            assertEquals(damage, bonuses.equipmentMagicDamageBonus(player), weapon)
            assertEquals(damage, bonuses.magicDamageBonusBase(player), weapon)
            assertEquals(10, bonuses.calculate(player).magicDmgMultiplier, weapon)
        }
    }

    @Test
    fun `negative native magic accuracy is preserved without truncation or clamping`() {
        val player = player()
        val gear = player.equip("obj.tumekens_shadow", "obj.rune_platebody", "obj.rune_platelegs", "obj.rune_full_helm")
        val rawAccuracy = gear.sumOf { it.param(params.attack_magic) }
        assertTrue(rawAccuracy < 0, "Heavy armour must outweigh this weapon's magic accuracy")

        assertEquals(rawAccuracy, bonuses.offensiveMagicBonusBase(player))
        assertEquals(rawAccuracy * 3, bonuses.calculate(player).offMagic)
        assertEquals(rawAccuracy * 3, bonuses.offensiveMagicBonus(player))
    }

    @Test
    fun `Elite Void set damage is separate from equipment while old combined bonus stays intact`() {
        for (weapon in listOf("obj.tumekens_shadow", "obj.staff_of_air")) {
            val player = player()
            val gear = player.equip(weapon, "obj.occult_necklace", *ELITE_MAGE_VOID)
            val equipmentDamage = gear.sumOf { it.param(params.magic_damage) }
            val rawAccuracy = gear.sumOf { it.param(params.attack_magic) }

            assertEquals(equipmentDamage, bonuses.equipmentMagicDamageBonus(player))
            assertEquals(50, bonuses.eliteVoidMagicDamageBonus(player))
            assertEquals(equipmentDamage + 50, bonuses.magicDamageBonusBase(player))
            assertEquals(rawAccuracy, bonuses.offensiveMagicBonusBase(player))
            assertEquals(equipmentDamage + 50, bonuses.calculate(player).magicDmg)
            val displayMultiplier = if (weapon == "obj.tumekens_shadow") 3 else 1
            assertEquals((equipmentDamage + 50) * displayMultiplier, bonuses.calculate(player).multipliedMagicDmg)
        }
    }

    @Test
    fun `each required Elite Void piece must remain equipped for its set bonus`() {
        for (missing in ELITE_MAGE_VOID) {
            val player = player()
            val gear = player.equip("obj.tumekens_shadow", "obj.occult_necklace", *ELITE_MAGE_VOID)
            val removed = type(missing)
            player.worn[removed.wearpos1] = null
            val equipmentDamage = gear.filter { it.id != removed.id }.sumOf { it.param(params.magic_damage) }

            assertEquals(0, bonuses.eliteVoidMagicDamageBonus(player), missing)
            assertEquals(equipmentDamage, bonuses.equipmentMagicDamageBonus(player), missing)
            assertEquals(equipmentDamage, bonuses.magicDamageBonusBase(player), missing)
        }
    }

    @Test
    fun `regular Void and the wrong helmet do not grant Elite Mage Void damage`() {
        val regular = player()
        regular.equip("obj.game_pest_mage_helm", "obj.pest_void_knight_top", "obj.pest_void_knight_robes", "obj.pest_void_knight_gloves")
        assertEquals(0, bonuses.eliteVoidMagicDamageBonus(regular))

        val wrongHelmet = player()
        wrongHelmet.equip(*ELITE_MAGE_VOID)
        wrongHelmet.equip("obj.game_pest_archer_helm")
        assertEquals(0, bonuses.eliteVoidMagicDamageBonus(wrongHelmet))

        val protectedSet = player()
        protectedSet.equip("obj.game_pest_mage_helm_trouver", "obj.elite_void_knight_top_trouver", "obj.elite_void_knight_robes_trouver", "obj.pest_void_knight_gloves_trouver")
        assertEquals(50, bonuses.eliteVoidMagicDamageBonus(protectedSet))
    }

    @Test
    fun `Virtus display only additive bonus is excluded from equipment combat damage`() {
        val player = player()
        val gear = player.equip("obj.tumekens_shadow", "obj.virtus_mask", "obj.virtus_top", "obj.virtus_legs")
        val equipmentDamage = gear.sumOf { it.param(params.magic_damage) }

        assertEquals(equipmentDamage, bonuses.equipmentMagicDamageBonus(player))
        assertEquals(equipmentDamage, bonuses.magicDamageBonusBase(player))
        assertEquals(0, bonuses.eliteVoidMagicDamageBonus(player))
        assertEquals(9, bonuses.calculate(player).magicDmgAdditive)
    }

    @Test
    fun `shared aggregation still excludes quiver bonuses for thrown weapons and chargebows`() {
        val arrow = type("obj.rune_arrow")
        assertTrue(arrow.param(params.ranged_strength) > 0)
        for (weapon in listOf("obj.rune_dart", "obj.crystal_bow")) {
            val player = player()
            val weaponType = player.equip(weapon).single()
            assertTrue(weaponType.isCategoryType("category.throwing_weapon") || weaponType.isCategoryType("category.chargebow"))
            val withoutQuiver = bonuses.calculate(player)
            val rawMagic = bonuses.offensiveMagicBonusBase(player)
            val equipmentMagic = bonuses.equipmentMagicDamageBonus(player)
            player.worn[arrow.wearpos1] = InvObj(arrow, 50)

            assertEquals(withoutQuiver, bonuses.calculate(player), weapon)
            assertEquals(withoutQuiver.rangedStr, bonuses.rangedStrengthBonus(player), weapon)
            assertEquals(rawMagic, bonuses.offensiveMagicBonusBase(player), weapon)
            assertEquals(equipmentMagic, bonuses.equipmentMagicDamageBonus(player), weapon)
        }

        val bow = player()
        bow.equip("obj.magic_shortbow")
        val withoutArrows = bonuses.rangedStrengthBonus(bow)
        bow.worn[arrow.wearpos1] = InvObj(arrow, 50)
        assertEquals(withoutArrows + arrow.param(params.ranged_strength) + arrow.param(params.additive_ranged_strength), bonuses.rangedStrengthBonus(bow))
    }

    @Test
    fun `empty equipment has zero raw and set bonuses with the unchanged display defaults`() {
        val player = player()

        assertEquals(0, bonuses.offensiveMagicBonusBase(player))
        assertEquals(0, bonuses.equipmentMagicDamageBonus(player))
        assertEquals(0, bonuses.eliteVoidMagicDamageBonus(player))
        assertEquals(0, bonuses.magicDamageBonusBase(player))
        assertEquals(10, bonuses.calculate(player).magicDmgMultiplier)
    }

    private fun Player.equip(vararg symbols: String): List<ItemServerType> = symbols.map { symbol ->
        type(symbol).also { worn[it.wearpos1] = InvObj(it) }
    }

    private fun player(): Player = Player().apply {
        worldType = WorldType.MAIN
        inv = invMap.getOrPut("inv.inv")
        worn = invMap.getOrPut("inv.worn")
    }

    companion object {
        private val ELITE_MAGE_VOID = arrayOf(
            "obj.game_pest_mage_helm", "obj.elite_void_knight_top", "obj.elite_void_knight_robes", "obj.pest_void_knight_gloves",
        )

        private fun type(symbol: String): ItemServerType = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))

        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
