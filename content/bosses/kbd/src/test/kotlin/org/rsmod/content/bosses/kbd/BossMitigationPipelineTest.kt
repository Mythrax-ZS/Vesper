package org.rsmod.content.bosses.kbd

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.bosses.runtime.BossDeps
import org.rsmod.api.bosses.runtime.BossEncounter
import org.rsmod.api.bosses.runtime.EffectInterpreter
import org.rsmod.api.bosses.spec.DamageExpr
import org.rsmod.api.bosses.spec.Effect
import org.rsmod.api.bosses.spec.HitMitigation
import org.rsmod.api.bosses.spec.HitType
import org.rsmod.api.combat.formulas.NpcIncomingDamageReducer
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.hit.DeferredPlayerHit
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.hit.modify
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitDefenceType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.queue.PlayerQueueList
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@ResourceLock("ServerCacheManager")
class BossMitigationPipelineTest {
    @Test
    fun `ordinary fixed hits receive mitigation once`() {
        val f = Fixture()
        f.shield(true)
        f.run(Effect.Hit(damage = DamageExpr.Fixed(25), type = HitType.Ranged))
        assertEquals(18, (f.queue("queue.hit").args as Hit).damage)
        assertEquals(listOf(10), f.random.bounds)
    }

    @Test
    fun `special and environmental hits retain explicit eligibility`() {
        for (hit in listOf(
            Effect.Hit(damage = DamageExpr.Fixed(25), type = HitType.Typeless),
            Effect.Hit(damage = DamageExpr.Fixed(25), type = HitType.Dragonfire),
            Effect.Hit(damage = DamageExpr.Fixed(25), type = HitType.Magic, hazard = true),
            Effect.Hit(damage = DamageExpr.Fixed(25), type = HitType.Ranged, mitigation = HitMitigation.NONE),
        )) {
            val f = Fixture()
            f.shield(true)
            f.run(hit)
            val damage = (f.queue("queue.hit").args as Hit).damage
            if (hit.type == HitType.Dragonfire) {
                assertEquals(0, damage)
                assertEquals(listOf(26), f.random.bounds)
            } else {
                assertEquals(25, damage)
                assertTrue(f.random.bounds.isEmpty())
            }
        }
    }

    @Test
    fun `special hits can explicitly opt into mitigation independently of prayer style`() {
        val f = Fixture()
        f.shield(true)
        f.run(Effect.Hit(
            damage = DamageExpr.Fixed(25), type = HitType.Typeless,
            mitigation = HitMitigation(defenceType = HitDefenceType.Stab),
        ))
        assertEquals(18, (f.queue("queue.hit").args as Hit).damage)
        assertEquals(listOf(10), f.random.bounds)
    }

    @Test
    fun `deferred projectiles preserve context and only roll mitigation at impact`() {
        val f = Fixture()
        f.run(f.projectile(deferred = true))
        val queued = f.queue("queue.impact_hit")
        val hit = queued.args as DeferredPlayerHit
        assertEquals(25, hit.builder.damage)
        assertEquals(f.npc.visType.id, hit.builder.npcDamageContext?.sourceType)
        assertEquals(HitDefenceType.Stab, hit.builder.npcDamageContext?.defenceType)
        assertTrue(f.random.bounds.isEmpty())
        assertTrue(queued.remainingCycles > 0)
        f.shield(true)
        hit.modifier.modify(hit.builder, f.player)
        assertEquals(18, hit.builder.build().damage)
        assertEquals(listOf(10), f.random.bounds)
    }

    @Test
    fun `removing equipment before a deferred impact changes mitigation`() {
        val f = Fixture()
        f.shield(true)
        f.run(f.projectile(deferred = true))
        f.shield(false)
        val hit = f.queue("queue.impact_hit").args as DeferredPlayerHit
        hit.modifier.modify(hit.builder, f.player)
        assertEquals(25, hit.builder.build().damage)
        assertTrue(f.random.bounds.isEmpty())
    }

