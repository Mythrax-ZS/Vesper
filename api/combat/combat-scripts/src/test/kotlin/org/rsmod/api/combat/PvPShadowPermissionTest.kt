package org.rsmod.api.combat

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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.area.checker.AreaChecker
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
import org.rsmod.api.combat.player.specialAttackType
import org.rsmod.api.combat.scripts.PvPCombatScript
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.death.PvPAttackValidateHook
import org.rsmod.api.death.PvPAttackValidateResult
import org.rsmod.api.death.PvPSkullHook
import org.rsmod.api.death.PvPSpecialAttackHook
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.events.interact.PlayerEvents
import org.rsmod.api.player.events.interact.PlayerTEvents
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.interact.PlayerInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContext
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.specials.SpecialAttackType
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackRegistry
import org.rsmod.api.spells.attack.SpellAttackRepository
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
import org.rsmod.api.weapons.MagicWeapon
import org.rsmod.api.weapons.MinigameStaffPvPPermission
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.other.special.weapons.magic.TumekensShadowWeapons
import org.rsmod.content.skills.magic.spell.attacks.standard.ElementalSpells
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionOp
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
class PvPShadowPermissionTest {
    @Test
    fun `ordinary PvP denial preserves final and stacked charges through actual dispatch`() {
        for (count in listOf(1, 20)) {
            for (legacyConstructor in listOf(false, true)) {
                val f = Fixture(count, legacyConstructor = legacyConstructor)
                val before = f.snapshot()
                repeat(2) {
                    f.prepareInteraction()
                    assertTrue(f.cast().isEmpty())
                    assertNull(f.player.interaction)
                    assertEquals(before, f.snapshot())
                }
                f.assertWeapon(charged = true, remaining = count)
            }
        }
    }

    @Test
    fun `denial precedes empty weapon normalization and uncharged handler effects`() {
        for (chargedIdentity in listOf(false, true)) {
            val f = Fixture(0, chargedIdentity = chargedIdentity)
            val before = f.snapshot()
            repeat(2) {
                assertTrue(f.cast().isEmpty())
                assertEquals(before, f.snapshot())
            }
            f.assertWeapon(charged = chargedIdentity, remaining = 0)
        }
    }

    @Test
    fun `denial precedes delayed continuation and pending weapon or shield special activation`() {
        // A pending shield selection is a defensive invalid-state check: Shadow is two-handed.
        // This does not claim a shield special can normally be armed with Shadow equipped.
        for (type in listOf(SpecialAttackType.Weapon, SpecialAttackType.Shield)) {
            for (delayed in listOf(false, true)) {
                val f = Fixture(1)
                f.selectSpecial(type)
                if (delayed) f.player.actionDelay = 200
                val before = f.snapshot()
                repeat(2) { f.cast() }
                assertEquals(before, f.snapshot())
                assertEquals(type, f.selectedSpecial())
            }
        }
    }

    @Test
    fun `registered op and approach events resolve native Shadow staff attacks and deny them`() {
        for (approach in listOf(false, true)) {
            val f = Fixture(1)
            val before = f.snapshot()
            assertTrue(f.castOp(approach).isEmpty())
            assertNull(f.player.interaction)
            assertEquals(before, f.snapshot())
        }
    }

    @Test
    fun `synthetic native minigame UI and team values never grant staff permission`() {
        val f = Fixture(1)
        // Native field definitions come from the LIVE varbit receipt. These assignments are
        // synthetic UI values, not evidence of an active or eligible minigame match.
        for ((who, team) in listOf(f.player to 1, f.opponent to 2)) {
            f.setUiVar(who, "varbit.clanwars_tots", 1)
            f.setUiVar(who, "varbit.clanwars_magic", 0)
            f.setUiVar(who, "varbit.clanwars_myteam", team)
            f.setUiVar(who, "varbit.clanwars_countdown", 0)
            f.setUiVar(who, "varbit.soul_wars_team", team)
            f.setUiVar(who, "varbit.soul_wars_entered_game", 1)
        }
        f.setUiVar(f.player, "varbit.castlewars_saradomin_team", 1)
        f.setUiVar(f.opponent, "varbit.castlewars_zamorak_team", 1)
        val before = f.snapshot()
        repeat(2) {
            assertTrue(f.castOp().isEmpty())
            assertNull(f.player.interaction)
            assertEquals(before, f.snapshot())
        }
        f.assertWeapon(charged = true, remaining = 1)
    }

