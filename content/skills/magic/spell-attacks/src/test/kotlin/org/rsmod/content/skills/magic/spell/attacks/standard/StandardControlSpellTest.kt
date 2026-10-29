package org.rsmod.content.skills.magic.spell.attacks.standard
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
import dev.openrune.util.Wearpos
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
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
import org.rsmod.api.combat.FreezeTimerScript
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatEffects
import org.rsmod.api.combat.commons.magic.SpellQuestRequirement
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.formulas.accuracy.magic.PvNMagicAccuracy
import org.rsmod.api.combat.formulas.accuracy.magic.PvPMagicAccuracy
import org.rsmod.api.combat.formulas.attributes.collector.CombatMagicAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatNpcAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.magic.PvNMagicMaxHit
import org.rsmod.api.combat.formulas.maxhit.magic.PvPMagicMaxHit
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.death.PvPAttackValidateHook
import org.rsmod.api.death.PvPAttackValidateResult
import org.rsmod.api.death.PvPContextAuthorizationHook
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.game.process.npc.NpcQueueProcessor
import org.rsmod.api.game.process.player.PlayerQueueProcessor
import org.rsmod.api.game.process.player.PlayerTimerProcessor
import org.rsmod.api.game.process.world.WorldQueueListProcess
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.net.rsprot.handlers.OpPlayerTHandler
import org.rsmod.api.npc.access.StandardNpcAccessContextFactory
import org.rsmod.api.npc.access.StandardNpcAccessLauncher
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.processor.StandardNpcHitProcessor
import org.rsmod.api.npc.plugin.AiSpawnScript
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.death.DEATH_CAUSE_ATTR
import org.rsmod.api.player.death.DeathCause
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.interact.PlayerTInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.loc.LocRegistryNormal
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.specials.SpecialAttackRegistry
import org.rsmod.api.specials.energy.SpecialAttackEnergy
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackRegistry
import org.rsmod.api.spells.attack.SpellAttackRepository
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
import org.rsmod.content.areas.wilderness.WildernessPvPHook
import org.rsmod.content.areas.wilderness.WildernessPvPPermission
import org.rsmod.content.skills.magic.spell.attacks.standard.ElementalSpells
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.entity.player.WorldTypeChangedEvent
import org.rsmod.game.hit.Hit
import org.rsmod.game.interact.InteractionPlayerT
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.queue.WorldQueueList
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

/** Real registered handlers, native runes and areas, packet dispatch, hit queues and status lifecycle. */
@OptIn(InternalApi::class)
@Isolated("Loads native cache and temporarily installs inventory transaction services")
class StandardControlSpellTest {
    @Test
    fun `all three binding spells pay once and bind at the scheduled effect with native durations`() {
        for ((symbol, duration) in listOf(BIND to 8, SNARE to 16, ENTANGLE to 24)) {
            val f = Fixture(symbol)
            f.supplyRunes()
            f.castPlayerPacket()
            assertFalse(f.target.isFrozen)
            assertEquals(1, f.playerHits().size)
            assertTrue(f.caster.inv.objs.all { it == null })
            val xp = f.xp()
            val baseXp = when (symbol) { BIND -> 310; SNARE -> 610; else -> 890 }
            assertEquals(126, f.target.appearance.combatLevel)
            // The existing level-126 PvP target bonus is 1.125 on damage XP only:
            // 2 Magic XP * 10 fine units * 1.125 = 45/2 fine units per damage.
            // Outcome-specific cast XP is awarded separately without that target bonus.
            val damageXp = f.playerHits().single().damage * 45 / 2
            assertEquals(baseXp + damageXp, xp.getValue("stat.magic"))
            repeat(10) { if (!f.target.isFrozen) f.advance() }
            assertTrue(f.target.isFrozen)
            assertEquals(duration, f.target.vars["varp.standard_bind_end_tick"] - f.target.currentMapClock)
            f.advance(duration - 1)
            assertTrue(f.target.isFrozen)
            f.advance()
            assertFalse(f.target.isFrozen)
            assertTrue(f.target.freezeImmune)
            f.advance(5)
            assertFalse(f.target.freezeImmune)
            assertEquals(xp, f.xp(), "Delayed effects must not grant XP again")
        }
    }

