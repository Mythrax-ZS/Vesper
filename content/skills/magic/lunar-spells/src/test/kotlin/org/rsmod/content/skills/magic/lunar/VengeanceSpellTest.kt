package org.rsmod.content.skills.magic.lunar

import com.google.inject.Guice
import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.map.area.MapAreaDecoder
import dev.openrune.map.area.MapAreaDefinitions
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import dev.openrune.util.Wearpos
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import net.rsprot.protocol.game.incoming.buttons.If3Button
import net.rsprot.protocol.game.incoming.players.OpPlayerT
import net.rsprot.protocol.util.CombinedId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.rsmod.annotations.InternalApi
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.combat.commons.magic.MagicSpell
import org.rsmod.api.combat.commons.magic.SpellQuestRequirement
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.formulas.NpcIncomingDamageReducer
import org.rsmod.api.combat.formulas.PvpIncomingDamageReducer
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.death.NpcAttackValidateHook
import org.rsmod.api.death.PvPAttackValidateHook
import org.rsmod.api.death.PvPContextAuthorizationHook
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.game.process.npc.NpcQueueProcessor
import org.rsmod.api.game.process.player.PlayerQueueProcessor
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.net.rsprot.handlers.If3ButtonHandler
import org.rsmod.api.net.rsprot.handlers.OpPlayerTHandler
import org.rsmod.api.npc.access.StandardNpcAccessContextFactory
import org.rsmod.api.npc.access.StandardNpcAccessLauncher
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.processor.StandardNpcHitProcessor
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.hit.processor.DamageOnlyPlayerHitProcessor
import org.rsmod.api.player.interact.PlayerTInteractions
import org.rsmod.api.player.ironman.PlayerGamemode
import org.rsmod.api.player.ironman.setGamemode
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.loc.LocRegistryNormal
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.specials.SpecialAttackRegistry
import org.rsmod.api.specials.energy.SpecialAttackEnergy
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.NonAttackSpellProvider
import org.rsmod.api.spells.NonAttackSpellRegistry
import org.rsmod.api.spells.attack.SpellAttackRegistry
import org.rsmod.api.spells.autocast.AutocastWeapons
import org.rsmod.api.spells.runes.BlightedRunePackPolicy
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
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.content.areas.wilderness.WildernessBlightedRunePackPermission
import org.rsmod.content.areas.wilderness.WildernessPvPPermission
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.entity.player.WorldTypeChangedEvent
import org.rsmod.game.hit.Hit
import org.rsmod.game.interact.InteractionPlayerT
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.map.LocZoneStorage
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
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Native packet/event dispatch, inventory payment and real hit queues. Session reset and active
 * recast cases describe documented local policies; they are not observations of live OSRS.
 */
@OptIn(InternalApi::class)
@Isolated("Loads native cache and temporarily installs inventory transaction services")
class VengeanceSpellTest {
    @Test
    fun `native self button consumes one cast and leaves combat clocks and target untouched`() {
        val f = Fixture(); f.supplyRunes(f.self)
        val clocks = f.combatClocks(f.caster)
        f.castSelf()
        assertTrue(f.state.isActive(f.caster))
        assertEquals(1120, f.xp(f.caster))
        assertTrue(f.caster.inv.objs.all { it == null })
        assertEquals(clocks, f.combatClocks(f.caster))
        assertNull(f.caster.interaction)
        assertEquals(99, f.target.hitpoints)
        assertTrue(f.hits(f.target).isEmpty())
        f.castSelf()
        assertEquals(1120, f.xp(f.caster), "Repeated same-cycle packet cannot pay or award twice")
    }

    @Test
    fun `friendly Other packet works outside PvP after generic combat handlers register`() {
        val f = Fixture(); f.supplyRunes(f.other)
        f.target.statMap.setBaseLevel("stat.defence", 1)
        f.target.statMap.setCurrentLevel("stat.defence", 1)
        val clocks = f.combatClocks(f.caster)
        f.castOther()
        assertTrue(f.state.isActive(f.target))
        assertFalse(f.state.isActive(f.caster))
        assertEquals(1080, f.xp(f.caster))
        assertEquals(0, f.xp(f.target))
        assertTrue(f.caster.inv.objs.all { it == null })
        assertEquals(clocks, f.combatClocks(f.caster))
        assertTrue(f.hits(f.target).isEmpty(), "Aid must not enqueue an offensive hit")
        assertEquals(99, f.target.hitpoints)
        assertFalse(f.state.isCoolingDown(f.target), "Only the caster starts the shared cooldown")
    }

