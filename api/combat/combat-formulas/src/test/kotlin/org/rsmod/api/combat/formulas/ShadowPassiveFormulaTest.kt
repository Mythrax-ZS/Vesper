package org.rsmod.api.combat.formulas

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import java.util.EnumSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.commons.styles.MagicAttackStyle
import org.rsmod.api.combat.formulas.accuracy.magic.PvNMagicAccuracy
import org.rsmod.api.combat.formulas.accuracy.magic.PvPMagicAccuracy
import org.rsmod.api.combat.formulas.attributes.CombatNpcAttributes
import org.rsmod.api.combat.formulas.attributes.CombatStaffAttributes
import org.rsmod.api.combat.formulas.attributes.collector.CombatMagicAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatNpcAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.magic.MagicMaxHitOperations
import org.rsmod.api.combat.formulas.maxhit.magic.PvNMagicMaxHit
import org.rsmod.api.combat.formulas.maxhit.magic.PvPMagicMaxHit
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class ShadowPassiveFormulaTest {
    @Test
    fun `native equipment is tripled before the accuracy offset and damage addition`() {
        val f = Fixture().smallLoadout()

        assertEquals(82, f.bonuses.offensiveMagicBonusBase(f.source))
        assertEquals(80, f.bonuses.equipmentMagicDamageBonus(f.source))
        assertEquals(34_100, f.staffAttackRoll())
        assertEquals(42, f.staffMax())
        assertEquals(42, f.source.vars["varp.com_maxhit"])
    }

    @Test
    fun `Augury is applied to level and added after the Shadow equipment damage multiplier`() {
        val f = Fixture().smallLoadout().augury()

        assertEquals(41_540, f.staffAttackRoll())
        assertEquals(43, f.staffMax())
    }

    @Test
    fun `manual fire surge keeps raw accuracy and damage in PvN and PvP`() {
        val f = Fixture().smallLoadout()

        assertEquals(15_768, f.spellAttackRoll())
        assertEquals(15_768, f.pvpSpellAttackRoll())
        assertEquals(0..25, f.spellMax())
        assertEquals(0..25, f.pvpSpellMax())
    }

    @Test
    fun `manual fire surge adds Augury without inheriting the powered passive`() {
        val f = Fixture().smallLoadout().augury()

        assertEquals(19_272, f.spellAttackRoll())
        assertEquals(19_272, f.pvpSpellAttackRoll())
        assertEquals(0..26, f.spellMax())
        assertEquals(0..26, f.pvpSpellMax())
    }

    @Test
    fun `PvP powered formula excludes the passive independently of attack permission`() {
        val f = Fixture().smallLoadout()

        assertEquals(16_060, f.pvpAccuracy.computeStaffAttackRoll(f.source, MagicAttackStyle.Accurate))
        assertEquals(36, f.pvpMax.getStaffMaxHit(f.source, f.opponent, 34, 1.0))
        f.augury()
        assertEquals(19_564, f.pvpAccuracy.computeStaffAttackRoll(f.source, MagicAttackStyle.Accurate))
        assertEquals(38, f.pvpMax.getStaffMaxHit(f.source, f.opponent, 34, 1.0))
    }

    @Test
    fun `uncharged weapon identity cannot enable the powered passive`() {
        val f = Fixture().smallLoadout()
        f.equip("obj.tumekens_shadow_uncharged")

        assertEquals(16_060, f.staffAttackRoll())
        assertEquals(36, f.staffMax())
        assertEquals(15_768, f.spellAttackRoll())
        assertEquals(0..25, f.spellMax())
    }

    @Test
    fun `negative native equipment accuracy is multiplied before adding sixty four`() {
        val f = Fixture().equip("obj.tumekens_shadow", "obj.rune_platebody", "obj.rune_platelegs")

        assertEquals(-16, f.bonuses.offensiveMagicBonusBase(f.source))
        assertEquals(1_760, f.staffAttackRoll())
        assertEquals(5_184, f.spellAttackRoll())
        assertEquals(5_280, f.pvpAccuracy.computeStaffAttackRoll(f.source, MagicAttackStyle.Accurate))

        f.equip("obj.rune_full_helm")
        assertEquals(-22, f.bonuses.offensiveMagicBonusBase(f.source))
        assertEquals(-220, f.staffAttackRoll())
        assertEquals(4_536, f.spellAttackRoll())
    }

    @Test
    fun `Elite Void is added once after equipment multiplication`() {
        val f = Fixture().eliteVoid()

        assertEquals(50, f.bonuses.equipmentMagicDamageBonus(f.source))
        assertEquals(50, f.bonuses.eliteVoidMagicDamageBonus(f.source))
        assertEquals(40, f.staffMax())
        assertEquals(0..26, f.spellMax())
        assertEquals(37, f.pvpMax.getStaffMaxHit(f.source, f.opponent, 34, 1.0))

        f.augury()
        assertEquals(42, f.staffMax())
        assertEquals(0..27, f.spellMax())
        assertEquals(38, f.pvpMax.getStaffMaxHit(f.source, f.opponent, 34, 1.0))
    }

    @Test
    fun `removing a required Void piece removes the set addition without removing equipment damage`() {
        val f = Fixture().eliteVoid()
        assertEquals(40, f.staffMax())

        val gloves = item("obj.pest_void_knight_gloves")
        f.source.worn[gloves.wearpos1] = null

        assertEquals(50, f.bonuses.equipmentMagicDamageBonus(f.source))
        assertEquals(0, f.bonuses.eliteVoidMagicDamageBonus(f.source))
        assertEquals(39, f.staffMax())
    }

    @Test
    fun `full native damage equipment remains below the ordinary threefold cap`() {
        val f = Fixture().fullLoadout()

        assertEquals(177, f.bonuses.offensiveMagicBonusBase(f.source))
        assertEquals(270, f.bonuses.equipmentMagicDamageBonus(f.source))
        assertEquals(65_450, f.staffAttackRoll())
        assertEquals(61, f.staffMax())
        f.augury()
        assertEquals(79_730, f.staffAttackRoll())
        assertEquals(62, f.staffMax())
    }

    @Test
    fun `fourfold formula branch uses native gear but does not establish ToA runtime context`() {
        val f = Fixture().smallLoadout()
        val context = staffAttributes(CombatStaffAttributes.TumekensShadow, CombatStaffAttributes.TumekensShadowInAmascut)

        assertEquals(43_120, f.staffAttackRoll(context))
        assertEquals(44, f.staffModifiedMax(context))
        f.augury()
        assertEquals(52_528, f.staffAttackRoll(context))
        assertEquals(46, f.staffModifiedMax(context))
    }

    @Test
    fun `fourfold formula caps native twenty seven percent equipment before Augury`() {
        val f = Fixture().fullLoadout()
        val context = staffAttributes(CombatStaffAttributes.TumekensShadow, CombatStaffAttributes.TumekensShadowInAmascut)

        assertEquals(84_920, f.staffAttackRoll(context))
        assertEquals(68, f.staffModifiedMax(context))
        f.augury()
        assertEquals(103_448, f.staffAttackRoll(context))
        assertEquals(69, f.staffModifiedMax(context))
    }

    @Test
    fun `equipment cap leaves prayer and Elite Void additions outside the cap`() {
        assertEquals(64, damage(gear = 300))
        assertEquals(65, damage(gear = 300, prayer = 40))
        assertEquals(68, damage(gear = 400))
        assertEquals(69, damage(gear = 400, prayer = 40))
        assertEquals(71, damage(gear = 400, void = 50, prayer = 40))
        assertEquals(24, damage(gear = -100))
    }

    @Test
    fun `Salve and Slayer are applied after the Shadow equipment cap`() {
        assertEquals(
            74,
            damage(
                gear = 400,
                staff = staffAttributes(CombatStaffAttributes.TumekensShadow, CombatStaffAttributes.SalveAmuletEi),
                npc = npcAttributes(CombatNpcAttributes.Undead),
            ),
        )
        assertEquals(
            78,
            damage(
                gear = 400,
                staff = staffAttributes(CombatStaffAttributes.TumekensShadow, CombatStaffAttributes.BlackMaskI),
                npc = npcAttributes(CombatNpcAttributes.SlayerTask),
            ),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy five argument operation preserves the equipment cap without Void`() {
        assertEquals(
            69,
            MagicMaxHitOperations.modifyStaffBaseDamage(
                34, 400, 40, staffAttributes(CombatStaffAttributes.TumekensShadow), npcAttributes(),
            ),
        )
    }

    private fun damage(
        gear: Int,
        void: Int = 0,
        prayer: Int = 0,
        staff: EnumSet<CombatStaffAttributes> = staffAttributes(CombatStaffAttributes.TumekensShadow),
        npc: EnumSet<CombatNpcAttributes> = npcAttributes(),
    ): Int = MagicMaxHitOperations.modifyStaffBaseDamage(
        baseDamage = 34,
        sourceEquipmentMagicDmgBonus = gear,
        sourceEliteVoidMagicDmgBonus = void,
        sourceMagicPrayerBonus = prayer,
        staffAttributes = staff,
        npcAttributes = npc,
    )

    private class Fixture {
        val source = player()
        val opponent = player()
        val bonuses = WornBonuses()
        private val npc = Npc("npc.king_dragon", CoordGrid(2271, 4695))
        private val magicAttributes = CombatMagicAttributeCollector()
        private val npcCollector = CombatNpcAttributeCollector()
        private val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        private val accuracy = PvNMagicAccuracy(NoRandom, bonuses, magicAttributes, npcCollector)
        val pvpAccuracy = PvPMagicAccuracy(NoRandom, bonuses, styles, magicAttributes)
        private val max = PvNMagicMaxHit(NoRandom, bonuses, npcCollector, magicAttributes)
        val pvpMax = PvPMagicMaxHit(NoRandom, bonuses, magicAttributes, DamageReductionAttributeCollector(styles))
        private val spell = item("obj.95_fire_surge")

        fun equip(vararg symbols: String): Fixture = apply {
            for (symbol in symbols) {
                val type = item(symbol)
                source.worn[type.wearpos1] = InvObj(type)
            }
        }

        fun smallLoadout(): Fixture = equip("obj.tumekens_shadow", "obj.occult_necklace", "obj.ancestral_robe_top")

        fun fullLoadout(): Fixture = equip(
            "obj.tumekens_shadow", "obj.occult_necklace", "obj.ancestral_hat", "obj.ancestral_robe_top",
            "obj.ancestral_robe_bottom", "obj.confliction_gauntlets", "obj.avernic_treads_magic",
            "obj.ma2_saradomin_cape", "obj.magus_ring",
        )

        fun eliteVoid(): Fixture = equip(
            "obj.tumekens_shadow", "obj.occult_necklace", "obj.game_pest_mage_helm",
            "obj.elite_void_knight_top", "obj.elite_void_knight_robes", "obj.pest_void_knight_gloves",
        )

        fun augury(): Fixture = apply { VarPlayerIntMapSetter.set(source, "varbit.prayer_augury", 1) }

        private fun targetAttributes(): EnumSet<CombatNpcAttributes> =
            npcCollector.collect(npc.visType, npc, npc.hitpoints, npc.baseHitpointsLvl, slayerTask = false)

        fun staffAttackRoll(context: EnumSet<CombatStaffAttributes> = magicAttributes.staffCollect(source, NoRandom)): Int =
            accuracy.computeStaffAttackRoll(source, MagicAttackStyle.Accurate, context, targetAttributes())

        fun staffMax(): Int = max.getStaffMaxHit(source, npc, 34, 1.0)

        fun staffModifiedMax(context: EnumSet<CombatStaffAttributes>): Int =
            max.computeStaffModifiedDamage(source, 34, context, targetAttributes())

        private fun spellAttributes() = magicAttributes.spellCollect(source, spell, Spellbook.Standard, false, NoRandom)

        fun spellAttackRoll(): Int = accuracy.computeSpellAttackRoll(source, 0, spellAttributes(), targetAttributes())

        fun pvpSpellAttackRoll(): Int = pvpAccuracy.computeSpellAttackRoll(source, spellAttributes())

        fun spellMax(): IntRange = max.getSpellMaxHit(source, npc, spell, Spellbook.Standard, 24, 5, false)

        fun pvpSpellMax(): IntRange = pvpMax.getSpellMaxHit(source, opponent, spell, Spellbook.Standard, 24, false)
    }

    private object NoRandom : GameRandom {
        override fun of(maxExclusive: Int): Int = error("Passive formula unexpectedly consumed RNG: $maxExclusive")
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Passive formula unexpectedly rolled damage")
        override fun randomDouble(): Double = error("Passive formula unexpectedly consumed floating-point RNG")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }

        private fun item(symbol: String): ItemServerType = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))

        private fun player(): Player = Player().apply {
            worldType = WorldType.MAIN
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            for (stat in listOf("stat.magic", "stat.defence", "stat.hitpoints")) {
                statMap.setBaseLevel(stat, 99)
                statMap.setCurrentLevel(stat, 99)
            }
        }

        private fun staffAttributes(vararg attrs: CombatStaffAttributes): EnumSet<CombatStaffAttributes> =
            EnumSet.noneOf(CombatStaffAttributes::class.java).apply { addAll(attrs) }

        private fun npcAttributes(vararg attrs: CombatNpcAttributes): EnumSet<CombatNpcAttributes> =
            EnumSet.noneOf(CombatNpcAttributes::class.java).apply { addAll(attrs) }
    }
}
