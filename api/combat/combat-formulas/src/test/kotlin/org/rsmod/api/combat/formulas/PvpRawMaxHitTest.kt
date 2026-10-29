package org.rsmod.api.combat.formulas

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.formulas.attributes.collector.CombatMagicAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatMeleeAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatRangedAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.magic.PvPMagicMaxHit
import org.rsmod.api.combat.formulas.maxhit.melee.PvPMeleeMaxHit
import org.rsmod.api.combat.formulas.maxhit.ranged.PvPRangedMaxHit
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.random.GameRandom
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.plugin.scripts.ScriptContext

@ResourceLock("ServerCacheManager")
class PvpRawMaxHitTest {
    @Test
    fun `all four real PvP maxima ignore target Elysian and never roll its proc`() {
        val source = player()
        val target = player()
        val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        val bonuses = WornBonuses()
        val reductions = DamageReductionAttributeCollector(styles)
        val speeds = WeaponSpeeds(styles)
        val melee = PvPMeleeMaxHit(NoRandom, bonuses, speeds, mockk(), CombatMeleeAttributeCollector(), reductions)
        val ranged = PvPRangedMaxHit(NoRandom, bonuses, speeds, CombatRangedAttributeCollector(), reductions)
        val magic = PvPMagicMaxHit(NoRandom, bonuses, CombatMagicAttributeCollector(), reductions)
        val spell = checkNotNull(ServerCacheManager.getItem("obj.01_wind_strike".asRSCM()))
        fun maxima(): List<Int> = listOf(
            melee.getMaxHit(source, target, MeleeAttackType.Stab, MeleeAttackStyle.Aggressive, 1.0),
            ranged.getMaxHit(source, target, RangedAttackType.Standard, RangedAttackStyle.Accurate, 1.0, 0),
            magic.getStaffMaxHit(source, target, 20, 1.0),
            magic.getSpellMaxHit(source, target, spell, Spellbook.Standard, 20, false).last,
        )

        val withoutShield = maxima()
        assertEquals(listOf(11, 11, 20, 20), withoutShield)
        val shield = checkNotNull(ServerCacheManager.getItem("obj.elysian".asRSCM()))
        target.worn[shield.wearpos1] = InvObj(shield)
        repeat(10) { assertEquals(withoutShield, maxima()) }
        assertEquals(0..20, magic.getSpellMaxHit(source, target, spell, Spellbook.Standard, 20, false))
    }

    @Test
    fun `raw PvP maxima still respond to source stats and spell base damage`() {
        val source = player()
        val target = player()
        val bonuses = WornBonuses()
        val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        val reductions = DamageReductionAttributeCollector(styles)
        val melee = PvPMeleeMaxHit(NoRandom, bonuses, WeaponSpeeds(styles), mockk(), CombatMeleeAttributeCollector(), reductions)
        val ranged = PvPRangedMaxHit(NoRandom, bonuses, WeaponSpeeds(styles), CombatRangedAttributeCollector(), reductions)
        val magic = PvPMagicMaxHit(NoRandom, bonuses, CombatMagicAttributeCollector(), reductions)
        val shield = checkNotNull(ServerCacheManager.getItem("obj.elysian".asRSCM()))
        target.worn[shield.wearpos1] = InvObj(shield)
        source.statMap.setCurrentLevel("stat.strength", 1)
        source.statMap.setCurrentLevel("stat.ranged", 1)

        assertTrue(melee.getMaxHit(source, target, MeleeAttackType.Stab, MeleeAttackStyle.Aggressive, 1.0) < 11)
        assertTrue(ranged.getMaxHit(source, target, RangedAttackType.Standard, RangedAttackStyle.Accurate, 1.0, 0) < 11)
        assertEquals(0, magic.getStaffMaxHit(source, target, 0, 1.0))
        assertEquals(3, magic.getStaffMaxHit(source, target, 3, 1.0))
    }

    private fun player(): Player = Player().apply {
        worldType = WorldType.MAIN
        inv = invMap.getOrPut("inv.inv")
        worn = invMap.getOrPut("inv.worn")
        for (stat in listOf("stat.strength", "stat.ranged", "stat.magic", "stat.hitpoints")) {
            statMap.setBaseLevel(stat, 99)
            statMap.setCurrentLevel(stat, 99)
        }
    }

    private object NoRandom : GameRandom {
        override fun of(maxExclusive: Int): Int = error("Maximum calculation consumed RNG bound $maxExclusive")
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Maximum calculation consumed range RNG")
        override fun randomDouble(): Double = error("Maximum calculation consumed double RNG")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