    @Test
    fun `completed quest does not bypass native book current level or base Lunar skill floors`() {
        val failures: List<(Fixture) -> Unit> = listOf(
            { it.questComplete = false },
            { VarPlayerIntMapSetter.set(it.caster, "varbit.spellbook", Spellbook.Standard.varValue) },
            { it.caster.statMap.setCurrentLevel("stat.magic", 93) },
            { it.caster.statMap.setBaseLevel("stat.magic", 64); it.caster.statMap.setCurrentLevel("stat.magic", 99) },
            { it.caster.statMap.setBaseLevel("stat.defence", 39); it.caster.statMap.setCurrentLevel("stat.defence", 99) },
        )
        for (change in failures) {
            val f = Fixture(); f.supplyRunes(f.self); change(f)
            val inventory = f.caster.inv.objs.toList()
            f.castSelf()
            assertFalse(f.state.isActive(f.caster))
            assertFalse(f.state.isCoolingDown(f.caster))
            assertEquals(inventory, f.caster.inv.objs.toList())
            assertEquals(0, f.xp(f.caster))
        }
        val boosted = Fixture(); boosted.supplyRunes(boosted.self)
        boosted.caster.statMap.setBaseLevel("stat.magic", 90)
        boosted.caster.statMap.setCurrentLevel("stat.magic", 94)
        boosted.castSelf()
        assertTrue(boosted.state.isActive(boosted.caster))
        assertEquals(1120, boosted.xp(boosted.caster))
    }

    @Test
    fun `Other rejects aid refusal solo iron and foreign mode without spending resources`() {
        val failures: List<(Fixture) -> Unit> = listOf(
            { VarPlayerIntMapSetter.set(it.target, "varbit.option_acceptaid", 0) },
            { it.target.setGamemode(PlayerGamemode.IRONMAN) },
            { it.target.worldType = WorldType.TRAILBLAZER_LEAGUE },
            { it.target.coords = CoordGrid(it.target.coords.x, it.target.coords.z, 1) },
        )
        for (change in failures) {
            val f = Fixture(); f.supplyRunes(f.other); change(f)
            val inventory = f.caster.inv.objs.toList()
            f.castOther()
            assertFalse(f.state.isActive(f.target))
            assertFalse(f.state.isCoolingDown(f.caster))
            assertEquals(inventory, f.caster.inv.objs.toList())
            assertEquals(0, f.xp(f.caster))
        }
    }

    @Test
    fun `Other single Wilderness restriction does not forbid ordinary safe area aid`() {
        val f = Fixture(WILD_SINGLE); f.supplyRunes(f.other)
        assertTrue(f.areas.inArea("area.wilderness", f.caster.coords))
        assertFalse(f.areas.inArea("area.multiway", f.caster.coords))
        val inventory = f.caster.inv.objs.toList()
        f.castOther()
        assertFalse(f.state.isActive(f.target))
        assertEquals(inventory, f.caster.inv.objs.toList())
        assertEquals(0, f.xp(f.caster))
    }

    @Test
    fun `eligible sack pays exactly once for either spell without spending ordinary runes`() {
        for (other in listOf(false, true)) {
            val f = Fixture(FEROX); val spell = if (other) f.other else f.self
            f.supplyRunes(spell)
            val ordinary = f.caster.inv.objs.toList()
            f.caster.inv[27] = InvObj(SACK, 2)
            if (other) f.castOther() else f.castSelf()
            assertEquals(1, f.caster.inv[27]?.count)
            assertEquals(ordinary.take(27), f.caster.inv.objs.take(27))
            assertEquals(if (other) 1080 else 1120, f.xp(f.caster))
            if (other) f.castOther() else f.castSelf()
            assertEquals(1, f.caster.inv[27]?.count)
        }
        val denied = Fixture(); denied.caster.inv[0] = InvObj(SACK)
        VarPlayerIntMapSetter.set(denied.caster, "varbit.blighted_items_allowed", 1)
        denied.castSelf()
        assertFalse(denied.state.isActive(denied.caster))
        assertEquals(InvObj(SACK), denied.caster.inv[0])
        assertEquals(0, denied.xp(denied.caster))
    }