    @Test
    fun `general PvP rule denial precedes any synthetic staff permission grant`() {
        for (approach in listOf(false, true)) {
            var permissionCalls = 0
            var generalCalls = 0
            val f = Fixture(
                1,
                permissions = setOf(MinigameStaffPvPPermission { _, _, _ -> permissionCalls++; true }),
                generalHooks = setOf(PvPAttackValidateHook { _, _ ->
                    generalCalls++
                    PvPAttackValidateResult.Deny("Synthetic general PvP restriction.")
                }),
            )
            val before = f.snapshot()
            assertTrue(f.castOp(approach).isEmpty())
            assertNull(f.player.interaction)
            assertEquals(before, f.snapshot())
            assertEquals(1, generalCalls)
            assertEquals(0, permissionCalls)
        }
    }

    @Test
    fun `synthetic match permission is checked again for grant revocation and opponent changes`() {
        // This fixture exercises the extension contract; it is not a minigame implementation.
        var permittedTarget: Player? = null
        var permittedAttacker: Player? = null
        var calls = 0
        val permission = MinigameStaffPvPPermission { attacker, target, attack ->
            calls++
            assertEquals(item("obj.tumekens_shadow").id, attack.weapon.id)
            attacker === permittedAttacker && target === permittedTarget
        }
        val f = Fixture(3, permissions = setOf(permission))
        val before = f.snapshot()
        assertTrue(f.cast().isEmpty())
        assertEquals(before, f.snapshot())
        permittedTarget = f.opponent
        permittedAttacker = f.player

        val hit = f.cast().single()
        assertEquals(36, hit.damage)
        f.assertChargedSnapshot(hit)
        f.assertWeapon(charged = true, remaining = 2)
        assertEquals(1, f.skulls)
        assertEquals(1, f.continuations)
        assertEquals(1, f.projectiles.size)

        permittedTarget = null
        val afterCast = f.snapshot()
        // Still within the existing attack delay: rejection must not schedule a continuation.
        assertEquals(listOf(hit), f.cast())
        assertEquals(afterCast, f.snapshot())
        permittedTarget = f.opponent
        val other = newPlayer(3, CoordGrid(2272, 4695))
        assertEquals(listOf(hit), f.cast(other))
        assertEquals(afterCast, f.snapshot())
        assertEquals(4, calls)
    }

    @Test
    fun `permitted final charge launches raw PvP damage then transforms the weapon exactly once`() {
        val f = Fixture(1, permissions = setOf(MinigameStaffPvPPermission { _, _, _ -> true }))
        val hit = f.cast().single()
        assertEquals(36, hit.damage)
        f.assertChargedSnapshot(hit)
        f.assertWeapon(charged = false, remaining = 0)
        assertEquals(1, f.random.accuracyDraws)
        assertEquals(listOf(0..36), f.random.damageRanges)
        assertEquals(1, f.projectiles.size)
        assertEquals(1, f.skulls)
        assertEquals(0, f.continuations)
    }

    @Test
    fun `native manual Fire Surge uses rune gate and raw formulas without staff permission`() {
        val f = Fixture(1, permissions = setOf(MinigameStaffPvPPermission { _, _, _ ->
            error("Manual spells must not consult the built-in staff permission")
        }))
        f.supplyFireSurgeRunes()
        f.random.accuracyRoll = 1
        val hit = f.castFireSurge().single()
        assertEquals(25, hit.damage)
        assertFalse(hit.isMagicSplash)
        f.assertFireSurgeSnapshot(hit)
        f.assertWeapon(charged = true, remaining = 1)
        assertEquals(listOf(0, 0, 0), f.runeCounts())
        assertEquals(1067, f.magicXpGained())
        assertEquals(1, f.skulls)
        assertEquals(1, f.projectiles.size)
        val beforeMissingRunes = f.snapshot()
        assertEquals(listOf(hit), f.castFireSurge())
        assertEquals(beforeMissingRunes, f.snapshot(), "Spell-event rune preflight must reject the repeat")
    }