    @Test
    fun `registered NPC binding applies only to the same living NPC after actual projectile delay`() {
        val f = Fixture(ENTANGLE); f.supplyRunes(); f.castNpc()
        assertFalse(CombatEffects.isFrozen(f.primary))
        assertEquals(1, f.npcHits().size)
        repeat(10) { if (!CombatEffects.isFrozen(f.primary)) f.advance() }
        assertTrue(CombatEffects.isFrozen(f.primary))
        assertEquals(24, f.primary.vars["varn.freeze_end_clock"] - f.primary.currentMapClock)
        val stale = Fixture(BIND); stale.supplyRunes(); stale.castNpc()
        stale.events.publish(NpcStateEvents.Delete(stale.primary))
        stale.events.publish(NpcStateEvents.Respawn(stale.primary))
        stale.advance(5)
        assertFalse(CombatEffects.isFrozen(stale.primary))
    }

    @Test
    fun `Protect Magic keeps full binding duration and only the three Swampbark pieces extend it`() {
        val f = Fixture(ENTANGLE)
        VarPlayerIntMapSetter.set(f.target, "varbit.prayer_protectfrommagic", 1)
        for ((slot, symbol) in listOf(
            Wearpos.Hat to "obj.swampbark_helm", Wearpos.Torso to "obj.swampbark_body",
            Wearpos.Legs to "obj.swampbark_legs", Wearpos.Hands to "obj.swampbark_gauntlets",
            Wearpos.Feet to "obj.swampbark_greaves",
        )) f.caster.worn[slot.slot] = InvObj(symbol)
        f.supplyRunes(); f.castPlayerPacket()
        repeat(10) { if (!f.target.isFrozen) f.advance() }
        assertTrue(f.target.isFrozen)
        assertEquals(30, f.target.vars["varp.standard_bind_end_tick"] - f.target.currentMapClock)
    }

    @Test
    fun `frozen and immune targets reject repeat casts without spending inputs or granting XP`() {
        val f = Fixture(SNARE)
        f.supplyRunes(); f.castPlayerPacket(); f.advance(3)
        assertTrue(f.target.isFrozen)
        f.supplyRunes()
        val before = f.caster.inv.objs.toList(); val xp = f.xp(); val rolls = f.random.accuracyDraws
        f.castPlayerDirect()
        assertEquals(before, f.caster.inv.objs.toList()); assertEquals(xp, f.xp()); assertEquals(rolls, f.random.accuracyDraws)
        CombatEffects.unfreeze(f.target)
        f.castPlayerDirect()
        assertEquals(before, f.caster.inv.objs.toList()); assertEquals(xp, f.xp()); assertEquals(rolls, f.random.accuracyDraws)
    }

    @Test
    fun `splash pays one complete recipe and awards outcome XP without status or damage XP`() {
        for ((symbol, tenths) in listOf(BIND to 300, SNARE to 600, ENTANGLE to 700, TELEBLOCK to 836)) {
            val f = Fixture(symbol); f.random.splash = true; f.supplyRunes(); f.castPlayerPacket()
            assertEquals(tenths, f.xp().getValue("stat.magic"), symbol)
            assertEquals(0, f.xp().getValue("stat.hitpoints"))
            assertTrue(f.caster.inv.objs.all { it == null })
            assertTrue(f.playerHits().single().isMagicSplash)
            assertEquals(0, f.target.vars["varbit.teleblock_cycles"])
            f.tickImpacts(); assertFalse(f.target.isFrozen); assertEquals(99, f.target.hitpoints)
        }
    }