    @Test
    fun `pouch and unlimited Earth staff pay native self cost without an inventory rune transaction`() {
        val f = Fixture(); f.caster.inv[0] = InvObj("obj.bh_rune_pouch")
        f.caster.worn[Wearpos.RightHand.slot] = InvObj("obj.staff_of_earth")
        val finite = f.self.objReqs.filterNot { it.obj.isType("obj.earthrune") }
        for ((index, requirement) in finite.withIndex()) {
            VarPlayerIntMapSetter.set(f.caster, "varbit.rune_pouch_type_${index + 1}", checkNotNull(f.compact[requirement.obj]))
            VarPlayerIntMapSetter.set(f.caster, "varbit.rune_pouch_quantity_${index + 1}", requirement.count)
        }
        val inventory = f.caster.inv.objs.toList()
        f.castSelf()
        assertTrue(f.state.isActive(f.caster))
        assertEquals(1120, f.xp(f.caster))
        assertEquals(inventory, f.caster.inv.objs.toList())
        for (index in finite.indices) assertEquals(0, f.caster.vars["varbit.rune_pouch_quantity_${index + 1}"])
    }

    @Test
    fun `self and Other share a caster cooldown with the exact fiftieth tick boundary`() {
        val f = Fixture(); f.supplyRunes(f.self, 2); f.castSelf()
        f.state.clearActive(f.caster)
        f.supplyRunes(f.other)
        val inventory = f.caster.inv.objs.toList()
        f.castOther(); f.advance(49); f.castOther()
        assertFalse(f.state.isActive(f.target))
        assertEquals(inventory, f.caster.inv.objs.toList())
        assertEquals(1120, f.xp(f.caster))
        f.advance(); f.castOther()
        assertTrue(f.state.isActive(f.target))
        assertEquals(2200, f.xp(f.caster))
        assertFalse(f.state.isCoolingDown(f.target))
    }

    @Test
    fun `unclaimed hostile Entangle still follows PvP admission instead of the friendly handler`() {
        val f = Fixture(); val entangle = f.spell("obj.79_entangle")
        VarPlayerIntMapSetter.set(f.caster, "varbit.spellbook", Spellbook.Standard.varValue)
        f.supplyRunes(entangle)
        val inventory = f.caster.inv.objs.toList()
        f.castPlayerPacket(entangle)
        assertTrue(f.hits(f.target).isEmpty())
        assertEquals(inventory, f.caster.inv.objs.toList())
        assertFalse(f.state.isActive(f.target))
        assertEquals(0, f.xp(f.caster))
        assertTrue(f.hostileValidations > 0, "The registered hostile spell must still invoke real authorization")
    }

    @Test
    fun `local active recast policy preserves the charge and all resources after cooldown`() {
        val f = Fixture(); f.supplyRunes(f.self, 2); f.castSelf(); f.advance(50)
        val inventory = f.caster.inv.objs.toList()
        f.castSelf()
        assertTrue(f.state.isActive(f.caster))
        assertFalse(f.state.isCoolingDown(f.caster))
        assertEquals(inventory, f.caster.inv.objs.toList())
        assertEquals(1120, f.xp(f.caster))
    }

    @Test
    fun `local session cleanup preserves other bits sharing the permanent spellbook varp`() {
        for (deleteOnly in listOf(false, true)) {
            val f = Fixture(); f.supplyRunes(f.self); f.castSelf()
            if (!deleteOnly) f.events.publish(SessionStateEvent.Logout(f.caster))
            f.events.publish(SessionStateEvent.Delete(f.caster))
            f.events.publish(SessionStateEvent.Delete(f.caster))
            assertFalse(f.state.isActive(f.caster))
            assertFalse(f.state.isCoolingDown(f.caster))
            assertEquals(Spellbook.Lunars.varValue, f.caster.vars["varbit.spellbook"])
            assertEquals(1, f.caster.vars["varbit.option_acceptaid"])
        }
    }

    @Test
    fun `pending mode switch retains charge on abort while actual save swap clears it`() {
        val f = Fixture(); f.supplyRunes(f.self); f.castSelf()
        f.caster.worldTypeSwitching = true
        f.advance(5)
        assertTrue(f.state.isActive(f.caster))
        assertTrue(f.state.isCoolingDown(f.caster))
        f.caster.worldTypeSwitching = false
        f.advance()
        assertTrue(f.state.isActive(f.caster))
        f.caster.worldType = WorldType.TRAILBLAZER_LEAGUE
        f.events.publish(WorldTypeChangedEvent(f.caster, WorldType.MAIN, WorldType.TRAILBLAZER_LEAGUE))
        assertFalse(f.state.isActive(f.caster))
        assertFalse(f.state.isCoolingDown(f.caster))
        assertEquals(1120, f.xp(f.caster))
    }

