package org.rsmod.api.combat

import com.google.inject.Guice
import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.definition.type.widget.IfEvent
import dev.openrune.map.area.MapAreaDecoder
import dev.openrune.map.area.MapAreaDefinitions
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import dev.openrune.types.aconverted.SpotanimType
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import net.rsprot.protocol.game.incoming.players.OpPlayer
import net.rsprot.protocol.game.incoming.players.OpPlayerT
import net.rsprot.protocol.util.CombinedId
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
import org.rsmod.api.combat.commons.magic.MagicSpell
import org.rsmod.api.combat.commons.ranged.RangedAmmunition
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.formulas.accuracy.magic.PvPMagicAccuracy
import org.rsmod.api.combat.formulas.accuracy.melee.PvPMeleeAccuracy
import org.rsmod.api.combat.formulas.accuracy.ranged.PvPRangedAccuracy
import org.rsmod.api.combat.formulas.attributes.collector.CombatMagicAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatMeleeAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatNpcAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatRangedAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.magic.PvPMagicMaxHit
import org.rsmod.api.combat.formulas.maxhit.melee.PvPMeleeMaxHit
import org.rsmod.api.combat.formulas.maxhit.ranged.PvPRangedMaxHit
import org.rsmod.api.combat.manager.CombatChargeManager
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.combat.scripts.PvPCombatScript
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.death.PvPAttackValidateHook
import org.rsmod.api.death.PvPAttackValidateResult
import org.rsmod.api.death.PvPContextAuthorizationHook
import org.rsmod.api.death.PvPContextPermission
import org.rsmod.api.death.PvPSkullHook
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.net.rsprot.handlers.OpPlayerHandler
import org.rsmod.api.net.rsprot.handlers.OpPlayerTHandler
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.events.PlayerQueueEvents
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.interact.PlayerInteractions
import org.rsmod.api.player.interact.PlayerTInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContext
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.loc.LocRegistryNormal
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.world.WorldRepository
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
import org.rsmod.api.weapons.MinigameStaffPvPPermission
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.canPvp
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.setCanPvp
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.setWildernessOptOut
import org.rsmod.content.areas.wilderness.WildernessPvPHook
import org.rsmod.content.areas.wilderness.WildernessPvPPermission
import org.rsmod.content.other.special.weapons.magic.TumekensShadowWeapons
import org.rsmod.content.skills.magic.spell.attacks.standard.ElementalSpells
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.interact.InteractionPlayerOp
import org.rsmod.game.interact.InteractionPlayerT
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.region.RegionListLarge
import org.rsmod.game.region.RegionListSmall
import org.rsmod.game.region.RegionListWorldEntity
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.ui.Component
import org.rsmod.game.ui.UserInterface
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
@ResourceLock("InvTransactions")
class PvPAuthorizationTest {
    @Test
    fun `safe area attack packets cannot launch any combat style even with stale attack menu flags`() {
        for (kind in Kind.entries) {
            for (op in listOf(1, 2)) {
                val f = Fixture(kind, SAFE)
                f.player.setCanPvp(true)
                f.target.setCanPvp(true)
                f.supplyRunes()
                val before = f.snapshot()
                assertTrue(f.fire(op = op).isEmpty())
                assertEquals(before, f.snapshot(), "kind=$kind op=$op")
                assertNull(f.player.interaction)
                assertEquals(0, f.impactAll())
                assertEquals(99, f.target.hitpoints)
            }
        }
    }

