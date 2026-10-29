package org.rsmod.api.combat.formulas

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.mockk
import java.util.ArrayDeque
import kotlin.math.ceil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.combat.commons.styles.AttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.magic.NvNMagicMaxHit
import org.rsmod.api.combat.formulas.maxhit.magic.NvPMagicMaxHit
import org.rsmod.api.combat.formulas.maxhit.melee.NvNMeleeMaxHit
import org.rsmod.api.combat.formulas.maxhit.melee.NvPMeleeMaxHit
import org.rsmod.api.combat.formulas.maxhit.ranged.NvNRangedMaxHit
import org.rsmod.api.combat.formulas.maxhit.ranged.NvPRangedMaxHit
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.refs.done.hitmark_groups
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.hit.HitDefenceType
import org.rsmod.game.hit.HitType
import org.rsmod.game.hit.NpcDamageContext
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@ResourceLock("ServerCacheManager")
class NpcIncomingDamageReducerTest {
    @Test
    fun `Elysian rolls once per positive sampled hit with seven proc slots`() {
        val fixture = Fixture()
        fixture.equip("obj.elysian")
        val results = (0..9).map { roll ->
            fixture.random.enqueue(roll)
            fixture.reduce(100)
        }
        assertEquals(List(7) { 75 } + List(3) { 100 }, results)
        assertEquals(List(10) { 10 }, fixture.random.bounds)
        fixture.random.assertExhausted()
    }

    @Test
    fun `misses absent context and ineligible equipment do not consume proc rolls`() {
        val fixture = Fixture()
        fixture.equip("obj.elysian")
        assertEquals(0, fixture.reduce(0))
        assertEquals(100, fixture.reduce(100, context = null))
        assertEquals(100, fixture.reduce(100, context = context(equipment = false)))
        assertEquals(100, fixture.reduce(100, fromNpc = false))
        assertTrue(fixture.random.bounds.isEmpty())
    }

    @Test
    fun `null defence skips Justiciar while retaining eligible Elysian`() {
        val fixture = Fixture()
        fixture.equipJusticiar()
        fixture.equip("obj.elysian")
        fixture.random.enqueue(0)
        assertEquals(75, fixture.reduce(100, context = context(defence = null)))
        assertEquals(listOf(10), fixture.random.bounds)
    }

    @Test
    fun `Justiciar uses the native defence bonus matching each supplied style`() {
        val fixture = Fixture()
        fixture.equipJusticiar()
        val bonuses = WornBonuses()
        val styles = listOf(
            HitDefenceType.Stab to bonuses.defensiveStabBonus(fixture.player),
            HitDefenceType.Slash to bonuses.defensiveSlashBonus(fixture.player),
            HitDefenceType.Crush to bonuses.defensiveCrushBonus(fixture.player),
            HitDefenceType.Ranged to bonuses.defensiveRangedBonus(fixture.player),
            HitDefenceType.Magic to bonuses.defensiveMagicBonus(fixture.player),
        )
        for ((style, bonus) in styles) {
            val expected = 120 - maxOf(1, ceil(120 * bonus.coerceAtLeast(0) / 3000.0).toInt())
            assertEquals(expected, fixture.reduce(120, context = context(defence = style)), style.toString())
        }
        val helm = checkNotNull(ServerCacheManager.getItem("obj.justiciar_faceguard".asRSCM()))
        fixture.player.worn[helm.wearpos1] = null
        assertEquals(120, fixture.reduce(120))
    }

    @Test
    fun `Dinh requires the block stance and completed passive delay`() {
        val fixture = Fixture()
        fixture.equip("obj.dinhs_bulwark")
        val blockStance = (0..3).single { stance ->
            VarPlayerIntMapSetter.set(fixture.player, "varp.com_mode", stance)
            fixture.styles.get(fixture.player) == AttackStyle.AggressiveMelee
        }
        VarPlayerIntMapSetter.set(fixture.player, "varp.com_mode", blockStance)
        VarPlayerIntMapSetter.set(fixture.player, "varp.dinhs_passive_delay", 101)
        assertEquals(100, fixture.reduce(100))
        fixture.player.currentMapClock = 101
        assertEquals(80, fixture.reduce(100))
        VarPlayerIntMapSetter.set(fixture.player, "varp.com_mode", (blockStance + 1) % 4)
        assertEquals(100, fixture.reduce(100))
    }

    @Test
    fun `Ward requires a demon source active prayer and explicit eligibility`() {
        val fixture = Fixture()
        val demon = context(source = "npc.duke_sucellus_awake", equipment = false, ward = true)
        assertEquals(100, fixture.reduce(100, context = demon))
        VarPlayerIntMapSetter.set(fixture.player, "varbit.ward_of_arceuus_active", 1)
        assertEquals(90, fixture.reduce(100, context = demon))
        assertEquals(100, fixture.reduce(100, context = demon.copy(demonWard = false)))
        assertEquals(100, fixture.reduce(100, context = context(ward = true)))
        fixture.equip("obj.elysian")
        assertEquals(90, fixture.reduce(100, context = demon))
        assertTrue(fixture.random.bounds.isEmpty())
    }