    @Test
    fun `native manual splash spends runes and cast XP with no Shadow charge or passive accuracy`() {
        val f = Fixture(1)
        f.supplyFireSurgeRunes()
        // Raw attack roll 15768 against defence 6912 gives 7808/10000; the old tripled
        // Shadow roll 33480 gives 8967/10000. This draw distinguishes the two paths.
        f.random.accuracyRoll = 8500
        val hit = f.castFireSurge().single()
        assertEquals(0, hit.damage)
        assertTrue(hit.isMagicSplash)
        assertEquals(505, f.magicXpGained())
        assertEquals(listOf(0, 0, 0), f.runeCounts())
        f.assertWeapon(charged = true, remaining = 1)
        assertTrue(f.random.damageRanges.isEmpty())
    }

    @Test
    fun `legacy magic weapon without optional validator retains normal dispatch and PK hooks`() {
        val f = Fixture(1)
        f.installLegacyHandler()
        assertTrue(f.cast().isEmpty())
        assertEquals(1, f.legacyCalls)
        assertEquals(1, f.skulls)
        assertTrue(f.player.actionDelay > f.player.currentMapClock)
        f.assertWeapon(charged = true, remaining = 1)
    }

    private class Fixture(
        charges: Int,
        chargedIdentity: Boolean = true,
        permissions: Set<MinigameStaffPvPPermission> = emptySet(),
        legacyConstructor: Boolean = false,
        generalHooks: Set<PvPAttackValidateHook> = emptySet(),
    ) {
        val player = newPlayer(1, CoordGrid(2275, 4695))
        val opponent = newPlayer(2, CoordGrid(2271, 4695)).apply {
            statMap.setCurrentLevel("stat.magic", 100)
            statMap.setCurrentLevel("stat.defence", 100)
            appearance.combatLevel = 126
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
        var skulls = 0
        var specials = 0
        var legacyCalls = 0
        private val protectedContext = mockk<ProtectedAccessContext>().also {
            every { it.areaChecker } returns AreaChecker(mockk(), AreaIndex())
            every { it.eventBus } returns events
        }
        private val access = ProtectedAccess(player, GameCoroutine("pvp-shadow-permission"), protectedContext)
        private val beforeXp = player.statMap.getFineXP("stat.magic")
        val manualSpells by lazy {
            ManualSpells(manager, ScriptContext(events, CheatCommandMap(), EngineQueueCache()))
        }

        private val combat by lazy {
            PvPCombat(
                WeaponSpeeds(styles), mockk(), mockk(), registry, manager, mockk(), manualSpells.attacks,
                setOf(PvPSkullHook { _, _ -> skulls++ }),
                setOf(PvPSpecialAttackHook { _, _ -> specials++ }),
            )
        }
        private val scriptStarted by lazy {
            val types = AttackTypes()
            val context = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
            with(WeaponAttackTypesScript(types)) { context.startup() }
            with(PvPCombatScript(styles, types, combat, manualSpells.spells, manualSpells.runes, mockk(), generalHooks)) {
                context.startup()
            }
            true
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
            every { playerInteractions.interact(player, opponent, any()) } answers { continuations++ }
            every { world.projAnim(player, opponent, any<SpotanimType>(), any<String>()) } answers {
                ProjAnim.fromBoundsToPlayer(player.bounds(), opponent, thirdArg<SpotanimType>().id, arg<String>(3)).also(projectiles::add)
            }
            val weaponMap = if (legacyConstructor) TumekensShadowWeapons(combatCharges)
                else TumekensShadowWeapons(combatCharges, permissions)
            with(weaponMap) {
                WeaponRepository(registry).register(WeaponAttackManager(manager))
            }
        }

        fun cast(target: Player = opponent): List<Hit> {
            val weapon = checkNotNull(player.worn[chargedType.wearpos1])
            runImmediately { combat.attack(access, target, CombatAttack.Staff(weapon, MagicAttackStyle.Accurate)) }
            return queuedHits()
        }

        fun prepareInteraction() {
            PlayerInteractions(events).interact(player, opponent, InteractionOp.Op2)
        }

        fun castOp(approach: Boolean = false): List<Hit> {
            check(scriptStarted)
            prepareInteraction()
            runImmediately {
                val handled = if (approach) events.publish(access, PlayerEvents.Ap2(opponent))
                    else events.publish(access, PlayerEvents.Op2(opponent))
                assertTrue(handled)
            }
            return queuedHits()
        }

        fun setUiVar(who: Player, symbol: String, value: Int) {
            val writer = ProtectedAccess(who, GameCoroutine("synthetic-minigame-ui"), protectedContext)
            writer.vars[symbol] = value
            assertEquals(value, who.vars[symbol], "Native field assignment must really take effect: $symbol")
        }

        fun selectSpecial(type: SpecialAttackType) { access.specialAttackType = type }
        fun selectedSpecial(): SpecialAttackType = access.specialAttackType

        fun installLegacyHandler() {
            registry.remove(chargedType)
            registry.add(chargedType, object : MagicWeapon {
                override suspend fun ProtectedAccess.attack(target: Npc, attack: CombatAttack.Staff): Boolean =
                    error("Unexpected NPC dispatch")
                override suspend fun ProtectedAccess.attack(target: Player, attack: CombatAttack.Staff): Boolean {
                    legacyCalls++
                    return true
                }
            })
        }

        fun snapshot(): Effects = Effects(
            player.actionDelay, selectedSpecial(), player.vars.backing.toMap(), opponent.vars.backing.toMap(),
            player.pendingSequence, player.pendingSpotanims.toLongArray().toList(),
            random.accuracyDraws, random.damageRanges.toList(), projectiles.size, queuedHits().size,
            magicXpGained(), continuations, skulls, specials,
            player.worn[chargedType.wearpos1]?.id,
            combatCharges.getWeaponCharges(player, "varobj.tumeken_charges"),
        )

        fun supplyFireSurgeRunes(casts: Int = 1) {
            player.inv[0] = InvObj(item("obj.airrune"), 7 * casts)
            player.inv[1] = InvObj(item("obj.firerune"), 10 * casts)
            player.inv[2] = InvObj(item("obj.wrathrune"), casts)
        }

        fun runeCounts(): List<Int> = listOf("obj.airrune", "obj.firerune", "obj.wrathrune").map(player.inv::count)

        fun castFireSurge(): List<Hit> {
            check(scriptStarted)
            val spell = manualSpells.fireSurge
            assertEquals(50.5, spell.castXp)
            assertEquals(Spellbook.Standard, spell.spellbook)
            runImmediately {
                assertTrue(events.publish(access, PlayerTEvents.Ap(opponent, -1, null, spell.component)))
            }
            return queuedHits()
        }

        private fun queuedHits(): List<Hit> = buildList {
            val iterator = opponent.queueList.iterator() ?: return@buildList
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.id == "queue.hit".asRSCM()) add(entry.args as Hit)
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

        fun magicXpGained(): Int = player.statMap.getFineXP("stat.magic") - beforeXp
    }

    private class ManualSpells(manager: PlayerAttackManager, context: ScriptContext) {
        val spells = Guice.createInjector(MagicSpellModule()).getInstance(MagicSpellRegistry::class.java)
        val fireSurge: MagicSpell = checkNotNull(spells.getObjSpell(item("obj.95_fire_surge")))
        val attacks = SpellAttackRegistry()
        val runes: MagicRuneManager

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
            runes = MagicRuneManager(fakes, combos, compact, unlimited, staffSubstitutes, runeSubstitutes, emptySet())
            with(ElementalSpells()) {
                SpellAttackRepository(attacks).register(SpellAttackManager(manager, runes))
            }
        }
    }

    private data class Effects(
        val attackDelay: Int,
        val selectedSpecial: SpecialAttackType,
        val attackerVars: Map<Int, Int>,
        val targetVars: Map<Int, Int>,
        val sequence: org.rsmod.game.seq.EntitySeq,
        val spotanims: List<Long>,
        val accuracyDraws: Int,
        val damageRanges: List<IntRange>,
        val projectiles: Int,
        val hits: Int,
        val magicXp: Int,
        val continuations: Int,
        val skulls: Int,
        val specials: Int,
        val weapon: Int?,
        val charges: Int,
    )

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
            checkNotNull(result) { "PvP dispatch unexpectedly suspended" }.getOrThrow()
        }
    }
}