    @Test
    fun `missing requirements and wrong level or book preserve every rune and launch no effects`() {
        for (problem in listOf("missing", "level", "book")) {
            val f = Fixture(ENTANGLE); f.supplyRunes()
            when (problem) {
                "missing" -> f.caster.inv[1] = null
                "level" -> f.caster.statMap.setCurrentLevel("stat.magic", 78)
                "book" -> VarPlayerIntMapSetter.set(f.caster, "varbit.spellbook", Spellbook.Lunars.varValue)
            }
            val before = f.caster.inv.objs.toList()
            f.castPlayerDirect()
            assertEquals(before, f.caster.inv.objs.toList()); assertEquals(0, f.xp().values.sum())
            assertEquals(0, f.random.accuracyDraws); assertTrue(f.worldQueues.isEmpty); assertTrue(f.playerHits().isEmpty())
        }
    }

    @Test
    fun `Tele Block applies immediately with cast-time prayer and only target logout protection`() {
        for (protected in listOf(false, true)) {
            val f = Fixture(TELEBLOCK); f.supplyRunes()
            VarPlayerIntMapSetter.set(f.target, "varbit.prayer_protectfrommagic", if (protected) 1 else 0)
            VarPlayerIntMapSetter.set(f.caster, "varp.lastcombat", 12)
            f.castPlayerPacket()
            assertEquals(if (protected) 350 else 600, f.target.vars["varbit.teleblock_cycles"])
            assertEquals(950, f.xp().getValue("stat.magic"))
            assertEquals(0, f.xp().getValue("stat.hitpoints"))
            assertEquals(12, f.caster.vars["varp.lastcombat"])
            assertEquals(100, f.target.vars["varp.lastcombat"])
            assertTrue(f.playerHits().isEmpty(), "Tele Block must not manufacture a damage hitsplat")
            VarPlayerIntMapSetter.set(f.target, "varbit.prayer_protectfrommagic", if (protected) 0 else 1)
            f.advance(3)
            assertEquals((if (protected) 350 else 600) - 3, f.target.vars["varbit.teleblock_cycles"])
        }
    }

    @Test
    fun `Tele Block splash also leaves caster logout clock unchanged`() {
        val f = Fixture(TELEBLOCK); f.random.splash = true; f.supplyRunes()
        VarPlayerIntMapSetter.set(f.caster, "varp.lastcombat", 12)
        f.castPlayerPacket(); f.tickImpacts()
        assertEquals(12, f.caster.vars["varp.lastcombat"])
        assertEquals(100, f.target.vars["varp.lastcombat"])
        assertEquals(0, f.target.vars["varbit.teleblock_cycles"])
    }

    @Test
    fun `Tele Block expires into one minute immunity then allows a new fully paid cast`() {
        val f = Fixture(TELEBLOCK); f.supplyRunes(); f.castPlayerPacket()
        f.supplyRunes(); val before = f.caster.inv.objs.toList(); val xp = f.xp()
        f.castPlayerDirect(); assertEquals(before, f.caster.inv.objs.toList()); assertEquals(xp, f.xp())
        f.advance(499); assertEquals(101, f.target.vars["varbit.teleblock_cycles"])
        f.advance(); assertEquals(100, f.target.vars["varbit.teleblock_cycles"])
        f.castPlayerDirect(); assertEquals(before, f.caster.inv.objs.toList()); assertEquals(xp, f.xp())
        f.advance(99); assertEquals(1, f.target.vars["varbit.teleblock_cycles"])
        f.castPlayerDirect(); assertEquals(before, f.caster.inv.objs.toList())
        f.advance(); assertEquals(0, f.target.vars["varbit.teleblock_cycles"])
        f.castPlayerDirect(); assertEquals(600, f.target.vars["varbit.teleblock_cycles"])
        assertTrue(f.caster.inv.objs.all { it == null }); assertEquals(1900, f.xp().getValue("stat.magic"))
    }