    @Test
    fun `restricted activity provider blocks payment and clears active charge on entry`() {
        val f = Fixture(); f.restricted = true; f.supplyRunes(f.self)
        val inventory = f.caster.inv.objs.toList()
        f.castSelf()
        assertEquals(inventory, f.caster.inv.objs.toList())
        assertEquals(0, f.xp(f.caster))
        assertFalse(f.state.isActive(f.caster))
        f.restricted = false; f.castSelf()
        assertTrue(f.state.isActive(f.caster))
        f.restricted = true; f.advance()
        assertFalse(f.state.isActive(f.caster))
        assertTrue(f.state.isCoolingDown(f.caster), "Activity entry clears the charge, not an unrelated caster cooldown")
    }

    private class Fixture(coords: CoordGrid = SAFE) {
        val caster = newPlayer(1, coords)
        val target = newPlayer(2, coords.translate(1, 0))
        val npc = Npc("npc.king_dragon", coords.translate(1, 0)).apply {
            slotId = 1; assignUid(); currentMapClock = 100
        }
        val events = EventBus()
        val players = PlayerList().also { it[caster.slotId] = caster; it[target.slotId] = target }
        val npcs = NpcList().also { it[npc.slotId] = npc }
        private val beforeXp = players.associateWith { it.statMap.getFineXP("stat.magic") }
        private val scripts = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
        val random = StrictRandom()
        private val collision = CollisionFlagMap()
        private val locs = LocZoneStorage()
        private val regions = RegionRegistry(
            RegionListSmall(), RegionListLarge(), RegionListWorldEntity(),
            LocRegistryNormal(ZoneUpdateMap(), collision, locs), collision, locs,
            mockk(), mockk(), ZonePlayerActivityBitSet(),
        )
        val areas = AreaChecker(regions, areaIndex)
        var restricted = false
        var questComplete = true
        var hostileValidations = 0
        val state = VengeanceState(players, setOf(VengeanceRestrictedArea { restricted }))
        val spells = Guice.createInjector(MagicSpellModule()).getInstance(MagicSpellRegistry::class.java)
        val self = spell(SELF)
        val other = spell(OTHER)
        val compact = CompactRuneRepository()
        private val fake = FakeRuneRepository()
        private val combo = ComboRuneRepository()
        private val unlimited = UnlimitedRuneRepository()
        private val staff = StaffSubstituteRepository()
        private val substitutes = RuneSubstituteRepository()
        val runes = MagicRuneManager(fake, combo, compact, unlimited, staff, substitutes,
            setOf(SpellQuestRequirement { _, quest ->
                assertEquals("quest_lunardiplomacy", quest)
                questComplete
            }), BlightedRunePackPolicy(setOf(WildernessBlightedRunePackPermission(areas))))
        private val vengeance = VengeanceSpells(spells, runes, state, areas, regions, emptySet())
        private val styles = AttackStyles()
        private val reductions = DamageReductionAttributeCollector(styles)
        val playerModifier = StandardPlayerHitModifier(events,
            NpcIncomingDamageReducer(random, WornBonuses(), reductions),
            PvpIncomingDamageReducer(random, reductions))
        private val npcModifier = StandardNpcHitModifier(events)
        private val npcContext = StandardNpcAccessContextFactory(random, npcModifier,
            StandardNpcHitProcessor(players, events, emptySet(), emptySet()))
        private val context = ProtectedAccessContextFactory.empty().copy(
            getAreaChecker = { areas }, getEventBus = { events }, getRandom = { random },
            getCollision = { collision }, getPlayerList = { players }, getNpcList = { npcs },
            getHitModifier = { playerModifier },
            getInstantHitProcessor = { DamageOnlyPlayerHitProcessor(events, npcs, players) },
        )
        private val launcher = ProtectedAccessLauncher(mockk<ProtectedAccessContextFactory>().also {
            every { it.create() } returns context
        })
        private val access = ProtectedAccess(caster, GameCoroutine("vengeance-cast"), context)
        private val playerProcess = PlayerQueueProcessor(events, launcher)
        private val npcProcess = NpcQueueProcessor(events, StandardNpcAccessLauncher(npcContext))
        private val playerInteractions = PlayerTInteractions(events)
        private val playerPacket = OpPlayerTHandler(events, players, playerInteractions)
        private val selfPacket = If3ButtonHandler(events, launcher)
        val playerImpacts = mutableListOf<Pair<Player, Hit>>()
        val npcImpacts = mutableListOf<Hit>()