    @Test
    fun `disabled Ward never needs a valid NPC source definition`() {
        val fixture = Fixture()
        val noSource = context(ward = false).copy(sourceType = Int.MAX_VALUE)
        fixture.equip("obj.elysian")
        fixture.random.enqueue(0)
        assertEquals(75, fixture.reduce(100, context = noSource))
    }

    @Test
    fun `all NPC maxima ignore target reducers and consume no random rolls`() {
        val npc = Npc(checkNotNull(ServerCacheManager.getNpc("npc.king_dragon".asRSCM())), CoordGrid(2271, 4695, 0))
        val random = CountingRandom()
        val melee = NvPMeleeMaxHit(random, mockk(), mockk())
        val ranged = NvPRangedMaxHit(random, mockk(), mockk())
        val magic = NvPMagicMaxHit(random, mockk(), mockk())
        val fixture = Fixture()
        fixture.equipJusticiar()
        fixture.equip("obj.elysian")
        VarPlayerIntMapSetter.set(fixture.player, "varbit.ward_of_arceuus_active", 1)
        for (target in listOf(Player(), fixture.player)) {
            assertEquals(25, melee.getMaxHit(npc, target, MeleeAttackType.Stab))
            assertEquals(NvNRangedMaxHit().getMaxHit(npc), ranged.getMaxHit(npc, target))
            assertEquals(NvNMagicMaxHit().getMaxHit(npc), magic.getMaxHit(npc, target))
        }
        npc.strengthLvl = 1
        npc.rangedLvl = 1
        npc.magicLvl = 1
        assertEquals(NvNMeleeMaxHit().getMaxHit(npc), melee.getMaxHit(npc, fixture.player, MeleeAttackType.Stab))
        assertTrue(melee.getMaxHit(npc, fixture.player, MeleeAttackType.Stab) < 25)
        assertEquals(NvNRangedMaxHit().getMaxHit(npc), ranged.getMaxHit(npc, fixture.player))
        assertEquals(NvNMagicMaxHit().getMaxHit(npc), magic.getMaxHit(npc, fixture.player))
        assertTrue(random.bounds.isEmpty())
    }

    private class Fixture {
        val player = Player().apply {
            worldType = WorldType.MAIN
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            currentMapClock = 100
        }
        val random = CountingRandom()
        val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        private val reducer = NpcIncomingDamageReducer(random, WornBonuses(), DamageReductionAttributeCollector(styles))

        fun equip(symbol: String) {
            val type = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))
            player.worn[type.wearpos1] = InvObj(type)
        }

        fun equipJusticiar() {
            equip("obj.justiciar_faceguard")
            equip("obj.justiciar_chestguard")
            equip("obj.justiciar_leg_guards")
        }

        fun reduce(damage: Int, context: NpcDamageContext? = context(), fromNpc: Boolean = true): Int {
            val mark = hitmark_groups.regular_damage.lit.asRSCM()
            val hit = HitBuilder(
                type = HitType.Melee,
                damage = damage,
                sourceUid = null,
                sourceSlot = if (fromNpc) 1 else null,
                isFromNpc = fromNpc,
                isFromPlayer = !fromNpc,
                clientDelay = 0,
                righthandType = null,
                secondaryType = null,
                targetHitmark = mark,
                sourceHitmark = mark,
                publicHitmark = mark,
                zeroDamageHitmarkLit = null,
                zeroDamageHitmarkTint = null,
                maxDamageHitmarkLit = null,
                targetMaxDamageThreshold = Int.MAX_VALUE,
                sourceMaxDamageThreshold = Int.MAX_VALUE,
            )
            hit.npcDamageContext = context
            with(reducer) { hit.reduce(player) }
            return hit.damage
        }
    }

    private class CountingRandom : GameRandom {
        val bounds = mutableListOf<Int>()
        private val rolls = ArrayDeque<Int>()

        fun enqueue(value: Int) { rolls.addLast(value) }
        fun assertExhausted() { assertTrue(rolls.isEmpty()) }

        override fun of(maxExclusive: Int): Int {
            bounds += maxExclusive
            check(rolls.isNotEmpty()) { "Unexpected random roll with bound $maxExclusive" }
            return rolls.removeFirst().also { require(it in 0 until maxExclusive) }
        }

        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unexpected range roll")
        override fun randomDouble(): Double = error("Unexpected double roll")
    }

    companion object {
        private fun context(
            source: String = "npc.king_dragon",
            defence: HitDefenceType? = HitDefenceType.Stab,
            equipment: Boolean = true,
            ward: Boolean = false,
        ): NpcDamageContext = NpcDamageContext(source.asRSCM(), defence, equipment, ward)

        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