    @Test
    fun `native Wilderness packet attacks queue impact consume and skull without cached menu permission`() {
        for (kind in listOf(Kind.MELEE, Kind.RANGED, Kind.SPELL)) {
            val f = Fixture(kind, WILD)
            assertFalse(f.player.canPvp())
            assertFalse(f.target.canPvp())
            f.supplyRunes()
            val hit = f.fire().single()
            assertTrue(hit.damage > 0)
            assertEquals(99, f.target.hitpoints, "Damage must wait for the queued impact")
            assertEquals(1, f.skulls)
            assertTrue(f.player.appearance.skullIcon != null)
            assertTrue(f.totalXpGained() > 0)
            assertEquals(1, f.impactAll())
            assertEquals(99 - hit.damage, f.target.hitpoints)
            assertEquals(listOf(hit), f.impacts)
            if (kind == Kind.RANGED) assertEquals(1, f.arrowCount())
            if (kind == Kind.SPELL) {
                assertEquals(listOf(0, 0, 0), f.runeCounts())
                assertEquals(1, f.shadowCharges())
                assertTrue(hit.isSecondaryObj(item("obj.95_fire_surge")))
            }
        }
    }

    @Test
    fun `current coordinates revoke a pending packet after either player leaves Wilderness`() {
        for (moveAttacker in listOf(false, true)) {
            val f = Fixture(Kind.SPELL, WILD)
            f.player.setCanPvp(true)
            f.target.setCanPvp(true)
            f.supplyRunes()
            f.receivePacket()
            if (moveAttacker) f.player.coords = SAFE else f.target.coords = SAFE
            val before = f.snapshot()
            assertTrue(f.dispatchPending().isEmpty())
            assertEquals(before, f.snapshot())
            assertEquals(0, f.impactAll())
            assertTrue(f.player.canPvp() && f.target.canPvp(), "Deliberately stale menu flags")
        }
    }

    @Test
    fun `entering Wilderness enables the next actual event without waiting for area flags`() {
        val f = Fixture(Kind.SPELL, SAFE)
        f.supplyRunes()
        assertTrue(f.fire().isEmpty())
        assertEquals(0, f.random.accuracyDraws)
        f.player.coords = WILD
        f.target.coords = WILD.translate(1, 0)
        assertFalse(f.player.canPvp())
        val hit = f.fire().single()
        assertEquals(25, hit.damage)
        assertEquals(1, f.impactAll())
        assertEquals(74, f.target.hitpoints)
    }

    @Test
    fun `manual rune preflight precedes permission and missing runes cannot consume a charge`() {
        val f = Fixture(Kind.SPELL, SAFE)
        val before = f.snapshot()
        assertTrue(f.fire().isEmpty())
        assertEquals(0, f.permissionChecks)
        assertEquals(before, f.snapshot())
        f.supplyRunes()
        val withRunes = f.snapshot()
        assertTrue(f.fire().isEmpty())
        assertEquals(1, f.permissionChecks)
        assertEquals(withRunes, f.snapshot())
        assertEquals(1, f.shadowCharges())
    }

    @Test
    fun `a legacy Pass hook cannot authorize without a positive context provider`() {
        val f = Fixture(Kind.SPELL, WILD, includeProvider = false,
            extraHooks = setOf(PvPAttackValidateHook { _, _ -> PvPAttackValidateResult.Pass }))
        f.supplyRunes()
        val before = f.snapshot()
        assertTrue(f.fire().isEmpty())
        assertEquals(before, f.snapshot())
        assertEquals(0, f.impactAll())
    }

    @Test
    fun `existing general veto still wins after actual Wilderness permission`() {
        var vetoes = 0
        val f = Fixture(Kind.SPELL, WILD, extraHooks = setOf(PvPAttackValidateHook { _, _ ->
            vetoes++
            PvPAttackValidateResult.Deny("Synthetic independent combat rule.")
        }))
        f.supplyRunes()
        val before = f.snapshot()
        assertTrue(f.fire().isEmpty())
        assertEquals(1, f.permissionChecks)
        assertEquals(1, vetoes)
        assertEquals(before, f.snapshot())
    }