    @Test
    fun `Tele Block rejects NPC and safe targets without consuming any rune`() {
        for (npc in listOf(false, true)) {
            val f = Fixture(TELEBLOCK, if (npc) WILD else SAFE); f.supplyRunes()
            val before = f.caster.inv.objs.toList()
            if (npc) f.castNpc() else f.castPlayerDirect()
            assertEquals(before, f.caster.inv.objs.toList()); assertEquals(0, f.xp().values.sum())
            assertTrue(f.npcHits().isEmpty()); assertTrue(f.playerHits().isEmpty()); assertEquals(0, f.random.accuracyDraws)
        }
    }

    @Test
    fun `logout death mode switch and Wilderness exit clear Tele Block without immunity`() {
        for (reason in listOf("logout", "death", "mode", "exit")) {
            val f = Fixture(TELEBLOCK); f.supplyRunes(); f.castPlayerPacket()
            when (reason) {
                "logout" -> f.events.publish(SessionStateEvent.Logout(f.target))
                "death" -> f.state.cleanup(f.target)
                "mode" -> {
                    f.target.worldType = WorldType.TWISTED_LEAGUE
                    f.events.publish(WorldTypeChangedEvent(f.target, WorldType.MAIN, WorldType.TWISTED_LEAGUE))
                }
                "exit" -> { f.target.coords = SAFE; f.advance() }
            }
            assertEquals(0, f.target.vars["varbit.teleblock_cycles"], reason)
            assertEquals(0, f.target.vars["varp.standard_teleblock_last_tick"])
        }
    }

    @Test
    fun `caster logout does not clear Tele Block but killing that caster does without immunity`() {
        val f = Fixture(TELEBLOCK); f.supplyRunes(); f.castPlayerPacket()
        f.events.publish(SessionStateEvent.Logout(f.caster))
        assertEquals(600, f.target.vars["varbit.teleblock_cycles"])
        val unrelated = newPlayer(3, WILD)
        f.caster.attr[DEATH_CAUSE_ATTR] = DeathCause.ByPlayer(unrelated)
        f.state.beforeDrops(f.caster)
        assertEquals(600, f.target.vars["varbit.teleblock_cycles"])
        f.caster.attr[DEATH_CAUSE_ATTR] = DeathCause.ByPlayer(f.target)
        f.state.beforeDrops(f.caster)
        assertEquals(0, f.target.vars["varbit.teleblock_cycles"])
    }

    @Test
    fun `stale target identity and changed caster mode cannot receive delayed binding`() {
        for (reuseTarget in listOf(false, true)) {
            val f = Fixture(ENTANGLE); f.supplyRunes(); f.castPlayerPacket()
            if (reuseTarget) { f.target.uuid = 1234; f.target.assignUid() } else f.caster.worldType = WorldType.TWISTED_LEAGUE
            f.advance(5)
            assertFalse(f.target.isFrozen)
            assertTrue(f.worldQueues.isEmpty)
        }
    }

    @Test
    fun `leaving binding distance releases the actual held player and starts shared immunity`() {
        val f = Fixture(ENTANGLE); f.supplyRunes(); f.castPlayerPacket(); f.advance(3)
        assertTrue(f.target.isFrozen)
        f.caster.coords = f.target.coords.translate(11, 0)
        f.advance()
        assertFalse(f.target.isFrozen); assertTrue(f.target.freezeImmune)
        f.advance(5); assertFalse(f.target.freezeImmune)
    }

    @Test
    fun `full inventory and maximum sack stack conserve exactly one paid control cast`() {
        for (symbol in listOf(BIND, SNARE, ENTANGLE, TELEBLOCK)) {
            val f = Fixture(symbol); f.random.splash = true
            for (slot in f.caster.inv.indices) f.caster.inv[slot] = InvObj("obj.bronze_dagger")
            val sack = if (symbol == TELEBLOCK) "obj.blighted_sack_teleblock" else "obj.blighted_sack_entangle"
            f.supplySack(sack, Int.MAX_VALUE)
            val before = f.caster.inv.objs.take(SACK_SLOT)
            f.castPlayerDirect()
            assertEquals(Int.MAX_VALUE - 1, f.caster.inv[SACK_SLOT]?.count)
            assertEquals(before, f.caster.inv.objs.take(SACK_SLOT))
            f.castPlayerDirect()
            assertEquals(Int.MAX_VALUE - 2, f.caster.inv[SACK_SLOT]?.count)
            assertEquals(2, f.random.accuracyDraws)
        }
    }

