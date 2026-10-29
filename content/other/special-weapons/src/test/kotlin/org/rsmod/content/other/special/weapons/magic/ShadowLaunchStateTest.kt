package org.rsmod.content.other.special.weapons.magic

import com.google.inject.Guice
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import dev.openrune.types.aconverted.SpotanimType
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.roundToInt
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.magic.MagicSpell
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.commons.styles.MagicAttackStyle
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.formulas.accuracy.magic.PvNMagicAccuracy
import org.rsmod.api.combat.formulas.accuracy.magic.PvPMagicAccuracy
import org.rsmod.api.combat.formulas.attributes.collector.CombatMagicAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatNpcAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.magic.PvNMagicMaxHit
import org.rsmod.api.combat.formulas.maxhit.magic.PvPMagicMaxHit
import org.rsmod.api.combat.manager.CombatChargeManager
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.refs.params
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.interact.PlayerInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackRegistry
import org.rsmod.api.spells.attack.SpellAttackRepository
import org.rsmod.api.spells.attack.attack
import org.rsmod.api.spells.runes.combo.ComboRuneRepository
import org.rsmod.api.spells.runes.combo.ComboRuneScript
import org.rsmod.api.spells.runes.compact.CompactRuneRepository
import org.rsmod.api.spells.runes.compact.CompactRuneScript
import org.rsmod.api.spells.runes.fake.FakeRuneRepository
import org.rsmod.api.spells.runes.fake.FakeRuneScript
import org.rsmod.api.spells.runes.staves.StaffSubstituteRepository
import org.rsmod.api.spells.runes.staves.StaffSubstituteScript
import org.rsmod.api.spells.runes.subs.RuneSubstituteRepository
import org.rsmod.api.spells.runes.subs.RuneSubstituteScript
import org.rsmod.api.spells.runes.unlimited.UnlimitedRuneRepository
import org.rsmod.api.spells.runes.unlimited.UnlimitedRuneScript
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.api.weapons.attack
import org.rsmod.content.skills.magic.spell.attacks.standard.ElementalSpells
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
@ResourceLock("InvTransactions")
class ShadowLaunchStateTest {
    @Test
    fun `last NPC cast retains charged accuracy damage bonuses and weapon metadata`() {
        val final = Fixture(charges = 1)
        val stacked = Fixture(charges = 20)
        val chargedMax = final.maxHit()
        val chargedChance = final.hitChance()
        assertEquals(42, chargedMax)
        assertEquals(final.staffChanceOracle(), chargedChance)
        val uncharged = Fixture(charges = 0, chargedIdentity = false)
        assertTrue(chargedMax > uncharged.maxHit(), "Fixture must expose Shadow's damage multiplier")
        assertTrue(chargedChance > uncharged.hitChance(), "Fixture must expose Shadow's accuracy multiplier")
        assertEquals(stacked.maxHit(), chargedMax)
        assertEquals(stacked.hitChance(), chargedChance)

        val finalHit = final.castAtLastSuccessfulRoll()
        val stackedHit = stacked.castAtLastSuccessfulRoll()

        assertEquals(stackedHit.damage, finalHit.damage)
        final.assertChargedSnapshot(finalHit)
        stacked.assertChargedSnapshot(stackedHit)
        final.assertWeapon(charged = false, remaining = 0)
        stacked.assertWeapon(charged = true, remaining = 19)
        assertEquals(0, final.continuations)
        assertEquals(1, stacked.continuations)
        assertEquals(stacked.magicXpGained(), final.magicXpGained())
        assertEquals(105, final.player.actionDelay)
    }

    @Test
    fun `last PvP handler cast uses raw bonuses and preserves the charged weapon snapshot`() {
        // Direct handler invocation does not establish permission to use powered staves in PvP.
        val final = Fixture(charges = 1, pvp = true)
        val stacked = Fixture(charges = 20, pvp = true)
        assertEquals(36, final.maxHit())
        assertEquals(final.staffChanceOracle(), final.hitChance())

        val finalHit = final.castAtLastSuccessfulRoll()
        val stackedHit = stacked.castAtLastSuccessfulRoll()

        assertEquals(stackedHit.damage, finalHit.damage)
        assertEquals(stacked.random.damageRanges, final.random.damageRanges)
        final.assertChargedSnapshot(finalHit)
        final.assertWeapon(charged = false, remaining = 0)
        stacked.assertWeapon(charged = true, remaining = 19)
        assertEquals(stacked.magicXpGained(), final.magicXpGained())
        assertEquals(0, final.continuations)
        assertEquals(1, stacked.continuations)
    }