    @Test
    fun `self cross mode and different physical plane packets never reach combat effects`() {
        for (kind in listOf(Kind.MELEE, Kind.SPELL)) {
            for (state in listOf("self", "mode", "plane")) {
                val f = Fixture(kind, WILD)
                f.supplyRunes()
                val selected = if (state == "self") f.player else f.target
                if (state == "mode") selected.worldType = WorldType.TWISTED_LEAGUE
                if (state == "plane") selected.coords = selected.coords.copy(level = 1)
                val before = f.snapshot()
                assertTrue(f.fire(selected).isEmpty())
                assertEquals(0, f.permissionChecks)
                assertEquals(before, f.snapshot(), "kind=$kind state=$state")
                assertEquals(0, f.impactAll())
            }
        }
    }

    @Test
    fun `target logout switching death and invalid persistence reject already routed packets`() {
        val invalidate: List<(Player) -> Unit> = listOf(
            { it.pendingLogout = true }, { it.loggingOut = true }, { it.worldTypeSwitching = true },
            { it.forceDisconnect = true }, { it.persistenceInvalidated = true },
            { it.statMap.setCurrentLevel("stat.hitpoints", 0) },
        )
        for (change in invalidate) {
            val f = Fixture(Kind.SPELL, WILD)
            f.supplyRunes()
            f.receivePacket()
            change(f.target)
            val before = f.snapshot()
            assertTrue(f.dispatchPending().isEmpty())
            assertEquals(0, f.permissionChecks)
            assertEquals(before, f.snapshot())
        }
        val f = Fixture(Kind.MELEE, WILD)
        f.player.worldTypeSwitching = true
        val before = f.snapshot()
        f.receivePacket(expectInteraction = false)
        assertNull(f.player.interaction)
        assertEquals(before, f.snapshot())
    }

    @Test
    fun `legacy opt out and existing Wilderness skull and level restrictions remain vetoes`() {
        for (restriction in listOf("attacker opt out", "target opt out", "skull prevention", "level")) {
            val f = Fixture(Kind.SPELL, WILD)
            f.supplyRunes()
            when (restriction) {
                "attacker opt out" -> f.player.setWildernessOptOut(true)
                "target opt out" -> f.target.setWildernessOptOut(true)
                "skull prevention" -> VarPlayerIntMapSetter.set(f.player, "varbit.skull_prevent_enabled", 1)
                "level" -> f.target.appearance.combatLevel = 3
            }
            val before = f.snapshot()
            assertTrue(f.fire().isEmpty())
            assertEquals(before, f.snapshot(), restriction)
        }
    }

    @Test
    fun `packed Ferox exclusion remains safe despite teleblock and cached flags`() {
        val f = Fixture(Kind.SPELL, CoordGrid(3130, 3630))
        f.player.setCanPvp(true)
        f.target.setCanPvp(true)
        f.supplyRunes()
        VarPlayerIntMapSetter.set(f.player, "varbit.teleblock_cycles", 1)
        VarPlayerIntMapSetter.set(f.target, "varbit.teleblock_cycles", 1)
        val before = f.snapshot()
        assertTrue(f.fire().isEmpty())
        assertEquals(before, f.snapshot())
        assertEquals(0, f.impactAll())
    }

    @Test
    fun `Ferox upper floor shoulder and basement packets reject every style without spending resources`() {
        val safeTiles = listOf(
            CoordGrid(3132, 3621, 1), CoordGrid(3141, 3637, 2),
            CoordGrid(3134, 3641, 1), CoordGrid(3137, 3644, 1),
            CoordGrid(3150, 10015), CoordGrid(3156, 10024),
        )
        for (tile in safeTiles) {
            for (kind in Kind.entries) {
                val f = Fixture(kind, tile)
                val expectedArea = if (tile.level == 0) "area.ferox_safe_zone" else "area.ferox_enclave"
                assertTrue(f.areas.inArea(expectedArea, f.player.coords))
                assertTrue(f.areas.inArea(expectedArea, f.target.coords))
                f.player.setCanPvp(true)
                f.target.setCanPvp(true)
                VarPlayerIntMapSetter.set(f.player, "varbit.teleblock_cycles", 1)
                VarPlayerIntMapSetter.set(f.target, "varbit.teleblock_cycles", 1)
                f.supplyRunes()
                val before = f.snapshot()
                assertTrue(f.fire().isEmpty(), "kind=$kind tile=$tile")
                assertEquals(before, f.snapshot(), "Denied packet must preserve ammo, runes, charges, XP and combat state: kind=$kind tile=$tile")
                assertNull(f.player.interaction)
                assertEquals(0, f.impactAll())
                assertEquals(99, f.target.hitpoints)
            }
        }
    }