    @Test
    fun `last control sack disappears once and cannot be replayed after exhaustion`() {
        val f = Fixture(BIND); f.random.splash = true; f.supplySack("obj.blighted_sack_entangle")
        f.castPlayerDirect(); val xp = f.xp(); val hits = f.playerHits().size
        f.castPlayerDirect()
        assertNull(f.caster.inv[SACK_SLOT]); assertEquals(xp, f.xp()); assertEquals(hits, f.playerHits().size)
        assertEquals(1, f.random.accuracyDraws)
    }

    @Test
    fun `pending failed world switch preserves existing statuses and resumes their original deadlines`() {
        val tb = Fixture(TELEBLOCK); tb.supplyRunes(); tb.castPlayerPacket()
        tb.target.worldTypeSwitching = true
        tb.advance(3)
        assertEquals(600, tb.target.vars["varbit.teleblock_cycles"])
        tb.target.worldTypeSwitching = false
        tb.advance()
        assertEquals(596, tb.target.vars["varbit.teleblock_cycles"])

        val bind = Fixture(ENTANGLE); bind.supplyRunes(); bind.castPlayerPacket(); bind.advance(3)
        assertTrue(bind.target.isFrozen)
        val end = bind.target.vars["varp.standard_bind_end_tick"]
        bind.caster.worldTypeSwitching = true
        bind.advance(2)
        assertTrue(bind.target.isFrozen)
        assertEquals(end, bind.target.vars["varp.standard_bind_end_tick"])
        bind.caster.worldTypeSwitching = false
        bind.advance()
        assertTrue(bind.target.isFrozen)
    }

    @Test
    fun `paid delayed binding survives aborted account swap but successful swap invalidates it`() {
        for (successful in listOf(false, true)) {
            val f = Fixture(ENTANGLE); f.supplyRunes(); f.castPlayerPacket()
            f.target.worldTypeSwitching = true
            f.advance(5)
            assertFalse(f.target.isFrozen)
            assertEquals(1, f.worldQueues.size, "Exactly one deferred effect remains")
            val xp = f.xp()
            if (successful) {
                f.target.worldType = WorldType.TWISTED_LEAGUE
                f.events.publish(WorldTypeChangedEvent(f.target, WorldType.MAIN, WorldType.TWISTED_LEAGUE))
            }
            f.target.worldTypeSwitching = false
            f.advance()
            assertEquals(!successful, f.target.isFrozen)
            assertTrue(f.worldQueues.isEmpty)
            assertEquals(xp, f.xp())
            assertTrue(f.caster.inv.objs.all { it == null })
        }
    }

    @Test
    fun `caster account identity survives relogged slot changes without truncated UID collision`() {
        val f = Fixture(TELEBLOCK); f.supplyRunes(); f.castPlayerPacket()
        val collision = newPlayer(f.caster.slotId, WILD).apply {
            uuid = checkNotNull(f.caster.uuid) + (1L shl 32)
            assignUid()
            attr[DEATH_CAUSE_ATTR] = DeathCause.ByPlayer(f.target)
        }
        assertEquals(f.caster.uid, collision.uid, "Fixture proves a real truncated protocol UID collision")
        f.state.beforeDrops(collision)
        assertEquals(600, f.target.vars["varbit.teleblock_cycles"])
        val relogged = newPlayer(7, WILD).apply {
            uuid = f.caster.uuid
            assignUid()
            attr[DEATH_CAUSE_ATTR] = DeathCause.ByPlayer(f.target)
        }
        f.state.beforeDrops(relogged)
        assertEquals(0, f.target.vars["varbit.teleblock_cycles"])
        assertEquals(0, f.target.vars["varp.standard_teleblock_caster_uuid_low"])
        assertEquals(0, f.target.vars["varp.standard_teleblock_caster_uuid_high"])
    }

