package org.rsmod.content.other.special.weapons.ranged

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.aconverted.SpotanimType
import dev.openrune.util.Wearpos
import io.mockk.every
import io.mockk.mockk
import java.util.IdentityHashMap
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
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.formulas.accuracy.ranged.PvNRangedAccuracy
import org.rsmod.api.combat.formulas.accuracy.ranged.PvPRangedAccuracy
import org.rsmod.api.combat.formulas.attributes.collector.CombatNpcAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatRangedAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.ranged.PvNRangedMaxHit
import org.rsmod.api.combat.formulas.maxhit.ranged.PvPRangedMaxHit
import org.rsmod.api.combat.manager.CombatChargeManager
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.death.NpcAttackValidateHook
import org.rsmod.api.death.NpcAttackValidateResult
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.access.StandardNpcAccessContext
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.events.NpcQueueEvents
import org.rsmod.api.npc.hit.flatArmour
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.processor.StandardNpcHitProcessor
import org.rsmod.api.npc.plugin.AiSpawnScript
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.interact.PlayerInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContext
import org.rsmod.api.player.quiver
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.npc.NpcRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.api.weapons.attack
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
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
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.utils.bits.withBits

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class RangedWeaponXpTest {
    @Test
    fun `dark bow single and double arrows award adjusted NPC damage once before impact`() {
        for (count in listOf(1, 2)) {
            for ((symbol, armour) in listOf("npc.earth_nagua" to -4, "npc.wyrm_light" to 1)) {
                val f = Fixture(Kind.DarkBow, symbol, arrows = count)
                assertEquals(armour, f.primary.flatArmour)
                repeat(count) { f.roll(f.primary, 3) }

                f.attack()

                assertEquals(List(count) { 3 - armour }, f.queued(f.primary).map { it.damage })
                f.assertXp(f.primary to count * (3 - armour))
                val hp = f.primary.hitpoints
                assertEquals(List(count) { 3 - armour }, f.impact(f.primary).map { it.damage })
                assertEquals(hp - count * (3 - armour), f.primary.hitpoints)
                f.assertXp(f.primary to count * (3 - armour))
                f.assertConsumed(ammo = count, charge = 0)
            }
        }
    }

    @Test
    fun `dark bow double shot shares remaining HP and fine XP rounding across arrows`() {
        val f = Fixture(Kind.DarkBow, "npc.earth_nagua")
        f.primary.hitpoints = 9
        f.roll(f.primary, 1)
        f.roll(f.primary, 2)

        f.attack()

        assertEquals(listOf(5, 6), f.queued(f.primary).map { it.damage })
        f.assertXp(f.primary to 9)
        assertEquals(listOf(5, 4), f.impact(f.primary).map { it.damage })
        assertEquals(0, f.primary.hitpoints)
        f.assertXp(f.primary to 9)
        f.assertConsumed(ammo = 2, charge = 0)
    }

    @Test
    fun `dark bow native armour zeroes low hits without XP and explicit zero restores damage`() {
        for (override in listOf(false, true)) {
            val f = Fixture(Kind.DarkBow, "npc.wyrm_light")
            if (override) f.primary.flatArmour = 0
            f.roll(f.primary, 1)
            f.roll(f.primary, 1)

            f.attack()

            val damage = if (override) 1 else 0
            assertEquals(listOf(damage, damage), f.queued(f.primary).map { it.damage })
            f.assertXp(f.primary to damage * 2)
            f.assertConsumed(ammo = 2, charge = 0)
        }
    }

    @Test
    fun `ranged styles and profile multipliers use adjusted damage`() {
        for (style in RangedAttackStyle.entries) {
            val f = Fixture(Kind.DarkBow, "npc.wyrm_light", style = style)
            f.source.xpRate = 1.5
            f.source.globalXpRate = 2.0
            f.roll(f.primary, 2)
            f.roll(f.primary, 3)

            f.attack()

            assertEquals(listOf(1, 2), f.queued(f.primary).map { it.damage })
            f.assertXp(f.primary to 3)
            f.assertConsumed(ammo = 2, charge = 0)
        }
    }

    @Test
    fun `venator primary keeps negative armour bonus while returning bounce excludes it from damage and XP`() {
        val f = Fixture(Kind.Venator, "npc.earth_nagua")
        val primaryHp = f.primary.hitpoints
        val secondaryHp = f.secondary.hitpoints
        assertTrue(primaryHp > 7 && secondaryHp > 2)
        f.roll(f.primary, 1)
        f.roll(f.secondary, 3, multiplier = 2.0 / 3.0)
        f.roll(f.primary, 2, multiplier = 2.0 / 3.0)

        f.attack()

        assertEquals(listOf(5, 2), f.queued(f.primary).map { it.damage })
        assertEquals(listOf(2), f.queued(f.secondary).map { it.damage })
        f.assertXp(f.primary to 5, f.secondary to 2, f.primary to 2)
        assertEquals(listOf(5, 2), f.impact(f.primary).map { it.damage })
        assertEquals(listOf(2), f.impact(f.secondary).map { it.damage })
        assertEquals(primaryHp - 7, f.primary.hitpoints)
        assertEquals(secondaryHp - 2, f.secondary.hitpoints)
        f.assertXp(f.primary to 5, f.secondary to 2, f.primary to 2)
        assertEquals(3, f.projectiles.size)
        f.assertConsumed(ammo = 1, charge = 1)
    }

    @Test
    fun `venator queues separate healthy NPC instances with the same native type`() {
        val f = Fixture(Kind.Venator, "npc.earth_nagua", secondarySymbol = "npc.earth_nagua")
        val primaryHp = f.primary.hitpoints
        val secondaryHp = f.secondary.hitpoints
        assertTrue(primaryHp > 7 && secondaryHp > 3)
        f.roll(f.primary, 1)
        f.roll(f.secondary, 3, multiplier = 2.0 / 3.0)
        f.roll(f.primary, 2, multiplier = 2.0 / 3.0)

        f.attack()

        assertEquals(listOf(5, 2), f.queued(f.primary).map { it.damage })
        assertEquals(listOf(3), f.queued(f.secondary).map { it.damage })
        f.assertXp(f.primary to 5, f.secondary to 3, f.primary to 2)
        assertEquals(listOf(5, 2), f.impact(f.primary).map { it.damage })
        assertEquals(listOf(3), f.impact(f.secondary).map { it.damage })
        assertEquals(primaryHp - 7, f.primary.hitpoints)
        assertEquals(secondaryHp - 3, f.secondary.hitpoints)
        f.assertXp(f.primary to 5, f.secondary to 3, f.primary to 2)
        f.assertConsumed(ammo = 1, charge = 1)
    }

    @Test
    fun `venator positive armour reduces primary and both bounces including a zero hit`() {
        val f = Fixture(Kind.Venator, "npc.wyrm_light")
        val primaryHp = f.primary.hitpoints
        val secondaryHp = f.secondary.hitpoints
        assertEquals(1, f.primary.flatArmour)
        assertEquals(1, f.secondary.flatArmour)
        f.roll(f.primary, 3)
        f.roll(f.secondary, 1, multiplier = 2.0 / 3.0)
        f.roll(f.primary, 2, multiplier = 2.0 / 3.0)

        f.attack()

        assertEquals(listOf(2, 1), f.queued(f.primary).map { it.damage })
        assertEquals(listOf(0), f.queued(f.secondary).map { it.damage })
        f.assertXp(f.primary to 2, f.secondary to 0, f.primary to 1)
        assertEquals(listOf(2, 1), f.impact(f.primary).map { it.damage })
        assertEquals(listOf(0), f.impact(f.secondary).map { it.damage })
        assertEquals(primaryHp - 3, f.primary.hitpoints)
        assertEquals(secondaryHp, f.secondary.hitpoints)
        f.assertXp(f.primary to 2, f.secondary to 0, f.primary to 1)
        assertEquals(3, f.projectiles.size)
        f.assertConsumed(ammo = 1, charge = 1)
    }

    @Test
    fun `venator single combat awards primary only and preserves a single ammo and charge use`() {
        val f = Fixture(Kind.Venator, "npc.earth_nagua", multiway = false)
        f.roll(f.primary, 3)

        f.attack()

        assertEquals(listOf(7), f.queued(f.primary).map { it.damage })
        assertTrue(f.queued(f.secondary).isEmpty())
        f.assertXp(f.primary to 7)
        assertEquals(1, f.projectiles.size)
        f.assertConsumed(ammo = 1, charge = 1)
    }

    @Test
    fun `ranged accuracy misses never turn native negative armour into XP`() {
        for (kind in Kind.entries) {
            val f = Fixture(kind, "npc.earth_nagua", secondarySymbol = "npc.earth_nagua")
            f.roll(f.primary, damage = null)
            if (kind == Kind.DarkBow) f.roll(f.primary, damage = null)
            else {
                f.roll(f.secondary, damage = null)
                f.roll(f.primary, damage = null)
            }

            f.attack()

            assertTrue(f.queued(f.primary).all { it.damage == 0 })
            assertTrue(f.queued(f.secondary).all { it.damage == 0 })
            f.assertXp(f.primary to 0, f.secondary to 0)
            f.assertConsumed(ammo = if (kind == Kind.DarkBow) 2 else 1, charge = if (kind == Kind.Venator) 1 else 0)
        }
    }

    @Test
    fun `both ranged handlers retain raw PvP XP when prayer reduces queued minimum hits to zero`() {
        for (kind in Kind.entries) {
            val f = Fixture(kind, "npc.earth_nagua", pvp = true)
            VarPlayerIntMapSetter.set(f.opponent, "varbit.prayer_protectfrommissiles", 1)
            val hits = if (kind == Kind.DarkBow) 2 else 1
            repeat(hits) { f.roll(f.opponent, 1) }

            f.attack()

            assertEquals(List(hits) { 0 }, f.queued(f.opponent).map { it.damage })
            f.assertPvpXp(hits)
            assertTrue(f.queued(f.primary).isEmpty())
            assertTrue(f.queued(f.secondary).isEmpty())
            f.assertConsumed(ammo = hits, charge = if (kind == Kind.Venator) 1 else 0)
        }
    }

    internal enum class Kind { DarkBow, Venator }

    internal class Fixture(
        kind: Kind,
        primarySymbol: String,
        secondarySymbol: String = "npc.wyrm_light",
        arrows: Int = 10,
        val style: RangedAttackStyle = RangedAttackStyle.Rapid,
        multiway: Boolean = true,
        val pvp: Boolean = false,
        private val realCombat: Boolean = false,
        private val chargeCount: Int = 10,
    ) {
        private val events = EventBus()
        private val random = DamageRandom(realCombat)
        private val clock = MapClock(100)
        private val collision = CollisionFlagMap()
        private val initialArrows = arrows
        private val styles = AttackStyles()
        val bonuses = WornBonuses()
        private val speeds = WeaponSpeeds(styles)
        private val npcAttributes = CombatNpcAttributeCollector()
        private val rangedAttributes = CombatRangedAttributeCollector()
        val source = player(1)
        val opponent = player(2)
        private val players = PlayerList().also { it[1] = source; it[2] = opponent }
        val primary = npc(primarySymbol, 1, CoordGrid(2271, 4695))
        val secondary = npc(secondarySymbol, 2, primary.coords.translate(1, 0))
        val npcs = NpcList()
        val npcRegistry = NpcRegistry(npcs, collision, events)
        private val accuracy = if (realCombat) AccuracyFormulae(
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            PvNRangedAccuracy(bonuses, npcAttributes, rangedAttributes),
            PvPRangedAccuracy(bonuses, styles, rangedAttributes),
            mockk(), mockk(), mockk(), mockk(),
        ) else mockk<AccuracyFormulae>()
        private val maxima = if (realCombat) MaxHitFormulae(
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            PvNRangedMaxHit(bonuses, speeds, npcAttributes, rangedAttributes),
            PvPRangedMaxHit(random, bonuses, speeds, rangedAttributes, DamageReductionAttributeCollector(styles)),
            mockk(), mockk(),
        ) else mockk<MaxHitFormulae>()
        private val world = mockk<WorldRepository>()
        private val npcRepo = NpcRepository(clock, npcRegistry, npcs)
        private val ammunition = RangedAmmoManager(NoAmmoDropRandom, events, collision, WorldQueueList(), mockk())
        private val charges = CombatChargeManager(ObjChargeManager())
        private val areas = mockk<AreaChecker>()
        private val npcInteractions = mockk<NpcInteractions>()
        private val playerInteractions = mockk<PlayerInteractions>()
        private val modifier = StandardNpcHitModifier(events)
        private val processor = StandardNpcHitProcessor(players, events, emptySet(), emptySet())
        private val manager = PlayerAttackManager(
            random, events, world, accuracy, maxima, modifier, StandardPlayerHitModifier(events),
            npcInteractions, mockk(), playerInteractions, mockk(), emptySet(),
        )
        private val registry = WeaponRegistry()
        val weaponType = checkNotNull(ServerCacheManager.getItem(
            (if (kind == Kind.DarkBow) "obj.darkbow" else "obj.venator_bow").asRSCM(),
        ))
        private val weapon = InvObj(weaponType)
        val ammoType = checkNotNull(ServerCacheManager.getItem("obj.rune_arrow".asRSCM()))
        private val attack = CombatAttack.Ranged(weapon, RangedAttackType.Standard, style)
        private val rolls = ArrayDeque<Roll>()
        private val maxRolls = ArrayDeque<Roll>()
        private val access = access()
        val projectiles = mutableListOf<ProjAnim>()
        var validateTarget: (Npc) -> NpcAttackValidateResult = { NpcAttackValidateResult.Pass }
        var beforeQueue: (Npc) -> Unit = {}
        private val modifyTypes = mutableSetOf<Int>()
        val sampledDamageRanges: List<IntRange> get() = random.sampledRanges
        var continuations = 0
            private set

        init {
            val chargeBits = checkNotNull(ServerCacheManager.getVarObj("varobj.venator_bow_charges".asRSCM())).bits
            source.worn[weaponType.wearpos1] = if (kind == Kind.Venator) weapon.copy(vars = 0.withBits(chargeBits, chargeCount)) else weapon
            source.worn[Wearpos.Quiver.slot] = if (arrows > 0) InvObj(ammoType, arrows) else null
            every { areas.inArea("area.multiway", source.coords) } returns multiway
            every { npcInteractions.interact(source, any(), any()) } answers { continuations++ }
            every { playerInteractions.interact(source, opponent, any()) } answers { continuations++ }
            if (!realCombat) {
                every { accuracy.rollRangedAccuracy(source, any<Npc>(), attack.type, style, attack.type, 1.0, random) } answers { nextAccuracy(secondArg()) }
                every { accuracy.rollRangedAccuracy(source, opponent, attack.type, style, 1.0, random) } answers { nextAccuracy(opponent) }
                every { maxima.getRangedMaxHit(source, any<Npc>(), attack.type, style, any(), 0) } answers { nextMaximum(secondArg(), arg(4)) }
                every { maxima.getRangedMaxHit(source, opponent, attack.type, style, any(), 0) } answers { nextMaximum(opponent, arg(4)) }
            }
            every { world.projAnim(source, any<Npc>(), any<SpotanimType>(), any()) } answers {
                ProjAnim.fromBoundsToNpc(source.bounds(), secondArg(), thirdArg<SpotanimType>().id, arg(3)).also(projectiles::add)
            }
            every { world.projAnim(source, opponent, any<SpotanimType>(), any()) } answers {
                ProjAnim.fromBoundsToPlayer(source.bounds(), opponent, thirdArg<SpotanimType>().id, arg(3)).also(projectiles::add)
            }
            every { world.projAnim(any<ProjAnim>()) } answers { projectiles += firstArg<ProjAnim>() }
            val repository = WeaponRepository(registry)
            val wrapper = WeaponAttackManager(manager)
            when (kind) {
                Kind.DarkBow -> with(DarkBowWeapons(ammunition)) { repository.register(wrapper) }
                Kind.Venator -> with(VenatorBowWeapons(ammunition, charges, npcRepo, world, setOf(NpcAttackValidateHook { _, npc -> validateTarget(npc) }))) { repository.register(wrapper) }
            }
            val context = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
            with(AiSpawnScript()) { context.startup() }
            with(NpcHitScript()) { context.startup() }
            with(WeaponAttackStylesScript(styles)) { context.startup() }
            addNpc(primary)
            addNpc(secondary)
        }

        fun addNpc(npc: Npc): Npc {
            for (dx in -16..16 step 8) for (dz in -16..16 step 8) {
                collision.allocateIfAbsent(npc.coords.x + dx, npc.coords.z + dz, npc.coords.level)
            }
            npcRegistry.add(npc)
            assertTrue(npc.combatXpMultiplier > 0)
            if (modifyTypes.add(npc.visType.id)) {
                events.subscribeKeyed(NpcHitEvents.Modify::class.java, npc.visType.id.toLong()) { beforeQueue(this.npc) }
            }
            return npc
        }

        fun move(npc: Npc, coords: CoordGrid) {
            npcRegistry.change(npc, org.rsmod.map.zone.ZoneKey.from(npc.coords), org.rsmod.map.zone.ZoneKey.from(coords))
            npc.coords = coords
            npc.lastProcessedZone = org.rsmod.map.zone.ZoneKey.from(coords)
        }

        fun remainingCharges(): Int = charges.getWeaponCharges(source, "varobj.venator_bow_charges")

        fun roll(target: PathingEntity, damage: Int?, multiplier: Double = 1.0) {
            rolls.addLast(Roll(target, damage, multiplier))
        }

        private fun nextAccuracy(target: PathingEntity): Boolean {
            val roll = checkNotNull(rolls.removeFirstOrNull()) { "Unexpected accuracy check" }
            assertTrue(roll.target === target, "Accuracy target differs from queued attack order")
            if (roll.damage != null) maxRolls.addLast(roll)
            return roll.damage != null
        }

        private fun nextMaximum(target: PathingEntity, multiplier: Double): Int {
            val roll = checkNotNull(maxRolls.removeFirstOrNull()) { "Unexpected maximum calculation" }
            assertTrue(roll.target === target)
            assertEquals(roll.multiplier, multiplier)
            val maximum = (9 * multiplier).toInt()
            random.draws.addLast((0..maximum) to checkNotNull(roll.damage))
            return maximum
        }

        fun attack() {
            val handler = checkNotNull(registry.getRanged(weapon))
            runImmediately {
                assertTrue(if (pvp) handler.attack(access, opponent, attack) else handler.attack(access, primary, attack))
            }
        }

        fun queued(target: PathingEntity): List<Hit> = buildList {
            when (target) {
                is Npc -> {
                    val iterator = target.queueList.iterator() ?: return@buildList
                    while (iterator.hasNext()) iterator.next().let { if (it.id == "queue.hit".asRSCM()) add(it.args as Hit) }
                }
                is Player -> {
                    val iterator = target.queueList.iterator() ?: return@buildList
                    while (iterator.hasNext()) iterator.next().let { if (it.id == "queue.hit".asRSCM()) add(it.args as Hit) }
                }
            }
        }

        private val impactTypes = mutableSetOf<Int>()
        private val impactsByNpc = IdentityHashMap<Npc, MutableList<Hit>>()

        fun impact(npc: Npc): List<Hit> {
            val queued = queued(npc)
            npc.queueList.removeAll("queue.hit")
            val impacts = mutableListOf<Hit>()
            impactsByNpc[npc] = impacts
            if (impactTypes.add(npc.visType.id)) {
                events.subscribeKeyed(NpcHitEvents.Impact::class.java, npc.visType.id.toLong()) {
                    impactsByNpc.getOrPut(this.npc) { mutableListOf() } += hit
                }
            }
            val access = StandardNpcAccess(npc, GameCoroutine("bow-npc"), StandardNpcAccessContext({ random }, { modifier }, { processor }))
            for (hit in queued) runImmediately {
                assertTrue(events.publish(access, NpcQueueEvents.Default(npc, hit, "queue.hit".asRSCM())))
            }
            assertEquals(queued.size, impacts.size)
            return impacts
        }

        fun assertXp(vararg contributions: Pair<Npc, Int>) {
            assertFineXp(contributions.map { (npc, damage) -> damage to npc.combatXpMultiplier / 1000.0 })
        }

        fun assertPvpXp(damage: Int) { assertFineXp(listOf(damage to 1.0)) }

        private fun assertFineXp(contributions: List<Pair<Int, Double>>) {
            for (stat in listOf("stat.ranged", "stat.defence", "stat.hitpoints", "stat.attack", "stat.strength")) {
                val perDamage = when {
                    stat == "stat.hitpoints" -> 1.33
                    stat == "stat.ranged" -> if (style == RangedAttackStyle.Longrange) 2.0 else 4.0
                    stat == "stat.defence" && style == RangedAttackStyle.Longrange -> 2.0
                    else -> 0.0
                }
                var expected = PlayerSkillXPTable.getXPFromLevel(99) * 10
                for ((damage, multiplier) in contributions) {
                    expected = (expected + damage * perDamage * multiplier * (source.xpRate * source.globalXpRate) * 10).toInt()
                }
                assertEquals(expected, source.statMap.getFineXP(stat), stat)
            }
        }

        fun assertConsumed(ammo: Int, charge: Int) {
            assertTrue(rolls.isEmpty(), "Unused accuracy outcomes: $rolls")
            assertTrue(maxRolls.isEmpty(), "Unused max hit calculations: $maxRolls")
            assertTrue(random.draws.isEmpty(), "Unused damage draws: ${random.draws}")
            assertEquals(initialArrows - ammo, source.quiver?.count ?: 0)
            if (weaponType.id == "obj.venator_bow".asRSCM()) assertEquals(chargeCount - charge, remainingCharges())
        }

        private fun access(): ProtectedAccess {
            val context = mockk<ProtectedAccessContext>()
            every { context.eventBus } returns events
            every { context.playerList } returns players
            every { context.npcList } returns npcs
            every { context.areaChecker } returns areas
            return ProtectedAccess(source, GameCoroutine("bow-player"), context)
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
            for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.ranged", "stat.hitpoints")) {
                statMap.setBaseLevel(stat, 99)
                statMap.setCurrentLevel(stat, 99)
                statMap.setXP(stat, PlayerSkillXPTable.getXPFromLevel(99))
            }
            xpRate = 1.0
            globalXpRate = 1.0
        }

        private fun npc(symbol: String, slot: Int, coords: CoordGrid): Npc = Npc(symbol, coords).apply {
            slotId = slot
            assignUid()
            currentMapClock = 100
        }

        private data class Roll(val target: PathingEntity, val damage: Int?, val multiplier: Double)
    }

    private class DamageRandom(private val realCombat: Boolean) : GameRandom {
        val sampledRanges = mutableListOf<IntRange>()
        val draws = ArrayDeque<Pair<IntRange, Int>>()
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            if (realCombat) {
                sampledRanges += minInclusive..maxInclusive
                return maxInclusive
            }
            val (range, result) = checkNotNull(draws.removeFirstOrNull()) { "Unexpected damage RNG" }
            assertEquals(range, minInclusive..maxInclusive)
            assertTrue(result in range)
            return result
        }
        override fun of(maxExclusive: Int): Int {
            check(realCombat && maxExclusive == 10000) { "Unexpected exclusive RNG: $maxExclusive" }
            return 0
        }
        override fun randomDouble(): Double = error("Unexpected floating-point RNG")
    }

    private object NoAmmoDropRandom : GameRandom {
        override fun of(maxExclusive: Int): Int {
            check(maxExclusive == 5) { "Unexpected ammunition draw: $maxExclusive" }
            return 4
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unexpected ammunition range")
        override fun randomDouble(): Double = error("Unexpected ammunition double")
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