    @Test
    fun `nearby tower remains attackable and an already queued legal hit survives entry into the Ferox basement`() {
        for (level in listOf(1, 2)) {
            for (kind in listOf(Kind.MELEE, Kind.RANGED, Kind.SPELL)) {
                val f = Fixture(kind, CoordGrid(3183, 3609, level))
                assertFalse(f.areas.inArea("area.ferox_safe_zone", f.player.coords))
                f.supplyRunes()
                val hit = f.fire().single()
                assertTrue(hit.damage > 0, "Outside tower kind=$kind level=$level")
                assertEquals(1, f.skulls)
                assertTrue(f.totalXpGained() > 0)
                if (kind == Kind.RANGED) assertEquals(1, f.arrowCount())
                if (kind == Kind.SPELL) assertEquals(listOf(0, 0, 0), f.runeCounts())
                assertEquals(1, f.impactAll())
                assertEquals(99 - hit.damage, f.target.hitpoints)
            }
        }

        val f = Fixture(Kind.SPELL, CoordGrid(3157, 3645))
        f.supplyRunes()
        val hit = f.fire().single()
        val launchXp = f.totalXpGained()
        f.target.coords = CoordGrid(3150, 10015)
        assertTrue(f.areas.inArea("area.ferox_safe_zone", f.target.coords))
        assertEquals(99, f.target.hitpoints, "Entering the cave must not process or clear pending damage")
        assertEquals(1, f.impactAll())
        assertEquals(99 - hit.damage, f.target.hitpoints)
        assertEquals(launchXp, f.totalXpGained(), "Impact does not repeat the XP award")
        assertEquals(listOf(hit), f.impacts)
    }

    @Test
    fun `both registered op and approach events enforce context after native packet selection`() {
        for (approach in listOf(false, true)) {
            val f = Fixture(Kind.RANGED, SAFE)
            val before = f.snapshot()
            assertTrue(f.fire(approach = approach).isEmpty())
            assertEquals(before, f.snapshot())
        }
    }

    private enum class Kind { MELEE, RANGED, SPELL, STAFF }

