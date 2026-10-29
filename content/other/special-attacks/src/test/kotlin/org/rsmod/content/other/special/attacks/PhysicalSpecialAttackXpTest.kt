package org.rsmod.content.other.special.attacks

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.aconverted.SpotanimType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.enums.SaEnums
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.access.StandardNpcAccessContext
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.events.NpcQueueEvents
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.processor.StandardNpcHitProcessor
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.interact.PlayerInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContext
import org.rsmod.api.player.quiver
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.specials.SpecialAttack
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackRegistry
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.weapon.SpecialAttackWeapons
import org.rsmod.content.other.special.attacks.melee.DragonLongswordSpecialAttack
import org.rsmod.content.other.special.attacks.melee.HalberdSpecialAttack
import org.rsmod.content.other.special.attacks.ranged.DarkBowSpecialAttack
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

/** Real registrations, queues, modifiers, impact processing and tenths-of-XP storage. */
@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class PhysicalSpecialAttackXpTest {
    @Test
    fun `longsword XP follows both signed native armour baselines exactly once`() {
        for ((symbol, expected) in listOf("npc.wyrm_light" to 7, "npc.earth_nagua" to 12)) {
            val f = Fixture(Kind.Longsword, symbol)
            f.rolls(8)
            assertEquals(listOf(expected), f.attack().map { it.damage })
            f.assertXp("stat.attack", expected * 40, (expected * 13.3).toInt())
            val xp = f.xp("stat.attack")
            assertEquals(listOf(expected), f.impact().map { it.damage })
            assertEquals(xp, f.xp("stat.attack"), "Impact must not award XP a second time")
            assertEquals(1, f.modifierCalls)
            f.assertConsumed()
        }
    }

    @Test
    fun `misses and immunity give no physical XP against negative armour`() {
        val miss = Fixture(Kind.Longsword, "npc.earth_nagua")
        miss.accuracy(false)
        assertEquals(listOf(0), miss.attack().map { it.damage })
        miss.assertXp("stat.attack", 0, 0)
        miss.assertConsumed()

        val immune = Fixture(Kind.Longsword, "npc.earth_nagua")
        immune.npc.vars["varn.immune_melee"] = 1
        immune.rolls(8)
        assertEquals(listOf(0), immune.attack().map { it.damage })
        immune.assertXp("stat.attack", 0, 0)
        immune.assertConsumed()

        val absorbed = Fixture(Kind.Longsword, "npc.wyrm_light")
        absorbed.rolls(1)
        assertEquals(listOf(0), absorbed.attack().map { it.damage })
        absorbed.assertXp("stat.attack", 0, 0)
        absorbed.assertConsumed()
    }

    @Test
    fun `longsword caps modified damage to HP before actual XP rate multipliers`() {
        val f = Fixture(Kind.Longsword, "npc.earth_nagua")
        f.npc.hitpoints = 3
        f.npc.combatXpMultiplier = 1125
        f.source.xpRate = 2.0
        f.source.globalXpRate = 1.5
        f.rolls(8)

        assertEquals(listOf(12), f.attack().map { it.damage })
        // 3 HP * 4 XP * 1.125 NPC rate * 2 personal * 1.5 world, stored in tenths.
        f.assertXp("stat.attack", 405, 134)
        assertEquals(listOf(3), f.impact().map { it.damage })
        f.assertConsumed()
    }

    @Test
    fun `controlled melee splits post-armour XP into real attack strength and defence stats`() {
        val f = Fixture(Kind.Longsword, "npc.earth_nagua", meleeStyle = MeleeAttackStyle.Controlled)
        f.rolls(8)
        f.attack()
        for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.hitpoints")) {
            assertEquals(159, f.xp(stat), stat)
        }
        f.assertConsumed()
    }

    @Test
    fun `large halberd target combines modified hits before one HP cap and XP rounding`() {
        val f = Fixture(Kind.Halberd)
        assertTrue(f.npc.size > 1)
        f.npc.vars["varn.flat_armour"] = -2
        f.npc.hitpoints = 7
        f.rolls(3, 3)

        assertEquals(listOf(5, 5), f.attack().map { it.damage })
        f.assertXp("stat.attack", 280, 93)
        assertEquals(listOf(5, 2), f.impact().map { it.damage })
        assertEquals(2, f.modifierCalls)
        f.assertConsumed()
    }

    @Test
    fun `halberd neighbouring NPCs use their own modified damage and HP cap`() {
        val f = Fixture(Kind.Halberd, "npc.man")
        assertEquals(1, f.npc.size)
        f.multiway = true
        f.npc.vars["varn.flat_armour"] = 1
        val neighbour = f.addNpc("npc.man", CoordGrid(2272, 4695)).apply {
            vars["varn.flat_armour"] = -2
            hitpoints = 5
        }
        val distant = f.addNpc("npc.man", CoordGrid(2280, 4695))
        f.rolls(4, 4)

        assertEquals(listOf(3), f.attack().map { it.damage })
        assertEquals(listOf(6), f.queued(neighbour).map { it.damage })
        assertTrue(f.queued(distant).isEmpty())
        // Separate targets keep separate HP caps and award rounding: 3 + min(6,5).
        f.assertXp("stat.attack", 320, 39 + 66)
        assertEquals(2, f.modifierCalls)
        f.assertConsumed()
    }

    @Test
    fun `both dark bow ammunition paths include the modified secondary hit in XP`() {
        for (kind in listOf(Kind.DarkBowRune, Kind.DarkBowDragon)) {
            for ((symbol, expected) in listOf("npc.wyrm_light" to listOf(7, 8), "npc.earth_nagua" to listOf(12, 13))) {
                val f = Fixture(kind, symbol)
                f.rolls(8, 9)
                assertEquals(expected, f.attack().map { it.damage })
                val total = expected.sum()
                f.assertXp("stat.ranged", total * 40, (total * 13.3).toInt())
                assertEquals(expected, f.impact().map { it.damage })
                assertEquals(2, f.modifierCalls)
                f.assertAmmoConsumed()
                f.assertConsumed()
            }
        }
    }

    @Test
    fun `dark bow combines hits before one HP cap and longrange XP split`() {
        for (kind in listOf(Kind.DarkBowRune, Kind.DarkBowDragon)) {
            val f = Fixture(kind, "npc.earth_nagua", rangedStyle = RangedAttackStyle.Longrange)
            f.npc.hitpoints = 6
            f.rolls(8, 9)
            assertEquals(listOf(12, 13), f.attack().map { it.damage })
            f.assertXp("stat.ranged", 120, 79)
            assertEquals(120, f.xp("stat.defence"))
            f.assertAmmoConsumed()
            f.assertConsumed()
        }
    }

    @Test
    fun `dark bow first miss contributes no XP while accurate secondary uses negative armour`() {
        for (kind in listOf(Kind.DarkBowRune, Kind.DarkBowDragon)) {
            val f = Fixture(kind, "npc.earth_nagua")
            f.accuracy(false, true)
            f.random.expect(0..10, 8)
            assertEquals(listOf(0, 12), f.attack().map { it.damage })
            f.assertXp("stat.ranged", 480, 159)
            assertEquals(2, f.modifierCalls)
            f.assertAmmoConsumed()
            f.assertConsumed()
        }
    }

    @Test
    fun `all shared handlers retain raw PvP XP when protection prayer reduces queued damage`() {
        for (kind in Kind.entries) {
            val f = Fixture(kind, pvp = true)
            val ranged = kind == Kind.DarkBowRune || kind == Kind.DarkBowDragon
            VarPlayerIntMapSetter.set(f.opponent, if (ranged) "varbit.prayer_protectfrommissiles" else "varbit.prayer_protectfrommelee", 1)
            val count = if (ranged) 2 else 1
            f.rolls(*IntArray(count) { 10 })

            assertEquals(List(count) { 6 }, f.attack().map { it.damage })
            f.assertXp(if (ranged) "stat.ranged" else "stat.attack", count * 400, count * 133)
            if (ranged) f.assertAmmoConsumed()
            f.assertConsumed()
        }
    }

    private enum class Kind { Longsword, Halberd, DarkBowRune, DarkBowDragon }

    private class Fixture(
        val kind: Kind,
        npcSymbol: String = "npc.king_dragon",
        val pvp: Boolean = false,
        meleeStyle: MeleeAttackStyle = MeleeAttackStyle.Accurate,
        rangedStyle: RangedAttackStyle = RangedAttackStyle.Accurate,
    ) {
        val events = EventBus()
        val random = StrictRandom()
        val source = player(1)
        val opponent = player(2)
        private val players = PlayerList().also { it[1] = source; it[2] = opponent }
        private val npcs = NpcList()
        val npc = addNpc(npcSymbol, CoordGrid(2271, 4695))
        private val accuracyResults = ArrayDeque<Boolean>()
        private val accuracy = mockk<AccuracyFormulae>()
        private val maxima = mockk<MaxHitFormulae>()
        private val world = mockk<WorldRepository>(relaxUnitFun = true)
        private val npcInteractions = mockk<NpcInteractions>(relaxUnitFun = true)
        private val playerInteractions = mockk<PlayerInteractions>(relaxUnitFun = true)
        private val npcModifier = StandardNpcHitModifier(events)
        private val manager = PlayerAttackManager(
            random, events, world, accuracy, maxima, npcModifier, StandardPlayerHitModifier(events),
            npcInteractions, mockk(), playerInteractions, mockk(), emptySet(),
        )
        private val catalog = mockk<SpecialAttackWeapons>()
        private val registry = SpecialAttackRegistry(catalog)
        private val ammunition = mockk<RangedAmmoManager>()
        private val wrapper = SpecialAttackManager(mockk(), catalog, manager)
        private val weapon = item(when (kind) {
            Kind.Longsword -> "obj.dragon_longsword"
            Kind.Halberd -> "obj.dragon_halberd"
            Kind.DarkBowRune, Kind.DarkBowDragon -> "obj.darkbow"
        })
        private val melee = CombatAttack.Melee(InvObj(weapon), MeleeAttackType.Slash, meleeStyle, CombatStance.Stance1)
        private val ranged = CombatAttack.Ranged(InvObj(weapon), RangedAttackType.Standard, rangedStyle)
        private val sourceAccess = access()
        private val impacts = mutableListOf<Hit>()
        var multiway = false
        var modifierCalls = 0
            private set

        init {
            source.worn[weapon.wearpos1] = InvObj(weapon)
            every { catalog.getSpecialEnergy(any()) } answers {
                SaEnums.sa_energy_requirements[checkNotNull(ServerCacheManager.getItem(firstArg<Int>()))]
            }
            every { maxima.getMeleeMaxHit(source, any<Npc>(), any(), any(), any(), any()) } returns 10
            every { maxima.getMeleeMaxHit(source, opponent, any(), any(), any(), any()) } returns 10
            every { maxima.getRangedMaxHit(source, any<Npc>(), any(), any(), any(), any()) } returns 10
            every { maxima.getRangedMaxHit(source, opponent, any(), any(), any(), any()) } returns 10
            every { accuracy.rollMeleeAccuracy(source, any<Npc>(), any(), any(), any(), any(), random) } answers { nextAccuracy() }
            every { accuracy.rollMeleeAccuracy(source, opponent, any(), any(), any(), any(), random) } answers { nextAccuracy() }
            every { accuracy.rollRangedAccuracy(source, any<Npc>(), any(), any(), any(), any(), random) } answers { nextAccuracy() }
            every { accuracy.rollRangedAccuracy(source, opponent, any(), any(), any(), random) } answers { nextAccuracy() }
            every { world.projAnim(source, any<Npc>(), any(), any()) } answers {
                ProjAnim.fromBoundsToNpc(source.bounds(), secondArg(), thirdArg<SpotanimType>().id, arg(3))
            }
            every { world.projAnim(source, any<Player>(), any(), any()) } answers {
                ProjAnim.fromBoundsToPlayer(source.bounds(), secondArg(), thirdArg<SpotanimType>().id, arg(3))
            }
            every { ammunition.attemptAmmoUsage(source, weapon, any()) } returns true
            every { ammunition.useQuiverAmmo(source, any(), any(), any(), any(), any(), any()) } answers {
                val quiver = checkNotNull(source.quiver)
                source.quiver = InvObj(checkNotNull(ServerCacheManager.getItem(quiver.id)), quiver.count - arg<Int>(6))
            }
            val repository = SpecialAttackRepository(registry)
            when (kind) {
                Kind.Longsword -> with(DragonLongswordSpecialAttack()) { repository.register(wrapper) }
                Kind.Halberd -> with(HalberdSpecialAttack(world, npcs)) { repository.register(wrapper) }
                Kind.DarkBowRune, Kind.DarkBowDragon -> {
                    val ammo = item(if (kind == Kind.DarkBowDragon) "obj.dragon_arrow" else "obj.rune_arrow")
                    source.quiver = InvObj(ammo, 10)
                    with(DarkBowSpecialAttack(ammunition)) { repository.register(wrapper) }
                }
            }
            val context = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
            with(NpcHitScript()) { context.startup() }
            events.subscribeKeyed(NpcHitEvents.Modify::class.java, npc.id.toLong()) { modifierCalls++ }
            events.subscribeKeyed(NpcHitEvents.Impact::class.java, npc.id.toLong()) { impacts += hit }
        }

        fun addNpc(symbol: String, coords: CoordGrid): Npc = Npc(symbol, coords).apply {
            slotId = npcs.count() + 1
            assignUid()
            currentMapClock = 100
            // Isolate post-armour XP input from the independent spawn formula. Production Create
            // supplies this per-thousand rate; explicit 1000 here is the normal 1x test baseline.
            combatXpMultiplier = 1000
            npcs[slotId] = this
        }

        fun accuracy(vararg values: Boolean) { values.forEach(accuracyResults::addLast) }
        fun rolls(vararg values: Int) {
            accuracy(*BooleanArray(values.size) { true })
            values.forEach { random.expect(0..10, it) }
        }
        private fun nextAccuracy(): Boolean = checkNotNull(accuracyResults.removeFirstOrNull()) { "Unexpected accuracy reroll" }

        fun attack(): List<Hit> {
            val target: PathingEntity = if (pvp) opponent else npc
            runImmediately {
                val special = checkNotNull(registry[InvObj(weapon)])
                assertTrue(when (special) {
                    is SpecialAttack.Melee -> special.attack(sourceAccess, target, melee)
                    is SpecialAttack.Ranged -> special.attack(sourceAccess, target, ranged)
                    else -> error("Expected registered physical special")
                })
            }
            return queued(target)
        }

        fun queued(target: PathingEntity = npc): List<Hit> = buildList {
            when (target) {
                is Npc -> {
                    val iterator = target.queueList.iterator()
                    while (iterator != null && iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
                    }
                }
                is Player -> {
                    val iterator = target.queueList.iterator()
                    while (iterator != null && iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
                    }
                }
            }
        }

        fun impact(): List<Hit> {
            val queued = queued()
            npc.queueList.removeAll("queue.hit")
            val processor = StandardNpcHitProcessor(players, events, emptySet(), emptySet())
            val access = StandardNpcAccess(
                npc, GameCoroutine("special-xp-impact"),
                StandardNpcAccessContext({ random }, { npcModifier }, { processor }),
            )
            for (hit in queued) runImmediately {
                assertTrue(events.publish(access, NpcQueueEvents.Default(npc, hit, "queue.hit".asRSCM())))
            }
            assertEquals(queued.size, impacts.size)
            return impacts.toList()
        }

        fun xp(stat: String): Int = source.statMap.getFineXP(stat)
        fun assertXp(stat: String, expected: Int, hitpoints: Int) {
            assertEquals(expected, xp(stat), stat)
            assertEquals(hitpoints, xp("stat.hitpoints"), "Hitpoints XP in tenths")
        }
        fun assertAmmoConsumed() {
            assertEquals(8, source.quiver?.count)
            verify(exactly = 2) { ammunition.useQuiverAmmo(source, any(), any(), any(), any(), any(), any()) }
        }
        fun assertConsumed() {
            assertTrue(accuracyResults.isEmpty(), "Unused accuracy outcomes")
            random.assertConsumed()
        }

        private fun access(): ProtectedAccess {
            val areas = mockk<AreaChecker>()
            every { areas.inArea("area.multiway", any()) } answers { multiway }
            val context = mockk<ProtectedAccessContext>()
            every { context.eventBus } returns events
            every { context.random } returns random
            every { context.areaChecker } returns areas
            return ProtectedAccess(source, GameCoroutine("special-xp-player"), context)
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
            // Avoid unrelated level-up UI while using the actual XP storage path from zero.
            for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.ranged", "stat.hitpoints")) {
                statMap.setBaseLevel(stat, 99)
                statMap.setCurrentLevel(stat, 99)
            }
            xpRate = 1.0
            globalXpRate = 1.0
        }
    }

    private class StrictRandom : GameRandom {
        private val expected = ArrayDeque<Pair<IntRange, Int>>()
        fun expect(range: IntRange, value: Int) { require(value in range); expected.addLast(range to value) }
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            val (range, result) = checkNotNull(expected.removeFirstOrNull()) { "Unexpected damage reroll" }
            assertEquals(range, minInclusive..maxInclusive)
            return result
        }
        override fun of(maxExclusive: Int): Int = error("Unexpected exclusive roll: $maxExclusive")
        override fun randomDouble(): Double = error("Unexpected floating-point roll")
        fun assertConsumed() = assertTrue(expected.isEmpty(), "Unused damage rolls: $expected")
    }

    companion object {
        @JvmStatic @BeforeAll fun cache() { ServerCacheManager.init(241).close() }
        private fun item(symbol: String) = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))
        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Special attack unexpectedly suspended" }.getOrThrow()
        }
    }
}