        init {
            for (player in players) {
                VarPlayerIntMapSetter.set(player, "varbit.spellbook", Spellbook.Lunars.varValue)
                VarPlayerIntMapSetter.set(player, "varbit.option_acceptaid", 1)
            }
            for (script in listOf(FakeRuneScript(fake), ComboRuneScript(combo), CompactRuneScript(compact),
                UnlimitedRuneScript(unlimited), StaffSubstituteScript(staff), RuneSubstituteScript(substitutes),
                NpcHitScript(), PlayerHitScript())) with(script) { scripts.startup() }
            events.subscribeUnbound(PlayerHitEvents.Impact::class.java) { playerImpacts += player to hit }
            events.subscribeKeyed(NpcHitEvents.Impact::class.java, npc.id.toLong()) { npcImpacts += hit }

            // Register the real generic combat scripts before the content scripts. Reflection only
            // crosses Kotlin-internal constructor visibility; packets and event handlers are real.
            val nonAttack = NonAttackSpellRegistry(setOf(object : NonAttackSpellProvider {
                override val spells = setOf(SELF, OTHER)
            }))
            val manager = mockk<PlayerAttackManager>()
            val attacks = SpellAttackRegistry()
            val types = AttackTypes()
            val authorization = PvPContextAuthorizationHook(setOf(WildernessPvPPermission(areas, regions)))
            val validators = setOf(PvPAttackValidateHook { attacker, victim ->
                hostileValidations++
                authorization.validate(attacker, victim)
            })
            val pvp = construct("org.rsmod.api.combat.PvPCombat", WeaponSpeeds(styles),
                mockk<SpecialAttackRegistry>(), mockk<SpecialAttackEnergy>(), WeaponRegistry(), manager,
                mockk<RangedAmmoManager>(), attacks, emptySet<Any>(), emptySet<Any>())
            val pvn = construct("org.rsmod.api.combat.PvNCombat", WeaponSpeeds(styles),
                mockk<SpecialAttackRegistry>(), mockk<SpecialAttackEnergy>(), WeaponRegistry(), manager,
                mockk<RangedAmmoManager>(), attacks, emptySet<NpcAttackValidateHook>())
            for (script in listOf(
                construct("org.rsmod.api.combat.scripts.PvPCombatScript", styles, types, pvp,
                    spells, runes, mockk<AutocastWeapons>(), validators, nonAttack) as PluginScript,
                construct("org.rsmod.api.combat.scripts.PvNCombatScript", styles, types, pvn,
                    spells, runes, mockk<AutocastWeapons>(), emptySet<NpcAttackValidateHook>(), nonAttack) as PluginScript,
                VengeanceScript(spells, vengeance, state),
            )) with(script) { scripts.startup() }
        }

        fun spell(symbol: String): MagicSpell = checkNotNull(spells.getObjSpell(item(symbol)))

        fun supplyRunes(spell: MagicSpell, casts: Int = 1) {
            for ((slot, requirement) in spell.objReqs.withIndex()) {
                assertNull(requirement.wornSlot)
                caster.inv[slot] = InvObj(requirement.obj, requirement.count * casts)
            }
        }

        private fun openSpellbook(player: Player, spell: MagicSpell) {
            val side = ServerCacheManager.fromComponent("component.toplevel_osrs_stretch:side6")
            player.ui.overlays[Component(side.packed)] = UserInterface(ServerCacheManager.fromInterface(spell.component.packed))
        }

        fun castSelf() {
            openSpellbook(caster, self)
            selfPacket.consume(caster, If3Button(CombinedId(self.component.packed), -1, -1, 1))
        }

        fun castOther() = castPlayerPacket(other)