    private class Fixture(
        val kind: Kind,
        coords: CoordGrid,
        includeProvider: Boolean = true,
        extraHooks: Set<PvPAttackValidateHook> = emptySet(),
    ) {
        val player = newPlayer(1, coords)
        val target = newPlayer(2, coords.translate(1, 0)).apply {
            statMap.setCurrentLevel("stat.magic", 100)
            statMap.setCurrentLevel("stat.defence", 100)
        }
        private val players = PlayerList().also { it[player.slotId] = player; it[target.slotId] = target }
        private val events = EventBus()
        private val scriptContext = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
        private val collision = CollisionFlagMap()
        private val locs = LocZoneStorage()
        private val regions = RegionRegistry(
            RegionListSmall(), RegionListLarge(), RegionListWorldEntity(),
            LocRegistryNormal(ZoneUpdateMap(), collision, locs), collision, locs,
            mockk(), mockk(), ZonePlayerActivityBitSet(),
        )
        val areas = AreaChecker(regions, areaIndex)
        private val wildernessPermission = WildernessPvPPermission(areas, regions)
        private val wildernessHook = WildernessPvPHook(areas)
        var permissionChecks = 0
        private val authorization = PvPContextAuthorizationHook(
            if (includeProvider) setOf(PvPContextPermission { source, victim ->
                permissionChecks++
                wildernessPermission.permits(source, victim)
            }) else emptySet(),
        )
        private val validators = linkedSetOf<PvPAttackValidateHook>(authorization, wildernessHook).apply { addAll(extraHooks) }
        val random = CombatRandom()
        private val ammoRandom = AmmoRandom()
        private val impactRandom = ImpactRandom()
        val projectiles = mutableListOf<ProjAnim>()
        val impacts = mutableListOf<Hit>()
        var continuations = 0
        var skulls = 0
        private val styles = AttackStyles().also { with(WeaponAttackStylesScript(it)) { scriptContext.startup() } }
        private val types = AttackTypes().also { with(WeaponAttackTypesScript(it)) { scriptContext.startup() } }
        private val speeds = WeaponSpeeds(styles)
        private val bonuses = WornBonuses()
        private val magicAttributes = CombatMagicAttributeCollector()
        private val npcAttributes = CombatNpcAttributeCollector()
        private val meleeAttributes = CombatMeleeAttributeCollector()
        private val rangedAttributes = CombatRangedAttributeCollector()
        private val reductions = DamageReductionAttributeCollector(styles)
        private val accuracy = AccuracyFormulae(
            mockk(), mockk(), mockk(), PvPMagicAccuracy(random, bonuses, styles, magicAttributes),
            mockk(), mockk(), mockk(), PvPMeleeAccuracy(bonuses, styles, meleeAttributes),
            mockk(), mockk(), mockk(), PvPRangedAccuracy(bonuses, styles, rangedAttributes),
            mockk(), mockk(), mockk(), mockk(),
        )
        private val maxima = MaxHitFormulae(
            mockk(), PvPMagicMaxHit(random, bonuses, magicAttributes, reductions), mockk(), mockk(),
            mockk(), PvPMeleeMaxHit(random, bonuses, speeds, npcAttributes, meleeAttributes, reductions), mockk(), mockk(),
            mockk(), PvPRangedMaxHit(random, bonuses, speeds, rangedAttributes, reductions), mockk(), mockk(),
        )
        private val world = mockk<WorldRepository>(relaxUnitFun = true)
        private val continuedPlayers = mockk<PlayerInteractions>()
        private val manager = PlayerAttackManager(
            random, events, world, accuracy, maxima, StandardNpcHitModifier(events), StandardPlayerHitModifier(events),
            mockk(), mockk(), continuedPlayers, mockk(), setOf(wildernessHook),
        )
        private val registry = WeaponRegistry()
        private val charges = CombatChargeManager(ObjChargeManager())
        private val manualSpells = ManualSpells(manager, scriptContext)
        private val ammo = RangedAmmoManager(ammoRandom, events, mockk(), mockk(), mockk())
        private val combat = PvPCombat(
            speeds, mockk(), mockk(), registry, manager, ammo, manualSpells.attacks,
            linkedSetOf(wildernessHook, PvPSkullHook { _, _ -> skulls++ }), emptySet(),
        )
        private val context = mockk<ProtectedAccessContext>().also {
            every { it.areaChecker } returns areas
            every { it.eventBus } returns events
            every { it.playerList } returns players
            every { it.npcList } returns NpcList()
            every { it.random } returns impactRandom
        }
        private val access = ProtectedAccess(player, GameCoroutine("pvp-authorization"), context)
        private val targetAccess = ProtectedAccess(target, GameCoroutine("pvp-authorization-impact"), context)
        private val interactions = PlayerInteractions(events)
        private val spellInteractions = PlayerTInteractions(events)
        private val opHandler = OpPlayerHandler(events, players, interactions)
        private val spellHandler = OpPlayerTHandler(events, players, spellInteractions)
        private val beforeXp = fineXp()

        init {
            assertFalse(areas.inArea("area.wilderness", SAFE))
            assertTrue(areas.inArea("area.wilderness", WILD))
            VarPlayerIntMapSetter.set(player, "varbit.skull_prevent_enabled", 0)
            every { continuedPlayers.interact(player, target, any()) } answers { continuations++ }
            every { world.projAnim(player, target, any<SpotanimType>(), any<String>()) } answers {
                ProjAnim.fromBoundsToPlayer(player.bounds(), target, thirdArg<SpotanimType>().id, arg<String>(3)).also(projectiles::add)
            }
            with(TumekensShadowWeapons(charges, setOf(MinigameStaffPvPPermission { _, _, _ -> true }))) {
                // Synthetic weapon-specific permission isolates the mandatory general context gate.
                WeaponRepository(registry).register(WeaponAttackManager(manager))
            }
            when (kind) {
                Kind.MELEE -> equip("obj.rune_scimitar")
                Kind.RANGED -> { equip("obj.magic_shortbow"); equip("obj.rune_arrow", 2) }
                Kind.SPELL, Kind.STAFF -> {
                    equip("obj.tumekens_shadow_uncharged")
                    val type = item("obj.tumekens_shadow")
                    val charged = ObjChargeManager().addCharges(player.worn, type.wearpos1, 1, "varobj.tumeken_charges", 20000)
                    assertTrue(charged is ObjChargeManager.Charge.Success)
                    equip("obj.occult_necklace")
                    equip("obj.ancestral_robe_top")
                }
            }
            with(PvPCombatScript(styles, types, combat, manualSpells.spells, manualSpells.runes, mockk(), validators)) {
                scriptContext.startup()
            }
            with(PlayerHitScript()) { scriptContext.startup() }
            events.subscribeUnbound(PlayerHitEvents.Impact::class.java) { impacts += hit }
            val slot = ServerCacheManager.fromComponent("component.toplevel_osrs_stretch:side6")
            player.ui.overlays[Component(slot.packed)] = UserInterface(ServerCacheManager.fromInterface(manualSpells.fireSurge.component.packed))
            assertTrue(manualSpells.fireSurge.component.hasEvent(IfEvent.TgtPlayer), "Native spell must target players")
        }

        private fun equip(symbol: String, count: Int = 1) {
            val type = item(symbol)
            player.worn[type.wearpos1] = InvObj(type, count)
        }

        fun receivePacket(selected: Player = target, op: Int = 2, expectInteraction: Boolean = true) {
            if (kind == Kind.SPELL) {
                spellHandler.consume(player, OpPlayerT(selected.slotId, false, CombinedId(manualSpells.fireSurge.component.packed), -1, -1))
            } else {
                opHandler.consume(player, OpPlayer(selected.slotId, false, op))
            }
            if (expectInteraction) assertTrue(player.interaction != null, "Packet must pass native target/UI validation")
        }

        fun dispatchPending(approach: Boolean = false): List<Hit> {
            val interaction = checkNotNull(player.interaction)
            runImmediately {
                when (interaction) {
                    is InteractionPlayerOp -> {
                        val event = if (approach) interactions.apTrigger(interaction.target, interaction.op)
                            else interactions.opTrigger(interaction.target, interaction.op)
                        assertTrue(events.publish(access, checkNotNull(event)))
                    }
                    is InteractionPlayerT -> {
                        val event = checkNotNull(spellInteractions.apTrigger(interaction.target, interaction.component, interaction.comsub, interaction.objType))
                        assertTrue(events.publish(access, event))
                    }
                    else -> error("Unexpected packet interaction $interaction")
                }
            }
            return queuedHits()
        }

        fun fire(selected: Player = target, op: Int = 2, approach: Boolean = false): List<Hit> {
            receivePacket(selected, op)
            return dispatchPending(approach)
        }

        fun supplyRunes() {
            player.inv[0] = InvObj(item("obj.airrune"), 7)
            player.inv[1] = InvObj(item("obj.firerune"), 10)
            player.inv[2] = InvObj(item("obj.wrathrune"), 1)
        }
        fun runeCounts(): List<Int> = listOf("obj.airrune", "obj.firerune", "obj.wrathrune").map(player.inv::count)
        fun arrowCount(): Int = player.worn[item("obj.rune_arrow").wearpos1]?.count ?: 0
        fun shadowCharges(): Int = charges.getWeaponCharges(player, "varobj.tumeken_charges")
        private fun fineXp(): List<Int> = listOf("stat.attack", "stat.strength", "stat.defence", "stat.ranged", "stat.magic", "stat.hitpoints").map(player.statMap::getFineXP)
        fun totalXpGained(): Int = fineXp().sum() - beforeXp.sum()
        fun snapshot(): List<Any?> = listOf(
            player.actionDelay, player.vars.backing.toMap(), target.vars.backing.toMap(),
            player.pendingSequence, player.pendingSpotanims.toLongArray().toList(), target.hitpoints, player.hitpoints,
            random.accuracyDraws, random.damageRanges.toList(), projectiles.size, queuedHits().size,
            fineXp(), continuations, skulls, player.appearance.skullIcon, target.appearance.skullIcon,
            (0 until player.inv.size).map { player.inv[it]?.copy() },
            (0 until player.worn.size).map { player.worn[it]?.copy() }, ammoRandom.draws,
        )
        private fun queuedHits(): List<Hit> = buildList {
            val iterator = target.queueList.iterator() ?: return@buildList
            while (iterator.hasNext()) { val queue = iterator.next(); if (queue.id == "queue.hit".asRSCM()) add(queue.args as Hit) }
        }
        fun impactAll(): Int {
            val hits = queuedHits()
            target.queueList.removeAll("queue.hit")
            for (hit in hits) runImmediately {
                assertTrue(events.publish(targetAccess, PlayerQueueEvents.Protected(hit, "queue.hit".asRSCM())))
            }
            return hits.size
        }
    }