    private class Fixture(
        spellSymbol: String,
        coords: CoordGrid = WILD,
        includeSecondary: Boolean = false,
        questRequirements: Set<SpellQuestRequirement> = emptySet(),
    ) {
        val caster = newPlayer(1, coords)
        val target = newPlayer(2, coords.translate(1, 0))
        val primary = Npc("npc.man", coords.translate(1, 0)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
        }
        val initialNpcHp = primary.hitpoints
        private val beforeXp = XP_STATS.associateWith(caster.statMap::getFineXP)
        val random = CastRandom()
        val events = EventBus()
        private val scripts = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
        val players = PlayerList().also { it[caster.slotId] = caster; it[target.slotId] = target }
        private val npcs = NpcList().also { it[primary.slotId] = primary }
        private val collision = CollisionFlagMap()
        private val locs = LocZoneStorage()
        private val regions = RegionRegistry(
            RegionListSmall(), RegionListLarge(), RegionListWorldEntity(),
            LocRegistryNormal(ZoneUpdateMap(), collision, locs), collision, locs,
            mockk(), mockk(), ZonePlayerActivityBitSet(),
        )
        val areas = AreaChecker(regions, areaIndex)
        val state = ControlSpellState(players, areas)
        val policy = BlightedRunePackPolicy(setOf(WildernessBlightedRunePackPermission(areas)))
        private val authorization = PvPContextAuthorizationHook(setOf(WildernessPvPPermission(areas, regions)))
        private val wildernessRules = WildernessPvPHook(areas)
        var denyLegacy = false
        private val validators = linkedSetOf(
            PvPAttackValidateHook { _, _ -> if (denyLegacy) PvPAttackValidateResult.Deny("Test veto") else PvPAttackValidateResult.Pass },
            authorization, wildernessRules,
        )
        private val styles = AttackStyles().also { with(WeaponAttackStylesScript(it)) { scripts.startup() } }
        private val types = AttackTypes().also { with(WeaponAttackTypesScript(it)) { scripts.startup() } }
        private val bonuses = WornBonuses()
        private val magicAttributes = CombatMagicAttributeCollector(areas)
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
        private val npcModifier = StandardNpcHitModifier(events)
        private val playerModifier = StandardPlayerHitModifier(events)
        private val world = mockk<WorldRepository>(relaxUnitFun = true)
        private val manager = PlayerAttackManager(random, events, world, accuracy, maxima, npcModifier, playerModifier,
            mockk(), mockk(), mockk(), mockk(), setOf(wildernessRules))
        private val context = ProtectedAccessContextFactory.empty().copy(
            getAreaChecker = { areas }, getEventBus = { events }, getRandom = { random },
            getPlayerList = { players }, getNpcList = { npcs }, getHitModifier = { playerModifier },
        )
        private val access = ProtectedAccess(caster, GameCoroutine("rune-sack-cast"), context)
        val worldQueues = WorldQueueList()
        private val worldProcess = WorldQueueListProcess(worldQueues)
        private val npcProcess = NpcQueueProcessor(events, StandardNpcAccessLauncher(StandardNpcAccessContextFactory(
            random, npcModifier, StandardNpcHitProcessor(players, events, emptySet(), emptySet()),
        )))
        private val playerProcess = PlayerQueueProcessor(events, ProtectedAccessLauncher(
            mockk<ProtectedAccessContextFactory>().also { every { it.create() } returns context },
        ))
        val spells = Guice.createInjector(MagicSpellModule()).getInstance(MagicSpellRegistry::class.java)
        val spell = checkNotNull(spells.getObjSpell(item(spellSymbol)))
        val attacks = SpellAttackRegistry()
        val compact = CompactRuneRepository()
        private val fake = FakeRuneRepository()
        private val combo = ComboRuneRepository()
        private val unlimited = UnlimitedRuneRepository()
        private val staff = StaffSubstituteRepository()
        private val substitutes = RuneSubstituteRepository()
        val legacyRunes = MagicRuneManager(fake, combo, compact, unlimited, staff, substitutes, questRequirements)
        val runes = MagicRuneManager(fake, combo, compact, unlimited, staff, substitutes, questRequirements, policy)
        val npcImpacts = mutableListOf<Hit>()
        val playerImpacts = mutableListOf<Hit>()
        var skulls = 0
        private val playerInteractions = PlayerTInteractions(events)
        private val packetHandler = OpPlayerTHandler(events, players, playerInteractions)

        init {
            VarPlayerIntMapSetter.set(caster, "varbit.spellbook", (spell.spellbook ?: Spellbook.Standard).varValue)
            VarPlayerIntMapSetter.set(caster, "varbit.skull_prevent_enabled", 0)
            for (script in listOf(
                FakeRuneScript(fake), ComboRuneScript(combo), CompactRuneScript(compact),
                UnlimitedRuneScript(unlimited), StaffSubstituteScript(staff), RuneSubstituteScript(substitutes),
                AiSpawnScript(), NpcHitScript(), PlayerHitScript(),
            )) with(script) { scripts.startup() }
            events.publish(NpcStateEvents.Create(primary))
            events.subscribeKeyed(NpcHitEvents.Impact::class.java, primary.id.toLong()) { npcImpacts += hit }
            events.subscribeUnbound(PlayerHitEvents.Impact::class.java) { playerImpacts += hit }
            every { world.projAnim(caster, primary, any<SpotanimType>(), any<String>()) } answers {
                ProjAnim.fromBoundsToNpc(caster.bounds(), primary, thirdArg<SpotanimType>().id, arg<String>(3))
            }
            every { world.projAnim(caster, target, any<SpotanimType>(), any<String>()) } answers {
                ProjAnim.fromBoundsToPlayer(caster.bounds(), target, thirdArg<SpotanimType>().id, arg<String>(3))
            }
            val spellManager = SpellAttackManager(manager, runes)
            with(StandardControlSpells(runes, random, worldQueues, state)) {
                SpellAttackRepository(attacks).register(spellManager)
            }
            with(ControlSpellStateScript(state)) { scripts.startup() }
            with(FreezeTimerScript()) { scripts.startup() }

            // These production classes are Kotlin-internal in combat-scripts. Reflection is only
            // constructor composition across modules; actual packets/events execute their code.
            val combat = construct("org.rsmod.api.combat.PvPCombat", WeaponSpeeds(styles),
                mockk<SpecialAttackRegistry>(), mockk<SpecialAttackEnergy>(), WeaponRegistry(), manager,
                mockk<RangedAmmoManager>(), attacks,
                linkedSetOf(wildernessRules, org.rsmod.api.death.PvPSkullHook { _, _ -> skulls++ }), emptySet<Any>())
            val script = construct("org.rsmod.api.combat.scripts.PvPCombatScript", styles, types, combat,
                spells, runes, mockk<AutocastWeapons>(), validators) as PluginScript
            with(script) { scripts.startup() }
        }

        fun supplySack(symbol: String, count: Int = 1) { caster.inv[SACK_SLOT] = InvObj(symbol, count) }

        fun supplyRunes() {
            for ((slot, requirement) in spell.objReqs.withIndex()) {
                assertNull(requirement.wornSlot)
                caster.inv[slot] = InvObj(requirement.obj, requirement.count)
            }
        }

        fun castNpc() {
            val handler = checkNotNull(attacks[spell.obj.internalName])
            runImmediately { with(handler) { access.attack(primary, CombatAttack.Spell(null, spell, false)) } }
        }

        fun castPlayerPacket() {
            assertTrue(spell.component.hasEvent(IfEvent.TgtPlayer))
            val side = ServerCacheManager.fromComponent("component.toplevel_osrs_stretch:side6")
            caster.ui.overlays[Component(side.packed)] = UserInterface(ServerCacheManager.fromInterface(spell.component.packed))
            packetHandler.consume(caster, OpPlayerT(target.slotId, false, CombinedId(spell.component.packed), -1, -1))
            val interaction = caster.interaction as? InteractionPlayerT ?: error("Native packet did not select its target")
            val event = checkNotNull(playerInteractions.apTrigger(interaction.target, interaction.component, interaction.comsub, interaction.objType))
            runImmediately { assertTrue(events.publish(access, event)) }
        }

        fun npcHits(): List<Hit> = buildList {
            val iterator = primary.queueList.iterator() ?: return@buildList
            while (iterator.hasNext()) { val queued = iterator.next(); if (queued.id == "queue.hit".asRSCM()) add(queued.args as Hit) }
            iterator.cleanUp()
        }

        fun playerHits(): List<Hit> = buildList {
            val iterator = target.queueList.iterator() ?: return@buildList
            while (iterator.hasNext()) { val queued = iterator.next(); if (queued.id == "queue.hit".asRSCM()) add(queued.args as Hit) }
            iterator.cleanUp()
        }

        fun xp() = XP_STATS.associateWith { caster.statMap.getFineXP(it) - beforeXp.getValue(it) }

        fun castPlayerDirect() {
            val handler = checkNotNull(attacks[checkNotNull(dev.openrune.rscm.RSCM.getReverseMapping(dev.openrune.rscm.RSCMType.OBJ, spell.obj.id))])
            runImmediately { with(handler) { access.attack(target, CombatAttack.Spell(null, spell, false)) } }
        }

        fun advance(ticks: Int = 1) {
            repeat(ticks) {
                caster.currentMapClock++
                target.currentMapClock = caster.currentMapClock
                primary.currentMapClock = caster.currentMapClock
                clock.cycle = caster.currentMapClock
                events.publish(GameLifecycle.StartCycle)
                worldProcess.process()
                npcProcess.process(primary)
                playerProcess.process(target)
                timers.process(target)
            }
        }

        fun tickImpacts() = advance(10)
        private val clock = MapClock(100)
        private val timers = PlayerTimerProcessor(clock, events, ProtectedAccessLauncher(
            mockk<ProtectedAccessContextFactory>().also { every { it.create() } returns context },
        ))
    }

