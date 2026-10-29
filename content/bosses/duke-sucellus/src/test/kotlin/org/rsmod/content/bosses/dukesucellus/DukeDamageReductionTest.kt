package org.rsmod.content.bosses.dukesucellus

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.every
import io.mockk.mockk
import kotlin.math.ceil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.bosses.runtime.BossDeps
import org.rsmod.api.bosses.runtime.BossExtensionRegistry
import org.rsmod.api.bosses.runtime.EffectInterpreter
import org.rsmod.api.bosses.runtime.EncounterRegistry
import org.rsmod.api.bosses.spec.Effect
import org.rsmod.api.bosses.spec.HitMitigation
import org.rsmod.api.combat.commons.player.finishNpcHit
import org.rsmod.api.combat.formulas.NpcIncomingDamageReducer
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.game.process.world.WorldQueueListProcess
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitDefenceType
import org.rsmod.game.hit.HitType
import org.rsmod.game.hit.NpcDamageContext
import org.rsmod.game.inv.InvObj
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@ResourceLock("ServerCacheManager")
class DukeDamageReductionTest {
    @Test
    fun `registered Duke melee chip receives Ward once`() {
        for ((ward, expected) in listOf(false to 11, true to 10)) {
            val fixture = Fixture(ward)
            fixture.random.enqueue(11)
            fixture.interpreter.run(null, fixture.chip())
            fixture.assertHit(expected, HitType.Melee, listOf(12))
        }
    }

    @Test
    fun `registered Duke magic projectile receives Ward once`() {
        for ((ward, expected) in listOf(false to 48, true to 44)) {
            val fixture = Fixture(ward)
            fixture.random.enqueue(20)
            val projectile = fixture.effects("ranged_magic").filterIsInstance<Effect.Projectile>().single()
            fixture.interpreter.run(null, projectile)
            fixture.assertHit(expected, HitType.Magic, listOf(21))
            assertEquals(1, fixture.projectiles.size)
        }
    }

    @Test
    fun `registered slam extension applies Ward once and bypasses equipment procs`() {
        for ((ward, expected) in listOf(false to 56, true to 51)) {
            val fixture = Fixture(ward)
            fixture.equip("obj.elysian")
            fixture.equipJusticiar()
            fixture.player.coords = fixture.npc.coords.translate(0, -1)
            fixture.random.enqueue(31)
            fixture.external("melee", "melee_slam")
            fixture.assertHit(expected, HitType.Melee, listOf(32))
        }
    }

    @Test
    fun `Duke chip selects Crush defence for the Justiciar set`() {
        val fixture = Fixture(ward = true)
        fixture.equipJusticiar()
        val crush = WornBonuses().defensiveCrushBonus(fixture.player)
        val afterJusticiar = 11 - maxOf(1, ceil(11 * crush.coerceAtLeast(0) / 3000.0).toInt())
        val afterWard = afterJusticiar - afterJusticiar / 10
        fixture.random.enqueue(11)
        fixture.interpreter.run(null, fixture.chip())
        fixture.assertHit(afterWard, HitType.Melee, listOf(12))
    }

    @Test
    fun `registered gaze resolver keeps peripheral damage outside Ward and equipment reduction`() {
        val fixture = Fixture(ward = true)
        fixture.equip("obj.elysian")
        fixture.random.enqueue(0)
        fixture.external("gaze_special", "gaze_resolve")
        fixture.assertHit(60, HitType.Typeless, listOf(42))
        assertTrue(fixture.player.frozen)
    }

    @Test
    fun `registered gas flare preserves damage while Ward only reduces its prayer drain`() {
        for ((ward, prayer) in listOf(false to 95, true to 97)) {
            val fixture = Fixture(ward)
            fixture.equip("obj.elysian")
            fixture.external("gas_flare", "gas_flare_cast")
            fixture.player.coords = fixture.projectiles.single().endCoord
            fixture.random.enqueue(12)
            fixture.advance(4)
            fixture.assertHit(12, HitType.Typeless, listOf(13))
            assertEquals(prayer, fixture.player.statMap.getCurrentLevel("stat.prayer").toInt())
        }
    }

    @Test
    fun `Duke startup does not apply Ward to unrelated NPC damage`() {
        val fixture = Fixture(ward = true)
        val other = Npc(
            checkNotNull(ServerCacheManager.getNpc("npc.king_dragon".asRSCM())),
            fixture.npc.coords,
        ).apply {
            slotId = 3
            assignUid()
        }
        fixture.player.finishNpcHit(
            other, 1, HitType.Magic, 40, fixture.modifier,
            damageContext = NpcDamageContext(other.visType.id, HitDefenceType.Magic),
        )
        fixture.assertHit(40, HitType.Magic, emptyList())
    }