    private class CombatRandom : GameRandom {
        var accuracyDraws = 0
        val damageRanges = mutableListOf<IntRange>()
        override fun of(maxExclusive: Int): Int {
            assertEquals(10000, maxExclusive)
            accuracyDraws++
            return 0
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int { damageRanges += minInclusive..maxInclusive; return maxInclusive }
        override fun randomDouble(): Double = error("Unexpected floating draw")
    }
    private class ImpactRandom : GameRandom {
        override fun of(maxExclusive: Int): Int { assertEquals(4, maxExclusive); return 0 }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unexpected impact range draw")
        override fun randomDouble(): Double = error("Unexpected impact floating draw")
    }
    private class AmmoRandom : GameRandom {
        var draws = 0
        override fun of(maxExclusive: Int): Int { assertEquals(RangedAmmunition.DEFAULT_AMMO_DROP_RATE, maxExclusive); draws++; return maxExclusive - 1 }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unexpected ammo draw")
        override fun randomDouble(): Double = error("Unexpected ammo draw")
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

    companion object {
        private val SAFE = CoordGrid(3222, 3218)
        private val WILD = CoordGrid(3201, 3554)
        private lateinit var areaIndex: AreaIndex
        private val RESTORATIONS = mutableListOf<() -> Unit>()

        @JvmStatic
        @BeforeAll
        fun cache() {
            val cache = ServerCacheManager.init(241)
            try {
                areaIndex = AreaIndex()
                for (region in cache.archives(MAPS)) {
                    if (region > 25286 || 7 !in cache.files(MAPS, region)) continue
                    if (cache.data(MAPS, region, 0) == null || cache.data(MAPS, region, 1) == null) continue
                    val data = checkNotNull(cache.data(MAPS, region, 7)) { "Missing declared area for $region" }
                    MapAreaDefinitions.putAreas(areaIndex, MapSquareKey(region), MapAreaDecoder.decode(InlineByteBuf(data)))
                }
            } finally { cache.close() }
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
            appearance.combatLevel = 126
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
