package org.rsmod.content.other.special.weapons.melee

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.formulas.PvpIncomingDamageReducer
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.access.StandardNpcAccessContext
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.events.NpcQueueEvents
import org.rsmod.api.npc.hit.flatArmour
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.processor.StandardNpcHitProcessor
import org.rsmod.api.npc.plugin.AiSpawnScript
import org.rsmod.api.player.cheat.adminMaxHit
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.events.PlayerQueueEvents
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.interact.PlayerInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContext
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.api.weapons.attack
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class MultiHitWeaponTest {
    @Test
    fun `scythe splits one maximum into full half and quarter including rounded zero`() {
        for (maximum in listOf(1, 3, 5, 9)) {
            val f = Fixture(Kind.Scythe, maximum)
            val maxima = listOf(maximum, maximum / 2, maximum / 4)
            f.accuracy(true, true, true)
            maxima.forEach { f.random.range(0..it, it) }

            val queued = f.attack()

            assertEquals(maxima.map { maxOf(it, 1) }, queued.map { it.damage })
            assertEquals(listOf(1, 1, 1), f.delays)
            assertEquals(1, f.maxCalculations)
            assertEquals(3, f.accuracyChecks)
            f.assertConsumed()
        }
    }

    @Test
    fun `paired weapons split odd maxima once for NPC and player targets`() {
        for (kind in listOf(Kind.Family, Kind.Dual)) {
            for (pvp in listOf(false, true)) {
                for (maximum in listOf(1, 3, 9)) {
                    val f = Fixture(kind, maximum, pvp)
                    val first = maximum / 2
                    val second = maximum - first
                    f.accuracy(true, true)
                    f.random.range(0..first, first)
                    f.random.range(0..second, second)

                    val queued = f.attack()

                    assertEquals(listOf(maxOf(first, 1), second), queued.map { it.damage })
                    assertEquals(if (kind == Kind.Dual) listOf(1, 2) else listOf(1, 1), f.delays)
                    assertEquals(1, f.maxCalculations)
                    assertEquals(2, f.accuracyChecks)
                    f.assertConsumed()
                }
            }
        }
    }

    @Test
    fun `independent family hit may succeed when the first accuracy check fails`() {
        val f = Fixture(Kind.Family, 3)
        f.accuracy(false, true)
        f.random.range(0..2, 2)

        assertEquals(listOf(0, 2), f.attack().map { it.damage })
        assertEquals(2, f.accuracyChecks)
        assertEquals(1, f.maxCalculations)
        f.assertConsumed()
    }

    @Test
    fun `scythe rolls accuracy independently without sampling damage for missed splats`() {
        val f = Fixture(Kind.Scythe, 9)
        f.accuracy(false, true, false)
        f.random.range(0..4, 0)

        assertEquals(listOf(0, 1, 0), f.attack().map { it.damage })
        assertEquals(3, f.accuracyChecks)
        assertEquals(1, f.maxCalculations)
        f.assertConsumed()
    }

    @Test
    fun `dual macuahuitl first miss prevents second accuracy and damage rolls`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(Kind.Dual, 9, pvp)
            f.accuracy(false)

            assertEquals(listOf(0, 0), f.attack().map { it.damage })
            assertEquals(1, f.accuracyChecks)
            assertEquals(1, f.maxCalculations)
            f.assertConsumed()
        }
    }

    @Test
    fun `dual macuahuitl second miss preserves the first successful hit`() {
        val f = Fixture(Kind.Dual, 9)
        f.accuracy(true, false)
        f.random.range(0..4, 4)

        assertEquals(listOf(4, 0), f.attack().map { it.damage })
        assertEquals(2, f.accuracyChecks)
        f.assertConsumed()
    }

    @Test
    fun `armour zero on first dual hit does not cancel second accuracy check`() {
        val f = Fixture(Kind.Dual, 3)
        f.npc.vars["varn.flat_armour"] = 1
        f.accuracy(true, true)
        f.random.range(0..1, 0)
        f.random.range(0..2, 2)

        assertEquals(listOf(0, 1), f.attack().map { it.damage })
        assertEquals(2, f.accuracyChecks)
        assertEquals(listOf(0, 1), f.impact().map { it.damage })
        assertEquals(f.startNpcHp - 1, f.npc.hitpoints)
        assertEquals(1, f.npc.vars["varn.flat_armour"])
        f.assertConsumed()
    }

    @Test
    fun `every accurate splat receives persistent negative armour exactly once`() {
        for (kind in Kind.entries) {
            val f = Fixture(kind, 1)
            f.npc.vars["varn.flat_armour"] = -2
            val maxima = if (kind == Kind.Scythe) listOf(1, 0, 0) else listOf(0, 1)
            f.accuracy(*BooleanArray(maxima.size) { true })
            maxima.forEach { f.random.range(0..it, 0) }

            assertEquals(List(maxima.size) { 3 }, f.attack().map { it.damage })
            assertEquals(List(maxima.size) { 3 }, f.impact().map { it.damage })
            assertEquals(f.startNpcHp - maxima.size * 3, f.npc.hitpoints)
            assertEquals(-2, f.npc.vars["varn.flat_armour"])
            assertEquals(1, f.maxCalculations)
            f.assertConsumed()
        }
    }

    @Test
    fun `all accuracy misses remain zero even against negative armour`() {
        for (kind in Kind.entries) {
            val f = Fixture(kind, 3)
            f.npc.vars["varn.flat_armour"] = -2
            val hitCount = if (kind == Kind.Scythe) 3 else 2
            val checks = if (kind == Kind.Dual) 1 else hitCount
            f.accuracy(*BooleanArray(checks))

            assertEquals(List(hitCount) { 0 }, f.attack().map { it.damage })
            assertEquals(List(hitCount) { 0 }, f.impact().map { it.damage })
            assertEquals(f.startNpcHp, f.npc.hitpoints)
            assertEquals(checks, f.accuracyChecks)
            assertEquals(1, f.maxCalculations)
            f.assertConsumed()
        }
    }

    @Test
    fun `a whole attack with zero maximum neither draws damage nor gains an eligible subhit`() {
        for (kind in Kind.entries) {
            val f = Fixture(kind, 0)
            val count = if (kind == Kind.Scythe) 3 else 2
            f.npc.vars["varn.flat_armour"] = -2
            f.accuracy(*BooleanArray(count) { true })

            assertEquals(List(count) { 0 }, f.attack().map { it.damage })
            assertEquals(List(count) { 0 }, f.impact().map { it.damage })
            assertEquals(f.startNpcHp, f.npc.hitpoints)
            assertEquals(1, f.maxCalculations)
            f.assertConsumed()
        }
    }

    @Test
    fun `admin maximum bypasses accuracy and RNG while retaining exact split maxima`() {
        for (kind in Kind.entries) {
            val f = Fixture(kind, 3)
            f.source.adminMaxHit = true

            assertEquals(if (kind == Kind.Scythe) listOf(3, 1, 0) else listOf(1, 2), f.attack().map { it.damage })
            assertEquals(0, f.accuracyChecks)
            assertEquals(1, f.maxCalculations)
            f.assertConsumed()
        }
    }

    @Test
    fun `multiple queued hits conserve the target remaining hitpoints at impact`() {
        val f = Fixture(Kind.Family, 9)
        f.npc.hitpoints = 6
        f.accuracy(true, true)
        f.random.range(0..4, 4)
        f.random.range(0..5, 5)

        assertEquals(listOf(4, 5), f.attack().map { it.damage })
        assertEquals(listOf(4, 2), f.impact().map { it.damage })
        assertEquals(0, f.npc.hitpoints)
        f.assertConsumed()
    }

    @Test
    fun `player prayer may reduce each successful minimum hit back to zero`() {
        val f = Fixture(Kind.Dual, 1, pvp = true)
        VarPlayerIntMapSetter.set(f.opponent, "varbit.prayer_protectfrommelee", 1)
        f.accuracy(true, true)
        f.random.range(0..0, 0)
        f.random.range(0..1, 0)

        assertEquals(listOf(0, 0), f.attack().map { it.damage })
        assertEquals(2, f.accuracyChecks)
        assertEquals(listOf(0, 0), f.impact().map { it.damage })
        assertEquals(99, f.opponent.hitpoints)
        f.assertConsumed()
    }

    @Test
    fun `player Elysian procs independently for each queued hit after one maximum calculation`() {
        val f = Fixture(Kind.Family, 25, pvp = true)
        val shield = checkNotNull(ServerCacheManager.getItem("obj.elysian".asRSCM()))
        f.opponent.worn[shield.wearpos1] = InvObj(shield)
        f.accuracy(true, true)
        f.random.range(0..12, 10)
        f.random.exclusive(10, 0)
        f.random.range(0..13, 10)
        f.random.exclusive(10, 9)

        assertEquals(listOf(7, 10), f.attack().map { it.damage })
        assertEquals(listOf(7, 10), f.impact().map { it.damage })
        assertEquals(82, f.opponent.hitpoints)
        assertEquals(1, f.maxCalculations)
        assertEquals(2, f.accuracyChecks)
        f.assertConsumed()
    }

    @Test
    fun `registered melee handlers award real XP from native armour adjusted hits`() {
        for (kind in Kind.entries) {
            for ((symbol, armour) in listOf("npc.earth_nagua" to -4, "npc.wyrm_light" to 1)) {
                val f = Fixture(kind, 9, npcSymbol = symbol)
                assertEquals(armour, f.npc.flatArmour)
                val maxima = if (kind == Kind.Scythe) List(minOf(f.npc.size, 3)) { 9 / (1 shl it) } else listOf(4, 5)
                f.accuracy(*BooleanArray(maxima.size) { true })
                maxima.forEach { f.random.range(0..it, it) }

                val damage = maxima.map { (it - armour).coerceAtLeast(0) }
                assertEquals(damage, f.attack().map { it.damage })
                f.assertXp(damage.sum())
                assertEquals(damage, f.impact().map { it.damage })
                assertEquals(f.startNpcHp - damage.sum(), f.npc.hitpoints)
                f.assertXp(damage.sum())
                f.assertConsumed()
            }
        }
    }

    @Test
    fun `explicit zero armour overrides native negative armour for XP and impact`() {
        val f = Fixture(Kind.Dual, 3, npcSymbol = "npc.earth_nagua")
        f.npc.flatArmour = 0
        f.accuracy(true, true)
        f.random.range(0..1, 1)
        f.random.range(0..2, 2)

        assertEquals(listOf(1, 2), f.attack().map { it.damage })
        f.assertXp(3)
        assertEquals(listOf(1, 2), f.impact().map { it.damage })
        f.assertXp(3)
        f.assertConsumed()
    }

    @Test
    fun `native negative armour multi-hit XP shares one remaining HP cap`() {
        for (kind in listOf(Kind.Family, Kind.Dual)) {
            val f = Fixture(kind, 3, npcSymbol = "npc.earth_nagua")
            f.npc.hitpoints = 7
            f.accuracy(true, true)
            f.random.range(0..1, 1)
            f.random.range(0..2, 2)

            assertEquals(listOf(5, 6), f.attack().map { it.damage })
            f.assertXp(7)
            assertEquals(listOf(5, 2), f.impact().map { it.damage })
            assertEquals(0, f.npc.hitpoints)
            f.assertXp(7)
            f.assertConsumed()
        }
    }

    @Test
    fun `real XP preserves style allocation profile rates and one grouped fine XP rounding`() {
        for (style in MeleeAttackStyle.entries) {
            val f = Fixture(Kind.Family, 3, npcSymbol = "npc.wyrm_light", style = style)
            f.source.xpRate = 1.5
            f.source.globalXpRate = 2.0
            f.accuracy(true, true)
            f.random.range(0..1, 1)
            f.random.range(0..2, 2)

            assertEquals(listOf(0, 1), f.attack().map { it.damage })
            f.assertXp(1)
            f.assertConsumed()
        }
        val f = Fixture(Kind.Family, 3, style = MeleeAttackStyle.Controlled)
        f.accuracy(true, true)
        f.random.range(0..1, 1)
        f.random.range(0..2, 2)
        f.attack()
        f.assertXp(3)
        f.assertConsumed()
    }

    @Test
    fun `native negative armour grants no XP on failed accuracy including gated dual second hit`() {
        for (kind in listOf(Kind.Family, Kind.Dual)) {
            val f = Fixture(kind, 3, npcSymbol = "npc.earth_nagua")
            f.accuracy(*BooleanArray(if (kind == Kind.Dual) 1 else 2))

            assertEquals(listOf(0, 0), f.attack().map { it.damage })
            f.assertXp(0)
            assertEquals(listOf(0, 0), f.impact().map { it.damage })
            assertEquals(f.startNpcHp, f.npc.hitpoints)
            f.assertConsumed()
        }
    }

    @Test
    fun `PvP retains raw grouped XP when prayer removes queued damage`() {
        val f = Fixture(Kind.Dual, 1, pvp = true)
        VarPlayerIntMapSetter.set(f.opponent, "varbit.prayer_protectfrommelee", 1)
        f.accuracy(true, true)
        f.random.range(0..0, 0)
        f.random.range(0..1, 0)

        assertEquals(listOf(0, 0), f.attack().map { it.damage })
        f.assertXp(2)
        f.assertConsumed()
    }

    private enum class Kind { Scythe, Family, Dual }

    private class Fixture(
        val kind: Kind,
        maximum: Int,
        val pvp: Boolean = false,
        npcSymbol: String = "npc.king_dragon",
        val style: MeleeAttackStyle = MeleeAttackStyle.Aggressive,
    ) {
        val events = EventBus()
        val random = StrictRandom()
        val source = player(1)
        val opponent = player(2)
        val players = PlayerList().also { it[1] = source; it[2] = opponent }
        val npc = Npc(npcSymbol, CoordGrid(2271, 4695)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
        }
        val startNpcHp = npc.hitpoints
        private val accuracyResults = ArrayDeque<Boolean>()
        private val accuracy = mockk<AccuracyFormulae>()
        private val maxima = mockk<MaxHitFormulae>()
        private val world = mockk<WorldRepository>()
        private val npcInteractions = mockk<NpcInteractions>()
        private val playerInteractions = mockk<PlayerInteractions>()
        private val npcModifier = StandardNpcHitModifier(events)
        private val playerModifier = StandardPlayerHitModifier(
            events,
            pvpReductions = PvpIncomingDamageReducer(random, DamageReductionAttributeCollector(mockk())),
        )
        private val manager = PlayerAttackManager(
            random, events, world, accuracy, maxima, npcModifier, playerModifier,
            npcInteractions, mockk(), playerInteractions, mockk(), emptySet(),
        )
        private val registry = WeaponRegistry()
        private val sourceAccess = access(source)
        private val opponentAccess = access(opponent)
        private val npcProcessor = StandardNpcHitProcessor(players, events, emptySet(), emptySet())
        private val npcAccess = StandardNpcAccess(
            npc, GameCoroutine("multihit-npc"),
            StandardNpcAccessContext({ random }, { npcModifier }, { npcProcessor }),
        )
        private val weapon = InvObj(checkNotNull(ServerCacheManager.getItem(when (kind) {
            Kind.Scythe -> "obj.scythe_of_vitur"
            Kind.Family -> "obj.glacial_temotli"
            Kind.Dual -> "obj.dual_macuahuitl"
        }.asRSCM())))
        private val melee = CombatAttack.Melee(weapon, MeleeAttackType.Crush, style, CombatStance.Stance1)
        private val impacts = mutableListOf<Hit>()
        private var queued = emptyList<Hit>()
        var delays = emptyList<Int>()
            private set
        var maxCalculations = 0
            private set
        var accuracyChecks = 0
            private set

        init {
            val weaponType = checkNotNull(ServerCacheManager.getItem(weapon.id))
            source.worn[weaponType.wearpos1] = weapon
            every { maxima.getMeleeMaxHit(source, npc, melee.type, melee.style, 1.0, false) } answers { maxCalculations++; maximum }
            every { maxima.getMeleeMaxHit(source, opponent, melee.type, melee.style, 1.0, false) } answers { maxCalculations++; maximum }
            every { accuracy.rollMeleeAccuracy(source, npc, melee.type, melee.style, melee.type, 1.0, random) } answers { nextAccuracy() }
            every { accuracy.rollMeleeAccuracy(source, opponent, melee.type, melee.style, melee.type, 1.0, random) } answers { nextAccuracy() }
            every { world.spotanimMap(any(), any(), any(), any()) } returns Unit
            every { npcInteractions.interact(source, npc, any()) } returns Unit
            every { playerInteractions.interact(source, opponent, any()) } returns Unit

            val wrapper = WeaponAttackManager(manager)
            val repository = WeaponRepository(registry)
            when (kind) {
                Kind.Scythe -> with(ScytheOfViturWeapons(world)) { repository.register(wrapper) }
                Kind.Family -> with(MultiHitMeleeWeapons()) { repository.register(wrapper) }
                Kind.Dual -> with(DualMacuahuitlWeapons()) { repository.register(wrapper) }
            }
            val context = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
            with(AiSpawnScript()) { context.startup() }
            events.publish(NpcStateEvents.Create(npc))
            assertTrue(npc.combatXpMultiplier > 0)
            with(NpcHitScript()) { context.startup() }
            with(PlayerHitScript()) { context.startup() }
            events.subscribeKeyed(NpcHitEvents.Impact::class.java, npc.id.toLong()) { impacts += hit }
            events.subscribeUnbound(PlayerHitEvents.Impact::class.java) { impacts += hit }
        }

        fun accuracy(vararg values: Boolean) { values.forEach(accuracyResults::addLast) }

        private fun nextAccuracy(): Boolean {
            accuracyChecks++
            return checkNotNull(accuracyResults.removeFirstOrNull()) { "Unexpected accuracy check" }
        }

        fun attack(): List<Hit> {
            val handler = checkNotNull(registry.getMelee(weapon))
            runImmediately {
                assertTrue(if (pvp) handler.attack(sourceAccess, opponent, melee) else handler.attack(sourceAccess, npc, melee))
            }
            if (pvp) {
                val iterator = checkNotNull(opponent.queueList.iterator())
                val hits = buildList { while (iterator.hasNext()) iterator.next().let { if (it.id == "queue.hit".asRSCM()) add(it) } }
                queued = hits.map { it.args as Hit }
                delays = hits.map { it.remainingCycles }
            } else {
                val iterator = checkNotNull(npc.queueList.iterator())
                val hits = buildList { while (iterator.hasNext()) iterator.next().let { if (it.id == "queue.hit".asRSCM()) add(it) } }
                queued = hits.map { it.args as Hit }
                delays = hits.map { it.remainingCycles }
            }
            assertTrue(queued.all { !it.isMagicSplash })
            return queued
        }

        fun impact(): List<Hit> {
            if (pvp) opponent.queueList.removeAll("queue.hit") else npc.queueList.removeAll("queue.hit")
            for (hit in queued) {
                runImmediately {
                    assertTrue(if (pvp) events.publish(opponentAccess, PlayerQueueEvents.Protected(hit, "queue.hit".asRSCM()))
                    else events.publish(npcAccess, NpcQueueEvents.Default(npc, hit, "queue.hit".asRSCM())))
                }
            }
            assertEquals(queued.size, impacts.size)
            return impacts.toList()
        }

        fun assertXp(damage: Int) {
            val multiplier = if (pvp) 1.0 else npc.combatXpMultiplier / 1000.0
            val rates = source.xpRate * source.globalXpRate
            val initial = PlayerSkillXPTable.getXPFromLevel(99) * 10
            for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.hitpoints")) {
                val xpPerDamage = when {
                    stat == "stat.hitpoints" -> 1.33
                    style == MeleeAttackStyle.Controlled -> 1.33
                    stat == "stat.attack" && style == MeleeAttackStyle.Accurate -> 4.0
                    stat == "stat.strength" && style == MeleeAttackStyle.Aggressive -> 4.0
                    stat == "stat.defence" && style == MeleeAttackStyle.Defensive -> 4.0
                    else -> 0.0
                }
                val fineXp = (initial + damage * xpPerDamage * multiplier * rates * 10).toInt()
                assertEquals(fineXp, source.statMap.getFineXP(stat), "$stat after $damage damage")
            }
        }

        fun assertConsumed() {
            assertTrue(accuracyResults.isEmpty(), "Unused accuracy outcomes: $accuracyResults")
            random.assertConsumed()
        }

        private fun access(player: Player): ProtectedAccess {
            val context = mockk<ProtectedAccessContext>()
            every { context.eventBus } returns events
            every { context.playerList } returns players
            every { context.npcList } returns NpcList()
            every { context.random } returns DefendSoundRandom
            return ProtectedAccess(player, GameCoroutine("multihit-player"), context)
        }

        private fun player(slot: Int): Player = Player().apply {
            slotId = slot
            uuid = slot.toLong()
            worldType = WorldType.MAIN
            assignUid()
            coords = CoordGrid(2276, 4695)
            currentMapClock = 100
            processedMapClock = 100
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.hitpoints")) {
                statMap.setBaseLevel(stat, 99)
                statMap.setCurrentLevel(stat, 99)
                statMap.setXP(stat, PlayerSkillXPTable.getXPFromLevel(99))
            }
            xpRate = 1.0
            globalXpRate = 1.0
        }
    }

    private object DefendSoundRandom : GameRandom {
        override fun of(maxExclusive: Int): Int {
            assertEquals(4, maxExclusive, "Impact may select a defend sound, not reroll a shield proc")
            return 0
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Impact must not reroll damage")
        override fun randomDouble(): Double = error("Unexpected impact roll")
    }

    private class StrictRandom : GameRandom {
        private val expected = ArrayDeque<Pair<String, Int>>()

        fun range(range: IntRange, value: Int) {
            require(value in range)
            expected.addLast("range:$range" to value)
        }

        fun exclusive(maximum: Int, value: Int) {
            require(value in 0 until maximum)
            expected.addLast("exclusive:$maximum" to value)
        }

        private fun next(kind: String): Int {
            val (expectedKind, value) = checkNotNull(expected.removeFirstOrNull()) { "Unexpected RNG call: $kind" }
            assertEquals(expectedKind, kind)
            return value
        }

        override fun of(minInclusive: Int, maxInclusive: Int): Int = next("range:${minInclusive..maxInclusive}")
        override fun of(maxExclusive: Int): Int = next("exclusive:$maxExclusive")
        override fun randomDouble(): Double = error("Unexpected floating-point draw")
        fun assertConsumed() = assertTrue(expected.isEmpty(), "Unused random draws: $expected")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }

        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Weapon or hit handler unexpectedly suspended" }.getOrThrow()
        }
    }
}