    private class CastRandom : GameRandom {
        var splash = false
        var accuracyDraws = 0
        val damageRanges = mutableListOf<IntRange>()
        override fun of(maxExclusive: Int): Int = when (maxExclusive) {
            10000 -> { accuracyDraws++; if (splash) maxExclusive - 1 else 0 }
            4 -> 0 // Native male impact sound.
            100 -> 99 // Native NPC freeze-resistance roll; effect fidelity is tested separately.
            else -> error("Unexpected RNG bound $maxExclusive")
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            damageRanges += minInclusive..maxInclusive
            return maxInclusive
        }
        override fun randomDouble(): Double = error("Unexpected floating RNG")
    }

    private class SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private const val BIND = "obj.20_bind"
        private const val SNARE = "obj.50_snare"
        private const val ENTANGLE = "obj.79_entangle"
        private const val TELEBLOCK = "obj.85_teleport_block"
        private const val TARGET_TELEPORT = "obj.placeholder_caveorb4dot"
        private const val SACK_SLOT = 27
        private val WILD = CoordGrid(3200, 3554)
        private val SAFE = CoordGrid(3222, 3218)
        private val XP_STATS = listOf("stat.magic", "stat.hitpoints")
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
            slotId = slot
            uuid = slot.toLong()
            worldType = WorldType.MAIN
            assignUid()
            client = SilentClient()
            this.coords = coords
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

        private fun item(symbol: String): ItemServerType = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))
        private fun construct(className: String, vararg arguments: Any): Any = Class.forName(className).constructors.single { it.parameterCount == arguments.size }.newInstance(*arguments)
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