    @Test
    fun `ordinary projectile stores already mitigated damage at launch`() {
        val f = Fixture()
        f.shield(true)
        f.run(f.projectile(deferred = false))
        f.shield(false)
        assertEquals(18, (f.queue("queue.hit").args as Hit).damage)
        assertEquals(listOf(10), f.random.bounds)
    }

    @Test
    fun `partial prayer protection retains the launch health cap before prayer`() {
        val f = Fixture()
        f.shield(true)
        f.player.statMap.setCurrentLevel("stat.hitpoints", 10)
        VarPlayerIntMapSetter.set(f.player, "varbit.prayer_protectfrommelee", 1)
        f.run(Effect.Hit(
            damage = DamageExpr.Fixed(25), type = HitType.Melee, penetration = 50,
            mitigation = HitMitigation(defenceType = HitDefenceType.Stab),
        ))
        assertEquals(5, (f.queue("queue.hit").args as Hit).damage)
        assertEquals(listOf(10), f.random.bounds)
    }

    private class Fixture {
        val random = ZeroRandom()
        val player = Player().apply {
            worldType = WorldType.MAIN
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            coords = CoordGrid(2278, 4695, 0)
            slotId = 2
            uuid = 100
            assignUid()
            statMap.setBaseLevel("stat.hitpoints", 99)
            statMap.setCurrentLevel("stat.hitpoints", 99)
        }
        val npc = Npc(checkNotNull(ServerCacheManager.getNpc("npc.king_dragon".asRSCM())), CoordGrid(2271, 4695, 0)).apply {
            slotId = 1
            assignUid()
        }
        private val events = EventBus()
        private val clock = MapClock(100)
        private val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        private val modifier = StandardPlayerHitModifier(
            events, NpcIncomingDamageReducer(random, WornBonuses(), DamageReductionAttributeCollector(styles)),
        )
        private val worldQueues = WorldQueueList()
        private val world = mockk<WorldRepository>().also {
            every { it.projAnim(any<ProjAnim>()) } returns Unit
        }
        private val deps = mockk<BossDeps>().also {
            every { it.random } returns random
            every { it.mapClock } returns clock
            every { it.worldQueues } returns worldQueues
            every { it.worldRepo } returns world
            every { it.playerHitModifier } returns modifier
        }
        private val spec = KingBlackDragon(deps, KbdBreathDamage(random)).spec
        private val interpreter = EffectInterpreter(npc, player, spec, BossEncounter(npc, spec, clock), deps)

        fun shield(worn: Boolean) {
            val shield = checkNotNull(ServerCacheManager.getItem("obj.elysian".asRSCM()))
            player.worn[shield.wearpos1] = if (worn) InvObj(shield) else null
        }

        fun projectile(deferred: Boolean) = Effect.Projectile(
            spotanim = "spotanim.dragon_ranged_fire_attack", travel = "projanim.dragonfire",
            hit = Effect.Hit(
                damage = DamageExpr.Fixed(25), type = HitType.Melee,
                mitigation = HitMitigation(defenceType = HitDefenceType.Stab),
            ),
            resolveOnImpact = deferred,
        )

        fun run(effect: Effect) = interpreter.run(null, effect)

        fun queue(symbol: String): PlayerQueueList.Queue {
            val matches = mutableListOf<PlayerQueueList.Queue>()
            val iterator = checkNotNull(player.queueList.iterator())
            while (iterator.hasNext()) {
                val queue = iterator.next()
                if (queue.id == symbol.asRSCM()) matches += queue
            }
            return matches.single()
        }
    }

    private class ZeroRandom : GameRandom {
        val bounds = mutableListOf<Int>()
        override fun of(maxExclusive: Int): Int {
            bounds += maxExclusive
            return 0
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unexpected range roll")
        override fun randomDouble(): Double = error("Unexpected floating-point roll")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
        }
    }
}