        fun castPlayerPacket(spell: MagicSpell) {
            openSpellbook(caster, spell)
            playerPacket.consume(caster, OpPlayerT(target.slotId, false, CombinedId(spell.component.packed), -1, -1))
            val interaction = caster.interaction as? InteractionPlayerT ?: error("Native packet did not select a player")
            val event = checkNotNull(playerInteractions.apTrigger(interaction.target, interaction.component, interaction.comsub, interaction.objType))
            runImmediately { assertTrue(events.publish(access, event)) }
        }

        fun hits(player: Player): List<Hit> = buildList {
            val iterator = player.queueList.iterator() ?: return@buildList
            while (iterator.hasNext()) {
                val queue = iterator.next()
                if (queue.id == "queue.hit".asRSCM()) add(queue.args as Hit)
            }
            iterator.cleanUp()
        }

        fun xp(player: Player): Int = player.statMap.getFineXP("stat.magic") - beforeXp.getValue(player)

        fun combatClocks(player: Player) = listOf(player.actionDelay, player.vars["varp.lastcombat"], player.vars["varp.lastcombat_pvp"])

        fun advance(ticks: Int = 1) {
            repeat(ticks) {
                caster.currentMapClock++
                target.currentMapClock = caster.currentMapClock
                npc.currentMapClock = caster.currentMapClock
                events.publish(GameLifecycle.StartCycle)
                playerProcess.process(caster)
                playerProcess.process(target)
                npcProcess.process(npc)
            }
        }
    }

    private class StrictRandom : GameRandom {
        var elysianRolls = 0
        override fun of(maxExclusive: Int): Int = when (maxExclusive) {
            10 -> { elysianRolls++; 0 }
            4 -> 0 // Existing native defend sound selection.
            else -> error("Unexpected RNG bound $maxExclusive")
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unexpected damage reroll $minInclusive..$maxInclusive")
        override fun randomDouble(): Double = error("Unexpected floating RNG")
    }

    private class SilentClient : Client<Any, Any> {
        val messages = mutableListOf<Any>()
        override fun write(message: Any) { messages += message }
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private const val SELF = "obj.94_vengeance"
        private const val OTHER = "obj.93_vengeance_other"
        private const val SACK = "obj.blighted_sack_vengeance"
        private val SAFE = CoordGrid(3222, 3218)
        private val FEROX = CoordGrid(3132, 3637)
        private val WILD_SINGLE = CoordGrid(3087, 3530)
        private lateinit var areaIndex: AreaIndex
        private val restorations = mutableListOf<() -> Unit>()

        @JvmStatic @BeforeAll
        fun cache() {
            val cache = ServerCacheManager.init(241)
            try {
                areaIndex = AreaIndex()
                for (region in cache.archives(MAPS)) {
                    if (region > 25286 || 7 !in cache.files(MAPS, region)) continue
                    if (cache.data(MAPS, region, 0) == null || cache.data(MAPS, region, 1) == null) continue
                    val data = checkNotNull(cache.data(MAPS, region, 7))
                    MapAreaDefinitions.putAreas(areaIndex, MapSquareKey(region), MapAreaDecoder.decode(InlineByteBuf(data)))
                }
            } finally { cache.close() }
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val previous = field.get(null)
                restorations += { field.set(null, previous) }
            }
            val previousStorage = InvVirtualStorageHolder.instance
            restorations += { InvVirtualStorageHolder.instance = previousStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic @AfterAll
        fun restoreGlobals() { restorations.asReversed().forEach { it() }; restorations.clear() }

        private fun newPlayer(slot: Int, coords: CoordGrid) = Player().apply {
            slotId = slot; uuid = slot.toLong(); worldType = WorldType.MAIN; assignUid()
            client = SilentClient(); this.coords = coords
            currentMapClock = 100; processedMapClock = 100; appearance.combatLevel = 126
            inv = invMap.getOrPut("inv.inv"); worn = invMap.getOrPut("inv.worn")
            for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.ranged", "stat.magic", "stat.hitpoints", "stat.prayer")) {
                statMap.setBaseLevel(stat, 99); statMap.setCurrentLevel(stat, 99)
                statMap.setXP(stat, PlayerSkillXPTable.getXPFromLevel(99))
            }
        }

        private fun item(symbol: String): ItemServerType = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))
        private fun construct(className: String, vararg arguments: Any): Any =
            Class.forName(className).constructors.single { it.parameterCount == arguments.size }.newInstance(*arguments)
        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Spell operation unexpectedly suspended" }.getOrThrow()
        }
    }
}
