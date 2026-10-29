package org.rsmod.api.combat

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.NpcMode
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.npc.NpcAttackOverride
import org.rsmod.api.combat.commons.npc.NpcAttackOverrideHook
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.formulas.NpcIncomingDamageReducer
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.magic.NvPMagicMaxHit
import org.rsmod.api.combat.formulas.maxhit.melee.NvPMeleeMaxHit
import org.rsmod.api.combat.formulas.maxhit.ranged.NvPRangedMaxHit
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.refs.params
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.access.StandardNpcAccessContext
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.events.PlayerQueueEvents
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContext
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitDefenceType
import org.rsmod.game.hit.HitType
import org.rsmod.game.hit.NpcDamageContext
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class NvPCombatMitigationTest {
    @Test
    fun `fixed override applies one Elysian proc or miss to the rolled damage`() {
        for ((procRoll, expected) in listOf(0 to 18, 6 to 18, 7 to 25, 9 to 25)) {
            val f = Fixture()
            f.equipElysian()
            f.override = NpcAttackOverride.Fixed(25)
            f.random.enqueue(procRoll)

            val queued = f.attack()

            assertEquals(expected, queued.damage)
            assertEquals(listOf("exclusive:10"), f.random.calls)
            assertEquals(listOf(25), f.incomingDamage)
            assertEquals(HitDefenceType.Stab, f.contexts.single()?.defenceType)
            assertEquals(f.npc.visType.id, f.contexts.single()?.sourceType)
            assertTrue(queued.isFromNpc)
            assertFalse(queued.isMagicSplash)
            assertSame(queued, f.impact())
            assertEquals(99 - expected, f.player.hitpoints)
        }
    }

    @Test
    fun `melee ranged and magic formulas roll their unmitigated maximum then reduce once`() {
        for (attack in attacks()) {
            val f = Fixture()
            f.equipElysian()
            // Live level changes keep the three formula paths above the sample damage. We retain
            // each native type's bonuses and feed the actual maximum into a strict RNG assertion.
            f.npc.strengthLvl = 240
            f.npc.rangedLvl = 240
            f.npc.magicLvl = 240
            val maximum = f.maximum(attack)
            assertTrue(maximum >= 8)
            assertTrue(f.random.calls.isEmpty(), "Computing a maximum must not roll an Elysian proc")
            f.random.enqueue(8, 0)

            val queued = f.attack(attack)

            assertEquals(6, queued.damage)
            assertEquals(listOf("range:0..$maximum", "exclusive:10"), f.random.calls)
            assertEquals(listOf(8), f.incomingDamage)
            assertEquals(defence(attack), f.contexts.single()?.defenceType)
            assertEquals(if (attack is CombatAttack.NpcMelee) 1 else 2, f.hitDelay)
            assertSame(queued, f.impact())
            assertEquals(93, f.player.hitpoints)
        }
    }

    @Test
    fun `guaranteed max override rolls once before Elysian mitigation`() {
        val f = Fixture()
        f.equipElysian()
        f.override = NpcAttackOverride.MaxHit(40, guaranteed = true)
        f.accurate = false
        f.random.enqueue(25, 0)

        assertEquals(18, f.attack().damage)
        assertEquals(listOf("range:0..40", "exclusive:10"), f.random.calls)
        assertEquals(listOf(25), f.incomingDamage)
    }

    @Test
    fun `Elysian reduction happens before low Hitpoints cap`() {
        val f = Fixture()
        f.equipElysian()
        f.player.statMap.setCurrentLevel("stat.hitpoints", 10)
        f.override = NpcAttackOverride.Fixed(25)
        f.random.enqueue(0)

        val queued = f.attack()

        assertEquals(listOf(25), f.incomingDamage)
        assertEquals(10, queued.damage, "25 becomes 18, then caps at 10; capping first would deal only 7")
        assertEquals(listOf("exclusive:10"), f.random.calls)
        assertSame(queued, f.impact())
        assertEquals(0, f.player.hitpoints)
        assertTrue("queue.death" in f.player.queueList)
    }

    @Test
    fun `Ward applies exactly once only for an active Ward and native demon source`() {
        for (demon in listOf(false, true)) {
            for (active in listOf(false, true)) {
                val f = Fixture(if (demon) "npc.lesser_demon" else "npc.king_dragon")
                assertEquals(demon, f.npc.visType.param(params.demon) != 0)
                VarPlayerIntMapSetter.set(f.player, "varbit.ward_of_arceuus_active", if (active) 1 else 0)
                f.override = NpcAttackOverride.Fixed(80)

                assertEquals(if (demon && active) 72 else 80, f.attack().damage)
                assertEquals(listOf(80), f.incomingDamage)
                assertTrue(f.random.calls.isEmpty())
                assertNotNull(f.contexts.single())
                assertTrue(checkNotNull(f.contexts.single()).demonWard)
            }
        }
    }

    @Test
    fun `Ward follows Elysian once on a native demon attack`() {
        val f = Fixture("npc.lesser_demon")
        f.equipElysian()
        VarPlayerIntMapSetter.set(f.player, "varbit.ward_of_arceuus_active", 1)
        f.override = NpcAttackOverride.Fixed(25)
        f.random.enqueue(0)

        assertEquals(17, f.attack().damage, "Elysian makes 18, then Ward removes 1")
        assertEquals(listOf("exclusive:10"), f.random.calls)
        assertEquals(listOf(25), f.incomingDamage)
    }

    @Test
    fun `prayer penetration preserves the launch health cap before prayer`() {
        val f = Fixture()
        f.equipElysian()
        f.player.statMap.setCurrentLevel("stat.hitpoints", 10)
        VarPlayerIntMapSetter.set(f.player, "varbit.prayer_protectfrommelee", 1)
        f.override = NpcAttackOverride.Fixed(25, penetration = 20)
        f.random.enqueue(0)

        val queued = f.attack()

        assertEquals(2, queued.damage, "25 → Elysian 18 → launch HP cap 10 → 20% prayer penetration 2")
        assertEquals(listOf("exclusive:10"), f.random.calls)
        assertSame(queued, f.impact())
        assertEquals(8, f.player.hitpoints)
    }

    @Test
    fun `matching protection prayer fully blocks each ordinary NPC attack style`() {
        for (attack in attacks()) {
            val f = Fixture()
            f.equipElysian()
            VarPlayerIntMapSetter.set(f.player, prayer(attack), 1)
            f.override = NpcAttackOverride.Fixed(25)
            f.random.enqueue(0)

            assertEquals(0, f.attack(attack).damage)
            assertEquals(listOf("exclusive:10"), f.random.calls)
            assertEquals(0, f.impact().damage)
            assertEquals(99, f.player.hitpoints)
        }
    }

    @Test
    fun `misses do not roll damage or spend an Elysian proc`() {
        for (attack in attacks()) {
            val f = Fixture()
            f.equipElysian()
            f.accurate = false

            assertEquals(0, f.attack(attack).damage)
            assertEquals(listOf(0), f.incomingDamage)
            assertTrue(f.random.calls.isEmpty())
            assertEquals(0, f.impact().damage)
            assertEquals(99, f.player.hitpoints)
        }
    }

    @Test
    fun `a legacy NPC queue without damage context does not opt into equipment reductions`() {
        val f = Fixture("npc.lesser_demon")
        f.equipElysian()
        VarPlayerIntMapSetter.set(f.player, "varbit.ward_of_arceuus_active", 1)

        val queued = f.player.queueHit(f.npc, 1, HitType.Melee, 25, f.modifier)

        assertEquals(25, queued.damage)
        assertNull(f.contexts.single())
        assertTrue(f.random.calls.isEmpty())
        assertSame(queued, f.impact())
        assertEquals(74, f.player.hitpoints)
    }

    private class Fixture(symbol: String = "npc.king_dragon") {
        val random = ScriptedRandom()
        private val events = EventBus()
        val player = Player().apply {
            slotId = 1
            uuid = 1
            worldType = WorldType.MAIN
            assignUid()
            currentMapClock = 100
            processedMapClock = 100
            coords = CoordGrid(2276, 4695)
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            statMap.setBaseLevel("stat.hitpoints", 99)
            statMap.setCurrentLevel("stat.hitpoints", 99)
        }
        val npc = Npc(symbol, CoordGrid(2271, 4695)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
            mode = NpcMode.OpPlayer2
        }
        private val players = PlayerList().also { it[player.slotId] = player }
        private val npcs = NpcList().also { it[npc.slotId] = npc }
        private val bonuses = WornBonuses()
        private val reductions = DamageReductionAttributeCollector(AttackStyles())
        private val melee = NvPMeleeMaxHit(random, bonuses, reductions)
        private val ranged = NvPRangedMaxHit(random, bonuses, reductions)
        private val magic = NvPMagicMaxHit(random, bonuses, reductions)
        private val maxima = MaxHitFormulae(
            pvnMagicMaxHit = mockk(), pvpMagicMaxHit = mockk(), nvpMagicMaxHit = magic, nvnMagicMaxHit = mockk(),
            pvnMeleeMaxHit = mockk(), pvpMeleeMaxHit = mockk(), nvnMeleeMaxHit = mockk(), nvpMeleeMaxHit = melee,
            pvnRangedMaxHit = mockk(), pvpRangedMaxHit = mockk(), nvpRangedMaxHit = ranged, nvnRangedMaxHit = mockk(),
        )
        var accurate = true
        var override: NpcAttackOverride? = null
        val incomingDamage = mutableListOf<Int>()
        val contexts = mutableListOf<NpcDamageContext?>()
        private val impacts = mutableListOf<Hit>()
        var hitDelay = -1
            private set
        private val accuracy = mockk<AccuracyFormulae>().also {
            every { it.rollMeleeAccuracy(npc, player, any(), random) } answers { accurate }
            every { it.rollRangedAccuracy(npc, player, random) } answers { accurate }
            every { it.rollMagicAccuracy(npc, player, random) } answers { accurate }
        }
        val modifier = StandardPlayerHitModifier(events, NpcIncomingDamageReducer(random, bonuses, reductions))
        private val combat = NvPCombat(
            accuracy, maxima, mockk(), modifier, emptySet(),
            setOf(NpcAttackOverrideHook { _, _ -> override }),
        )
        private val access = StandardNpcAccess(
            npc, GameCoroutine("nvp-mitigation-attack"),
            StandardNpcAccessContext({ random }, { error("NPC hit modifier not used") }, { error("NPC hit processor not used") }),
        )
        private val context = mockk<ProtectedAccessContext>().also {
            every { it.eventBus } returns events
            every { it.npcList } returns npcs
            every { it.playerList } returns players
            every { it.random } returns ImpactRandom
        }

        init {
            events.subscribeUnbound(PlayerHitEvents.Modify::class.java) {
                incomingDamage += hit.damage
                contexts += hit.npcDamageContext
            }
            events.subscribeUnbound(PlayerHitEvents.Impact::class.java) { impacts += hit }
            with(PlayerHitScript()) { ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup() }
        }

        fun equipElysian() {
            val type = checkNotNull(ServerCacheManager.getItem("obj.elysian".asRSCM()))
            player.worn[type.wearpos1] = InvObj(type)
        }

        fun maximum(attack: CombatAttack.NpcAttack): Int = when (attack) {
            is CombatAttack.NpcMelee -> maxima.getMeleeMaxHit(npc, player, attack.type)
            is CombatAttack.NpcRanged -> maxima.getRangedMaxHit(npc, player)
            is CombatAttack.NpcMagic -> maxima.getMagicMaxHit(npc, player)
        }

        fun attack(attack: CombatAttack.NpcAttack = CombatAttack.NpcMelee(MeleeAttackType.Stab)): Hit {
            combat.attack(access, player, attack)
            random.assertExhausted()
            assertEquals(1, player.queueList.count("queue.com_retaliate_npc"))
            return queuedHit().also { assertEquals(1, incomingDamage.size) }
        }

        private fun queuedHit(): Hit {
            val iterator = checkNotNull(player.queueList.iterator())
            val hits = mutableListOf<Hit>()
            while (iterator.hasNext()) {
                val queue = iterator.next()
                if (queue.id == "queue.hit".asRSCM()) {
                    hitDelay = queue.remainingCycles
                    hits += queue.args as Hit
                }
            }
            return hits.single()
        }

        fun impact(): Hit {
            val hit = queuedHit()
            player.queueList.removeAll("queue.hit")
            val access = ProtectedAccess(player, GameCoroutine("nvp-mitigation-impact"), context)
            runImmediately {
                assertTrue(events.publish(access, PlayerQueueEvents.Protected(hit, "queue.hit".asRSCM())))
            }
            return impacts.single()
        }
    }

    private class ScriptedRandom : GameRandom {
        val calls = mutableListOf<String>()
        private val values = ArrayDeque<Int>()

        fun enqueue(vararg rolls: Int) { values.addAll(rolls.toList()) }
        fun assertExhausted() { assertTrue(values.isEmpty(), "Unused random draws: $values") }

        override fun of(maxExclusive: Int): Int {
            calls += "exclusive:$maxExclusive"
            return draw().also { require(it in 0 until maxExclusive) }
        }

        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            calls += "range:$minInclusive..$maxInclusive"
            return draw().also { require(it in minInclusive..maxInclusive) }
        }

        override fun randomDouble(): Double = error("Unexpected randomDouble draw")

        private fun draw(): Int {
            check(values.isNotEmpty()) { "Unexpected random draw: $calls" }
            return values.removeFirst()
        }
    }

    /** Impact sound selection uses a separate random source from attack and mitigation assertions. */
    private object ImpactRandom : GameRandom {
        override fun of(maxExclusive: Int): Int = 0
        override fun of(minInclusive: Int, maxInclusive: Int): Int = minInclusive
        override fun randomDouble(): Double = 0.0
    }

    companion object {
        private fun attacks(): List<CombatAttack.NpcAttack> = listOf(
            CombatAttack.NpcMelee(MeleeAttackType.Stab),
            CombatAttack.NpcRanged(RangedAttackType.Standard),
            CombatAttack.NpcMagic(0),
        )

        private fun defence(attack: CombatAttack.NpcAttack): HitDefenceType = when (attack) {
            is CombatAttack.NpcMelee -> HitDefenceType.Stab
            is CombatAttack.NpcRanged -> HitDefenceType.Ranged
            is CombatAttack.NpcMagic -> HitDefenceType.Magic
        }

        private fun prayer(attack: CombatAttack.NpcAttack): String = when (attack) {
            is CombatAttack.NpcMelee -> "varbit.prayer_protectfrommelee"
            is CombatAttack.NpcRanged -> "varbit.prayer_protectfrommissiles"
            is CombatAttack.NpcMagic -> "varbit.prayer_protectfrommagic"
        }

        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }

        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Hit processing unexpectedly suspended." }.getOrThrow()
        }
    }
}