    @Test
    fun `explicit NONE keeps a typed Duke hit outside Ward and equipment reduction`() {
        val fixture = Fixture(ward = true)
        fixture.equip("obj.elysian")
        fixture.random.enqueue(11)
        fixture.interpreter.run(null, fixture.chip().copy(mitigation = HitMitigation.NONE))
        fixture.assertHit(11, HitType.Melee, listOf(12))
    }

    private class Fixture(ward: Boolean) {
        val random = ScriptedRandom()
        private val events = EventBus()
        private val clock = MapClock(100)
        private val queues = WorldQueueList()
        private val queueProcess = WorldQueueListProcess(queues)
        private val registry = EncounterRegistry(clock)
        private val extensions = BossExtensionRegistry()
        private val players = PlayerList()
        val projectiles = mutableListOf<ProjAnim>()
        private val worldRepo = mockk<WorldRepository>(relaxed = true)
        private val npcRepo = mockk<NpcRepository>(relaxed = true)
        val npc = Npc(
            checkNotNull(ServerCacheManager.getNpc("npc.duke_sucellus_awake".asRSCM())),
            CoordGrid(2735, 9040, 0),
        ).apply {
            slotId = 1
            assignUid()
        }
        val player = Player().apply {
            worldType = WorldType.MAIN
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            coords = npc.coords.translate(2, -2)
            slotId = 2
            uuid = 100
            assignUid()
            statMap.setBaseLevel("stat.hitpoints", 99)
            statMap.setCurrentLevel("stat.hitpoints", 99)
            statMap.setBaseLevel("stat.prayer", 99)
            statMap.setCurrentLevel("stat.prayer", 99)
            VarPlayerIntMapSetter.set(this, "varbit.ward_of_arceuus_active", if (ward) 1 else 0)
        }
        private val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        val modifier = StandardPlayerHitModifier(
            events,
            NpcIncomingDamageReducer(random, WornBonuses(), DamageReductionAttributeCollector(styles)),
        )
        private val deps = mockk<BossDeps>().also {
            every { it.random } returns random
            every { it.mapClock } returns clock
            every { it.worldQueues } returns queues
            every { it.encounterRegistry } returns registry
            every { it.extensionRegistry } returns extensions
            every { it.playerList } returns players
            every { it.worldRepo } returns worldRepo
            every { it.npcRepo } returns npcRepo
            every { it.playerHitModifier } returns modifier
        }
        private val script = DukeSucellus(deps, events)

        init {
            players[player.slotId] = player
            every { worldRepo.projAnim(any<ProjAnim>()) } answers {
                projectiles += firstArg<ProjAnim>()
                Unit
            }
            with(script) { ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup() }
        }

        val interpreter = EffectInterpreter(npc, player, script.spec, registry.of(npc), deps)

        fun chip(): Effect.Hit = effects("melee").filterIsInstance<Effect.Hit>().single()

        fun effects(ability: String): List<Effect> = flatten(script.spec.abilities.getValue(ability))

        fun external(ability: String, suffix: String) {
            val effect = effects(ability).filterIsInstance<Effect.External>().single { it.handler.endsWith(suffix) }
            assertTrue(extensions.contains(effect.handler))
            interpreter.run(null, effect)
        }

        fun advance(ticks: Int) {
            repeat(ticks) {
                clock.cycle++
                queueProcess.process()
            }
        }

        fun equip(symbol: String) {
            val type = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))
            player.worn[type.wearpos1] = InvObj(type)
        }

        fun equipJusticiar() {
            equip("obj.justiciar_faceguard")
            equip("obj.justiciar_chestguard")
            equip("obj.justiciar_leg_guards")
        }

        fun assertHit(damage: Int, type: HitType, bounds: List<Int>) {
            val hits = mutableListOf<Hit>()
            val iterator = checkNotNull(player.queueList.iterator())
            while (iterator.hasNext()) {
                val queue = iterator.next()
                if (queue.id == "queue.hit".asRSCM()) hits += queue.args as Hit
            }
            assertEquals(damage, hits.single().damage)
            assertEquals(type, hits.single().type)
            assertTrue(hits.single().isFromNpc)
            assertEquals(bounds, random.bounds)
            random.assertExhausted()
        }

        private fun flatten(effect: Effect): List<Effect> = when (effect) {
            is Effect.Sequence -> effect.effects.flatMap(::flatten)
            is Effect.Parallel -> effect.effects.flatMap(::flatten)
            else -> listOf(effect)
        }
    }

    private class ScriptedRandom : GameRandom {
        val bounds = mutableListOf<Int>()
        private val rolls = ArrayDeque<Int>()

        fun enqueue(value: Int) { rolls.addLast(value) }
        fun assertExhausted() { assertTrue(rolls.isEmpty()) }

        override fun of(maxExclusive: Int): Int {
            bounds += maxExclusive
            check(rolls.isNotEmpty()) { "Unexpected extra roll with bound $maxExclusive" }
            return rolls.removeFirst().also { require(it in 0 until maxExclusive) }
        }

        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unexpected range roll")
        override fun randomDouble(): Double = error("Unexpected floating-point roll")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