    @Test
    fun `splash still consumes exactly one charge and retains the launched charged weapon`() {
        for (pvp in listOf(false, true)) {
            for (count in listOf(1, 2)) {
                val f = Fixture(charges = count, pvp = pvp)
                val chance = f.staffChanceOracle()
                assertEquals(chance, f.hitChance())
                assertTrue(chance in 1 until 10000)
                f.random.accuracyRoll = chance

                val hit = f.cast().single()

                assertEquals(0, hit.damage)
                assertTrue(hit.isMagicSplash)
                f.assertChargedSnapshot(hit)
                f.assertWeapon(charged = count > 1, remaining = count - 1)
                assertEquals(1, f.random.accuracyDraws)
                assertTrue(f.random.damageRanges.isEmpty())
                assertEquals(1, f.projectiles.size)
                assertEquals(0, f.magicXpGained())
                assertEquals(if (count == 1) 0 else 1, f.continuations)
            }
        }
    }

    @Test
    fun `empty charged identity normalizes without a cast or charge underflow`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(charges = 0, pvp = pvp)

            assertTrue(f.cast().isEmpty())
            f.assertWeapon(charged = false, remaining = 0)
            assertTrue(f.cast().isEmpty(), "Repeated action now dispatches the uncharged handler")

            f.assertNoCast()
        }
    }

    @Test
    fun `ordinary uncharged weapon cannot cast or change its identity`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(charges = 0, pvp = pvp, chargedIdentity = false)

            assertTrue(f.cast().isEmpty())

            f.assertWeapon(charged = false, remaining = 0)
            f.assertNoCast()
        }
    }

    @Test
    fun `two charges permit exactly two full strength casts before the uncharged handler stops`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(charges = 2, pvp = pvp)
            val expectedMax = if (pvp) 36 else 42
            assertEquals(expectedMax, f.maxHit())
            f.random.accuracyRoll = f.staffChanceOracle() - 1

            assertEquals(expectedMax, f.cast().single().damage)
            f.assertWeapon(charged = true, remaining = 1)
            f.advance()
            assertEquals(listOf(expectedMax, expectedMax), f.cast().map(Hit::damage))
            f.assertWeapon(charged = false, remaining = 0)
            f.advance()
            assertEquals(2, f.cast().size)

            assertEquals(listOf(0..expectedMax, 0..expectedMax), f.random.damageRanges)
            assertEquals(2, f.random.accuracyDraws)
            assertEquals(2, f.projectiles.size)
            assertEquals(1, f.continuations)
        }
    }

    @Test
    fun `manual fire surge hits use raw bonuses and spend runes without consuming the final Shadow charge`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(charges = 1, pvp = pvp)
            f.supplyFireSurgeRunes()
            f.random.accuracyRoll = f.manualChanceOracle() - 1

            val hit = f.castFireSurge().single()

            assertEquals(25, hit.damage)
            assertFalse(hit.isMagicSplash)
            f.assertFireSurgeSnapshot(hit)
            f.assertWeapon(charged = true, remaining = 1)
            assertEquals(listOf(0, 0, 0), f.runeCounts())
            assertEquals(listOf(0..25), f.random.damageRanges)
            assertEquals(1, f.random.accuracyDraws)
            assertEquals(if (pvp) 1067 else 1005, f.magicXpGained())
            assertEquals(1, f.projectiles.size)
            assertEquals(0, f.continuations)

            assertEquals(listOf(hit), f.castFireSurge(), "No runes remain for a repeated cast")
            assertEquals(1, f.random.accuracyDraws)
            assertEquals(if (pvp) 1067 else 1005, f.magicXpGained())
            f.assertWeapon(charged = true, remaining = 1)
        }
    }

    @Test
    fun `manual fire surge splashes at a roll that the old tripled accuracy would hit`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(charges = 1, pvp = pvp)
            f.supplyFireSurgeRunes()
            val rawChance = f.manualChanceOracle()
            val leakedChance = f.oldTripledManualChanceOracle()
            assertTrue(rawChance < leakedChance)
            f.random.accuracyRoll = (rawChance + leakedChance) / 2

            val hit = f.castFireSurge().single()

            assertEquals(0, hit.damage)
            assertTrue(hit.isMagicSplash)
            f.assertFireSurgeSnapshot(hit)
            f.assertWeapon(charged = true, remaining = 1)
            assertEquals(listOf(0, 0, 0), f.runeCounts())
            assertEquals(505, f.magicXpGained(), "A splash still awards the native 50.5 cast XP")
            assertEquals(1, f.random.accuracyDraws)
            assertTrue(f.random.damageRanges.isEmpty())
            assertEquals(1, f.projectiles.size)
            assertEquals(0, f.continuations)
        }
    }

    @Test
    fun `missing any fire surge rune leaves all other runes and Shadow charges unchanged`() {
        for (pvp in listOf(false, true)) {
            for (missingSlot in 0..2) {
                val f = Fixture(charges = 1, pvp = pvp)
                f.supplyFireSurgeRunes()
                f.player.inv[missingSlot] = null
                val before = f.runeCounts()

                repeat(2) { assertTrue(f.castFireSurge().isEmpty()) }

                assertEquals(before, f.runeCounts())
                f.assertWeapon(charged = true, remaining = 1)
                f.assertNoCast()
            }
        }
    }

    @Test
    fun `two manual casts consume exactly two native recipes while retaining one Shadow charge`() {
        for (pvp in listOf(false, true)) {
            val f = Fixture(charges = 1, pvp = pvp)
            f.supplyFireSurgeRunes(casts = 2)
            f.random.accuracyRoll = f.manualChanceOracle() - 1

            assertEquals(25, f.castFireSurge().single().damage)
            assertEquals(listOf(7, 10, 1), f.runeCounts())
            f.advance()
            assertEquals(listOf(25, 25), f.castFireSurge().map(Hit::damage))

            assertEquals(listOf(0, 0, 0), f.runeCounts())
            f.assertWeapon(charged = true, remaining = 1)
            assertEquals(2, f.random.accuracyDraws)
            assertEquals(listOf(0..25, 0..25), f.random.damageRanges)
            assertEquals(if (pvp) 2134 else 2010, f.magicXpGained())
        }
    }

    private class Fixture(charges: Int, val pvp: Boolean = false, chargedIdentity: Boolean = true) {
        val player = newPlayer(1, CoordGrid(2275, 4695))
        private val opponent = newPlayer(2, CoordGrid(2271, 4695)).apply {
            statMap.setCurrentLevel("stat.magic", 100)
            statMap.setCurrentLevel("stat.defence", 100)
            appearance.combatLevel = 126
        }
        private val npc = Npc("npc.king_dragon", CoordGrid(2271, 4695)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
            magicLvl = 100
            defenceLvl = 100
            // The real XP advancement is retained; NPC spawn/multiplier calculation is separate.
            combatXpMultiplier = 1000
        }
        val random = CastRandom()
        val projectiles = mutableListOf<ProjAnim>()
        var continuations = 0
            private set
        private val events = EventBus()
        private val objCharges = ObjChargeManager()
        private val combatCharges = CombatChargeManager(objCharges)
        private val chargedType = item("obj.tumekens_shadow")
        private val unchargedType = item("obj.tumekens_shadow_uncharged")
        private val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        private val bonuses = WornBonuses()
        private val magicAttributes = CombatMagicAttributeCollector()
        private val npcAttributes = CombatNpcAttributeCollector()
        private val accuracy = AccuracyFormulae(
            mockk(), mockk(), PvNMagicAccuracy(random, bonuses, magicAttributes, npcAttributes),
            PvPMagicAccuracy(random, bonuses, styles, magicAttributes),
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
        )
        private val maxima = MaxHitFormulae(
            PvNMagicMaxHit(random, bonuses, npcAttributes, magicAttributes),
            PvPMagicMaxHit(random, bonuses, magicAttributes, DamageReductionAttributeCollector(styles)),
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
        )
        private val world = mockk<WorldRepository>(relaxUnitFun = true)
        private val npcInteractions = mockk<NpcInteractions>()
        private val playerInteractions = mockk<PlayerInteractions>()
        private val manager = PlayerAttackManager(
            random, events, world, accuracy, maxima, StandardNpcHitModifier(events), StandardPlayerHitModifier(events),
            npcInteractions, mockk(), playerInteractions, mockk(), emptySet(),
        )
        private val registry = WeaponRegistry()
        private val access = ProtectedAccess(player, GameCoroutine("shadow-launch"), mockk())
        private val beforeXp = player.statMap.getFineXP("stat.magic")
        private val manualSpells by lazy {
            ManualSpells(manager, ScriptContext(events, CheatCommandMap(), EngineQueueCache()))
        }

        init {
            player.worn[chargedType.wearpos1] = InvObj(unchargedType)
            if (charges > 0) {
                val result = objCharges.addCharges(player.worn, chargedType.wearpos1, charges, "varobj.tumeken_charges", 20000)
                assertTrue(result is ObjChargeManager.Charge.Success)
            } else if (chargedIdentity) {
                // Exercise malformed/admin-spawned charged identity with zero stored charges.
                player.worn[chargedType.wearpos1] = InvObj(chargedType)
            }
            for (symbol in listOf("obj.occult_necklace", "obj.ancestral_robe_top")) {
                val gear = item(symbol)
                player.worn[gear.wearpos1] = InvObj(gear)
            }
            every { npcInteractions.interact(player, npc, any()) } answers { continuations++ }
            every { playerInteractions.interact(player, opponent, any()) } answers { continuations++ }
            every { world.projAnim(player, npc, any<SpotanimType>(), any<String>()) } answers {
                ProjAnim.fromBoundsToNpc(player.bounds(), npc, thirdArg<SpotanimType>().id, arg<String>(3)).also(projectiles::add)
            }
            every { world.projAnim(player, opponent, any<SpotanimType>(), any<String>()) } answers {
                ProjAnim.fromBoundsToPlayer(player.bounds(), opponent, thirdArg<SpotanimType>().id, arg<String>(3)).also(projectiles::add)
            }
            with(TumekensShadowWeapons(combatCharges)) {
                WeaponRepository(registry).register(WeaponAttackManager(manager))
            }
        }

        fun maxHit(): Int = if (pvp) {
            maxima.getStaffMaxHit(player, opponent, 34, 1.0)
        } else {
            maxima.getStaffMaxHit(player, npc, 34, 1.0)
        }

        fun hitChance(): Int = if (pvp) {
            accuracy.getStaffHitChance(player, opponent, MagicAttackStyle.Accurate, 1.0)
        } else {
            accuracy.getStaffHitChance(player, npc, MagicAttackStyle.Accurate, 1.0)
        }

        fun staffChanceOracle(): Int = chanceOracle(if (pvp) 16_060 else 34_100)
        fun manualChanceOracle(): Int = chanceOracle(15_768)
        fun oldTripledManualChanceOracle(): Int = chanceOracle(33_480)

        private fun chanceOracle(attackRoll: Int): Int {
            val defenceRoll = if (pvp) 6_912 else 109 * (npc.visType.param(params.defence_magic) + 64)
            val chance = if (attackRoll > defenceRoll) {
                1.0 - (defenceRoll + 2.0) / (2.0 * (attackRoll + 1.0))
            } else {
                attackRoll / (2.0 * (defenceRoll + 1.0))
            }
            return (chance * 10_000).roundToInt()
        }

        fun castAtLastSuccessfulRoll(): Hit {
            val expectedMax = if (pvp) 36 else 42
            assertEquals(expectedMax, maxHit())
            assertEquals(staffChanceOracle(), hitChance())
            random.accuracyRoll = staffChanceOracle() - 1
            assertTrue(random.accuracyRoll in 0 until 10000)
            return cast().single().also {
                assertFalse(it.isMagicSplash)
                assertEquals(expectedMax, it.damage)
                assertEquals(listOf(0..expectedMax), random.damageRanges)
                assertEquals(1, random.accuracyDraws)
            }
        }

        fun cast(): List<Hit> {
            val weapon = checkNotNull(player.worn[chargedType.wearpos1])
            val handler = checkNotNull(registry.getMagic(weapon))
            val attack = CombatAttack.Staff(weapon, MagicAttackStyle.Accurate)
            runImmediately {
                assertTrue(if (pvp) handler.attack(access, opponent, attack) else handler.attack(access, npc, attack))
            }
            return queuedHits()
        }

        fun supplyFireSurgeRunes(casts: Int = 1) {
            player.inv[0] = InvObj(item("obj.airrune"), 7 * casts)
            player.inv[1] = InvObj(item("obj.firerune"), 10 * casts)
            player.inv[2] = InvObj(item("obj.wrathrune"), casts)
        }

        fun runeCounts(): List<Int> = listOf("obj.airrune", "obj.firerune", "obj.wrathrune").map(player.inv::count)

        fun castFireSurge(): List<Hit> {
            val weapon = checkNotNull(player.worn[chargedType.wearpos1])
            val spell = manualSpells.fireSurge
            assertEquals(50.5, spell.castXp)
            assertEquals(Spellbook.Standard, spell.spellbook)
            val attack = CombatAttack.Spell(weapon, spell, defensive = false)
            val handler = checkNotNull(manualSpells.attacks["obj.95_fire_surge"])
            runImmediately {
                if (pvp) handler.attack(access, opponent, attack) else handler.attack(access, npc, attack)
            }
            return queuedHits()
        }

        private fun queuedHits(): List<Hit> = buildList {
            if (pvp) {
                val iterator = opponent.queueList.iterator() ?: return@buildList
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
                }
            } else {
                val iterator = npc.queueList.iterator() ?: return@buildList
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
                }
            }
        }

        fun assertFireSurgeSnapshot(hit: Hit) {
            assertChargedSnapshot(hit)
            assertTrue(hit.isSecondaryObj(item("obj.95_fire_surge")))
        }

        fun assertChargedSnapshot(hit: Hit) {
            assertEquals(HitType.Magic, hit.type)
            assertTrue(hit.isRighthandObj(chargedType))
        }

        fun assertWeapon(charged: Boolean, remaining: Int) {
            assertEquals((if (charged) chargedType else unchargedType).id, player.worn[chargedType.wearpos1]?.id)
            assertEquals(1, player.worn[chargedType.wearpos1]?.count)
            assertEquals(remaining, combatCharges.getWeaponCharges(player, "varobj.tumeken_charges"))
        }

        fun assertNoCast() {
            assertEquals(0, random.accuracyDraws)
            assertTrue(random.damageRanges.isEmpty())
            assertTrue(projectiles.isEmpty())
            assertEquals(0, magicXpGained())
            assertEquals(0, continuations)
        }

        fun magicXpGained(): Int = player.statMap.getFineXP("stat.magic") - beforeXp
        fun advance() { player.currentMapClock += 5; player.processedMapClock += 5 }
    }

    private class ManualSpells(manager: PlayerAttackManager, context: ScriptContext) {
        private val spells = Guice.createInjector(MagicSpellModule()).getInstance(MagicSpellRegistry::class.java)
        val fireSurge: MagicSpell = checkNotNull(spells.getObjSpell(item("obj.95_fire_surge")))
        val attacks = SpellAttackRegistry()

        init {
            val fakes = FakeRuneRepository()
            val combos = ComboRuneRepository()
            val compact = CompactRuneRepository()
            val unlimited = UnlimitedRuneRepository()
            val staffSubstitutes = StaffSubstituteRepository()
            val runeSubstitutes = RuneSubstituteRepository()
            for (script in listOf(
                FakeRuneScript(fakes), ComboRuneScript(combos), CompactRuneScript(compact),
                UnlimitedRuneScript(unlimited), StaffSubstituteScript(staffSubstitutes), RuneSubstituteScript(runeSubstitutes),
            )) {
                with(script) { context.startup() }
            }
            val runes = MagicRuneManager(fakes, combos, compact, unlimited, staffSubstitutes, runeSubstitutes, emptySet())
            with(ElementalSpells()) {
                SpellAttackRepository(attacks).register(SpellAttackManager(manager, runes))
            }
        }
    }

    private class CastRandom : GameRandom {
        var accuracyRoll = 0
        var accuracyDraws = 0
        val damageRanges = mutableListOf<IntRange>()
        override fun of(maxExclusive: Int): Int {
            assertEquals(10000, maxExclusive, "Unexpected draw outside staff accuracy")
            accuracyDraws++
            return accuracyRoll.also { require(it in 0 until maxExclusive) }
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            assertEquals(0, minInclusive)
            damageRanges += minInclusive..maxInclusive
            return maxInclusive
        }
        override fun randomDouble(): Double = error("Unexpected floating-point draw")
    }

    companion object {
        private val RESTORATIONS = mutableListOf<() -> Unit>()

        @JvmStatic
        @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val previous = field.get(null)
                RESTORATIONS += { field.set(null, previous) }
            }
            val previousStorage = InvVirtualStorageHolder.instance
            RESTORATIONS += { InvVirtualStorageHolder.instance = previousStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic
        @AfterAll
        fun restoreGlobals() {
            RESTORATIONS.asReversed().forEach { it() }
            RESTORATIONS.clear()
        }

        private fun item(symbol: String): ItemServerType = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))

        private fun newPlayer(slot: Int, coord: CoordGrid): Player = Player().apply {
            slotId = slot
            uuid = slot.toLong()
            worldType = WorldType.MAIN
            assignUid()
            coords = coord
            currentMapClock = 100
            processedMapClock = 100
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.ranged", "stat.magic", "stat.hitpoints")) {
                statMap.setBaseLevel(stat, 99)
                statMap.setCurrentLevel(stat, 99)
                statMap.setXP(stat, PlayerSkillXPTable.getXPFromLevel(99))
            }
        }

        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Shadow cast unexpectedly suspended" }.getOrThrow()
        }
    }
}
