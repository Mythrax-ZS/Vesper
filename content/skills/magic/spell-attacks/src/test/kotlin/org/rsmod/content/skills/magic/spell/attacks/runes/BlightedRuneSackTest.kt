package org.rsmod.content.skills.magic.spell.attacks.runes

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
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.magic.MagicSpell
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
import org.rsmod.api.game.process.npc.NpcQueueProcessor
import org.rsmod.api.game.process.player.PlayerQueueProcessor
import org.rsmod.api.game.process.world.WorldQueueListProcess
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.hunt.Hunt
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
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.specials.SpecialAttackRegistry
import org.rsmod.api.specials.energy.SpecialAttackEnergy
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackRegistry
import org.rsmod.api.spells.attack.SpellAttackRepository
import org.rsmod.api.spells.autocast.AutocastWeapons
import org.rsmod.api.spells.runes.BlightedRunePackPolicy
import org.rsmod.api.spells.runes.MagicRunes
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
import org.rsmod.api.table.QuestRow
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.content.areas.wilderness.WildernessBlightedRunePackPermission
import org.rsmod.content.areas.wilderness.WildernessPvPHook
import org.rsmod.content.areas.wilderness.WildernessPvPPermission
import org.rsmod.content.skills.magic.spell.attacks.ancient.AncientSpells
import org.rsmod.content.skills.magic.spell.attacks.standard.ElementalSpells
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcStateEvents
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

/**
 * Native rune sources, registered spells, real inventory/XP/hit processing and PvP packet admission.
 * Candidate discovery and clear line of sight are controlled; walking is not simulated. Mapping
 * and payment coverage for Vengeance/Teleport to Target does not claim their spell runtimes exist.
 */
@OptIn(InternalApi::class)
@Isolated("Loads native cache and temporarily installs the inventory transaction service")
class BlightedRuneSackTest {
    @Test
    fun `all nineteen native spell mappings consume exactly one of the five current sacks`() {
        assertEquals(19, MAPPINGS.sumOf { it.second.size })
        assertEquals(5, MAPPINGS.map { it.first }.distinct().size)
        for ((sack, spells) in MAPPINGS) {
            for (symbol in spells) {
                val f = Fixture(symbol)
                f.supplySack(sack, 2)
                val before = f.caster.inv.objs.toList()
                val selected = MagicRunes.validateRunePack(f.caster, f.spell.obj, false, true)
                assertEquals(MagicRunes.Validation.Valid.HasEnough(MagicRunes.Source.InvSource(sack, SACK_SLOT, 1)), selected, symbol)
                assertEquals(before, f.caster.inv.objs.toList(), "Source validation must be read-only")
                assertEquals(MagicRuneManager.CastResult.Success.Consumed(false), f.runes.delReqs(f.caster, f.spell), symbol)
                assertEquals(1, f.caster.inv.count(sack))
                assertEquals(before.take(SACK_SLOT), f.caster.inv.objs.take(SACK_SLOT))
                assertEquals(MagicRuneManager.CastResult.Success.Consumed(false), f.runes.delReqs(f.caster, f.spell))
                assertEquals(0, f.caster.inv.count(sack))
                assertEquals(MagicRuneManager.CastResult.Failure.MissingObjRequirements, f.runes.delReqs(f.caster, f.spell))
                assertEquals(0, f.xp().values.sum(), "Resource API alone must not award cast or damage XP")
            }
        }
    }

    @Test
    fun `sacks retain ordinary Magic level and nonnull spellbook restrictions`() {
        for ((sack, spells) in MAPPINGS) {
            for (symbol in spells.filterNot { it == TARGET_TELEPORT }) {
                val f = Fixture(symbol)
                f.supplySack(sack)
                val before = f.caster.inv.objs.toList()
                f.caster.statMap.setCurrentLevel("stat.magic", (f.spell.levelReq - 1).toByte())
                assertEquals(MagicRuneManager.CastResult.Failure.MissingLevelRequirement, f.runes.attemptCast(f.caster, f.spell), symbol)
                f.caster.statMap.setCurrentLevel("stat.magic", 99)
                val wrong = Spellbook.entries.first { it != f.spell.spellbook }
                VarPlayerIntMapSetter.set(f.caster, "varbit.spellbook", wrong.varValue)
                assertEquals(MagicRuneManager.CastResult.Failure.IncorrectSpellbook, f.runes.attemptCast(f.caster, f.spell), symbol)
                assertEquals(before, f.caster.inv.objs.toList())
                assertEquals(0, f.xp().values.sum())
            }
        }
    }

    @Test
    fun `native any-book descriptor permits resource access from all four books without implementing target teleport`() {
        for (book in Spellbook.entries) {
            val f = Fixture(TARGET_TELEPORT)
            assertNull(f.spell.spellbook)
            assertNull(f.attacks[TARGET_TELEPORT], "This resource test must not imply a teleport handler")
            VarPlayerIntMapSetter.set(f.caster, "varbit.spellbook", book.varValue)
            f.supplySack("obj.blighted_sack_teleblock")
            assertTrue(f.runes.canCastSpell(f.caster, f.spell), book.name)
            assertEquals(MagicRuneManager.CastResult.Success.Consumed(false), f.runes.attemptCast(f.caster, f.spell))
            assertNull(f.caster.inv[SACK_SLOT])
            assertEquals(0, f.xp().values.sum())
            assertTrue(f.npcHits().isEmpty())
            assertTrue(f.playerHits().isEmpty())
        }
    }

    @Test
    fun `a mapped rune source does not bypass the shared quest requirement contract`() {
        val checked = mutableListOf<String>()
        val f = Fixture(FIRE_SURGE, questRequirements = setOf(SpellQuestRequirement { _, quest -> checked += quest; false }))
        f.supplySack("obj.blighted_sack_surge")
        // A native quest key is deliberately attached to a mapped descriptor to exercise the
        // shared API contract; Fire Surge itself is not claimed to require this quest.
        val nativeQuest = "quest_deserttreasure"
        assertTrue(QuestRow.getRow("dbrow.$nativeQuest").displayname.isNotBlank())
        val guarded = f.spell.copy(questReq = nativeQuest)
        val before = f.caster.inv.objs.toList()
        assertEquals(MagicRuneManager.CastResult.Failure.MissingQuestRequirement, f.runes.attemptCast(f.caster, guarded))
        assertEquals(listOf(nativeQuest), checked)
        assertEquals(before, f.caster.inv.objs.toList())
    }

    @Test
    fun `a rune replacement preserves equipment and non-rune item requirements atomically`() {
        val f = Fixture(FIRE_SURGE)
        val staff = item("obj.staff_of_fire")
        val banana = item("obj.banana")
        val guarded = f.spell.copy(objReqs = f.spell.objReqs + listOf(
            MagicSpell.ObjRequirement(staff, 1, staff.wearpos1),
            MagicSpell.ObjRequirement(banana, 1, null),
        ))
        // Synthetic extra requirements test the shared substitution contract, not Fire Surge's
        // native cost. Missing either must preserve the sack and every other available source.
        f.supplySack("obj.blighted_sack_surge")
        assertEquals(MagicRuneManager.CastResult.Failure.MissingObjRequirements, f.runes.attemptCast(f.caster, guarded))
        assertEquals(1, f.caster.inv.count("obj.blighted_sack_surge"))
        f.caster.worn[staff.wearpos1] = InvObj(staff)
        assertEquals(MagicRuneManager.CastResult.Failure.MissingObjRequirements, f.runes.attemptCast(f.caster, guarded))
        assertEquals(1, f.caster.inv.count("obj.blighted_sack_surge"))
        f.caster.inv[2] = InvObj(banana)
        assertEquals(MagicRuneManager.CastResult.Success.Consumed(false), f.runes.attemptCast(f.caster, guarded))
        assertNull(f.caster.inv[SACK_SLOT])
        assertNull(f.caster.inv[2])
        assertEquals(InvObj(staff), f.caster.worn[staff.wearpos1])
    }

    @Test
    fun `real current-area policy rejects a forged client flag and falls back to ordinary runes`() {
        val f = Fixture(FIRE_SURGE, SAFE)
        f.supplySack("obj.blighted_sack_surge")
        VarPlayerIntMapSetter.set(f.caster, "varbit.blighted_items_allowed", 1)
        assertFalse(f.policy.permits(f.caster))
        val before = f.caster.inv.objs.toList()
        assertFalse(f.runes.canCastSpell(f.caster, f.spell))
        assertEquals(MagicRuneManager.CastResult.Failure.MissingObjRequirements, f.runes.attemptCast(f.caster, f.spell))
        assertEquals(before, f.caster.inv.objs.toList())
        f.supplyRunes()
        assertEquals(MagicRuneManager.CastResult.Success.Consumed(false), f.runes.attemptCast(f.caster, f.spell))
        assertEquals(1, f.caster.inv.count("obj.blighted_sack_surge"))
        assertTrue(f.spell.objReqs.all { f.caster.inv.count(it.obj.internalName) == 0 })
    }

    @Test
    fun `cast revalidation handles moving out of eligibility and losing or replacing a preflight sack`() {
        for (mutation in listOf<(Fixture) -> Unit>(
            { it.caster.coords = SAFE },
            { it.caster.inv[SACK_SLOT] = null },
            { it.caster.inv[SACK_SLOT] = InvObj("obj.bronze_dagger") },
        )) {
            val f = Fixture(FIRE_SURGE)
            f.supplySack("obj.blighted_sack_surge")
            assertTrue(f.runes.canCastSpell(f.caster, f.spell))
            mutation(f)
            val before = f.caster.inv.objs.toList()
            assertEquals(MagicRuneManager.CastResult.Failure.MissingObjRequirements, f.runes.attemptCast(f.caster, f.spell))
            assertEquals(before, f.caster.inv.objs.toList())
            assertEquals(0, f.xp().values.sum())
        }
        val moved = Fixture(FIRE_SURGE)
        moved.supplySack("obj.blighted_sack_surge")
        assertTrue(moved.runes.canCastSpell(moved.caster, moved.spell))
        moved.caster.inv[8] = moved.caster.inv[SACK_SLOT]
        moved.caster.inv[SACK_SLOT] = null
        assertEquals(MagicRuneManager.CastResult.Success.Consumed(false), moved.runes.attemptCast(moved.caster, moved.spell))
        assertNull(moved.caster.inv[8], "A current source is reselected after inventory movement")
    }

    @Test
    fun `current sack-first compatibility branch preserves ordinary runes and never marks Sunfire consumed`() {
        // Exact server-side sack/rune priority is unresolved. This pins the explicitly retained
        // local selection seam, not an independently verified OSRS precedence rule.
        val f = Fixture(FIRE_SURGE)
        f.supplySack("obj.blighted_sack_surge")
        f.supplyRunes()
        f.caster.inv[12] = InvObj("obj.sunfirerune", 100)
        val before = f.caster.inv.objs.take(SACK_SLOT)
        assertEquals(MagicRuneManager.CastResult.Success.Consumed(false), f.runes.attemptCast(f.caster, f.spell))
        assertEquals(before, f.caster.inv.objs.take(SACK_SLOT))
        assertNull(f.caster.inv[SACK_SLOT])
    }

    @Test
    fun `unsupported spell or wrong sack leaves the normal rune pouch and unlimited staff path intact`() {
        val wrong = Fixture(FIRE_SURGE)
        wrong.supplySack("obj.blighted_sack_icebarrage")
        assertTrue(wrong.policy.permits(wrong.caster))
        assertFalse(wrong.runes.canCastSpell(wrong.caster, wrong.spell), "An eligible location cannot make an unrelated sack cover this spell")
        wrong.supplyRunes()
        assertTrue(wrong.runes.attemptCast(wrong.caster, wrong.spell) is MagicRuneManager.CastResult.Success)
        assertEquals(1, wrong.caster.inv.count("obj.blighted_sack_icebarrage"))

        val f = Fixture(FIRE_SURGE, SAFE)
        f.supplySack("obj.blighted_sack_icebarrage")
        val staff = item("obj.staff_of_fire")
        f.caster.worn[staff.wearpos1] = InvObj(staff)
        f.caster.inv[0] = InvObj("obj.bh_rune_pouch")
        val required = f.spell.objReqs.filterNot { it.obj.isType("obj.firerune") }
        assertEquals(2, required.size)
        for ((index, requirement) in required.withIndex()) {
            VarPlayerIntMapSetter.set(f.caster, "varbit.rune_pouch_type_${index + 1}", checkNotNull(f.compact[requirement.obj]))
            VarPlayerIntMapSetter.set(f.caster, "varbit.rune_pouch_quantity_${index + 1}", requirement.count)
        }
        assertTrue(f.runes.canCastSpell(f.caster, f.spell))
        val pouchInventory = f.caster.inv.objs.toList()
        assertEquals(MagicRuneManager.CastResult.Success.Consumed(false), f.runes.attemptCast(f.caster, f.spell))
        assertEquals(pouchInventory, f.caster.inv.objs.toList(), "Pouch-only payment must not delete an inventory item")
        assertEquals(1, f.caster.inv.count("obj.blighted_sack_icebarrage"))
        assertEquals(InvObj(staff), f.caster.worn[staff.wearpos1])
        for (index in required.indices) assertEquals(0, f.caster.vars["varbit.rune_pouch_quantity_${index + 1}"])
        assertEquals(MagicRuneManager.CastResult.Failure.MissingObjRequirements, f.runes.attemptCast(f.caster, f.spell))
        assertEquals(pouchInventory, f.caster.inv.objs.toList(), "An exhausted pouch cannot pay a repeated cast")
        for (index in required.indices) assertEquals(0, f.caster.vars["varbit.rune_pouch_quantity_${index + 1}"])

        val unsupported = Fixture("obj.01_wind_strike")
        unsupported.supplySack("obj.blighted_sack_surge")
        assertNull(MagicRunes.validateRunePack(unsupported.caster, unsupported.spell.obj, false, true))
        assertFalse(unsupported.runes.canCastSpell(unsupported.caster, unsupported.spell))
        unsupported.supplyRunes()
        assertTrue(unsupported.runes.attemptCast(unsupported.caster, unsupported.spell) is MagicRuneManager.CastResult.Success)
        assertEquals(1, unsupported.caster.inv.count("obj.blighted_sack_surge"))
    }

    @Test
    fun `legacy seven-argument manager remains callable without inventing a permission provider`() {
        val f = Fixture(FIRE_SURGE)
        f.supplySack("obj.blighted_sack_surge")
        assertFalse(f.legacyRunes.canCastSpell(f.caster, f.spell))
        assertEquals(1, f.caster.inv.count("obj.blighted_sack_surge"))
        f.supplyRunes()
        assertTrue(f.legacyRunes.attemptCast(f.caster, f.spell) is MagicRuneManager.CastResult.Success)
        assertEquals(1, f.caster.inv.count("obj.blighted_sack_surge"))
    }

    @Test
    fun `registered Ice Wave and Surge casts hit NPCs and admitted PvP targets for one sack`() {
        for ((sack, symbols) in SUPPORTED_ATTACKS) {
            for (symbol in symbols) {
                for (pvp in listOf(false, true)) {
                    val f = Fixture(symbol)
                    f.supplySack(sack, 2)
                    if (pvp) f.castPlayerPacket() else f.castNpc()
                    val hits = if (pvp) f.playerHits() else f.npcHits()
                    assertEquals(1, hits.size, "$symbol pvp=$pvp")
                    assertTrue(hits.single().damage > 0)
                    assertFalse(hits.single().isMagicSplash)
                    assertTrue(hits.single().isSecondaryObj(f.spell.obj))
                    assertEquals(1, f.caster.inv.count(sack))
                    assertTrue(f.xp().getValue("stat.magic") >= (f.spell.castXp * 10).toInt())
                    val xp = f.xp()
                    f.tickImpacts()
                    assertEquals(1, if (pvp) f.playerImpacts.size else f.npcImpacts.size)
                    assertEquals(xp, f.xp(), "Impact must not repeat the XP award")
                    assertEquals(1, f.caster.inv.count(sack), "Impact must not charge a second sack")
                }
            }
        }
    }

    @Test
    fun `registered splashes still pay one sack and award cast XP without damage XP`() {
        for ((sack, symbols) in SUPPORTED_ATTACKS) {
            for (symbol in symbols) {
                for (pvp in listOf(false, true)) {
                    val f = Fixture(symbol)
                    f.random.splash = true
                    f.supplySack(sack)
                    if (pvp) f.castPlayerPacket() else f.castNpc()
                    val hit = (if (pvp) f.playerHits() else f.npcHits()).single()
                    assertTrue(hit.isMagicSplash, "$symbol pvp=$pvp")
                    assertEquals(0, hit.damage)
                    assertNull(f.caster.inv[SACK_SLOT])
                    assertEquals((f.spell.castXp * 10).toInt(), f.xp().getValue("stat.magic"))
                    assertEquals(0, f.xp().getValue("stat.hitpoints"))
                    assertTrue(f.random.damageRanges.isEmpty(), "Splash must not draw damage")
                    f.tickImpacts()
                    assertEquals(99, f.target.hitpoints)
                    assertEquals(f.initialNpcHp, f.primary.hitpoints)
                }
            }
        }
    }

    @Test
    fun `Ice barrage secondary targets share one paid cast and exhaustion cannot launch again`() {
        val f = Fixture("obj.94_ice_barrage", includeSecondary = true)
        f.supplySack("obj.blighted_sack_icebarrage")
        f.castNpc()
        assertEquals(1, f.npcHits().size)
        assertEquals(1, f.playerHits().size)
        assertEquals(2, f.random.accuracyDraws)
        assertNull(f.caster.inv[SACK_SLOT])
        val xp = f.xp()
        val effects = f.worldQueues.size
        f.castNpc()
        assertEquals(xp, f.xp())
        assertEquals(effects, f.worldQueues.size)
        assertEquals(1, f.npcHits().size)
        assertEquals(1, f.playerHits().size)
        assertEquals(2, f.random.accuracyDraws)
        f.tickImpacts()
        assertEquals(1, f.npcImpacts.size)
        assertEquals(1, f.playerImpacts.size)
        assertEquals(xp, f.xp())
    }

    @Test
    fun `actual PvP packet admission denies safe-town and legacy-veto casts without payment or effects`() {
        val cases = listOf(
            CoordGrid(3130, 3630) to false, // Real sack-eligible Ferox is still not a PvP combat grant.
            WILD to true,
        )
        for ((coords, veto) in cases) {
            val f = Fixture(FIRE_SURGE, coords)
            f.denyLegacy = veto
            f.supplySack("obj.blighted_sack_surge")
            assertTrue(f.runes.canCastSpell(f.caster, f.spell), "Denial must reach actual PvP admission")
            val before = f.caster.inv.objs.toList()
            val delay = f.caster.actionDelay
            f.castPlayerPacket()
            assertEquals(before, f.caster.inv.objs.toList())
            assertEquals(delay, f.caster.actionDelay)
            assertEquals(0, f.xp().values.sum())
            assertEquals(0, f.random.accuracyDraws)
            assertTrue(f.playerHits().isEmpty())
            assertTrue(f.worldQueues.isEmpty)
            assertEquals(0, f.skulls)
        }
    }

    @Test
    fun `a full inventory can spend its last sack without disturbing the remaining slots`() {
        val f = Fixture(FIRE_SURGE)
        for (slot in f.caster.inv.indices) f.caster.inv[slot] = InvObj("obj.bronze_dagger")
        f.supplySack("obj.blighted_sack_surge")
        val before = f.caster.inv.objs.take(SACK_SLOT)
        f.castNpc()
        assertEquals(before, f.caster.inv.objs.take(SACK_SLOT))
        assertNull(f.caster.inv[SACK_SLOT])
        assertEquals(1, f.caster.inv.freeSpace())
        assertEquals(1, f.npcHits().size)
    }

    private class Fixture(
        spellSymbol: String,
        coords: CoordGrid = WILD,
        includeSecondary: Boolean = false,
        questRequirements: Set<SpellQuestRequirement> = emptySet(),
    ) {
        val caster = newPlayer(1, coords)
        val target = newPlayer(2, coords.translate(1, 0))
        val primary = Npc("npc.king_dragon", coords.translate(1, 0)).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
        }
        val initialNpcHp = primary.hitpoints
        private val beforeXp = XP_STATS.associateWith(caster.statMap::getFineXP)
        val random = CastRandom()
        private val events = EventBus()
        private val scripts = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
        private val players = PlayerList().also { it[caster.slotId] = caster; it[target.slotId] = target }
        private val npcs = NpcList().also { it[primary.slotId] = primary }
        private val collision = CollisionFlagMap()
        private val locs = LocZoneStorage()
        private val regions = RegionRegistry(
            RegionListSmall(), RegionListLarge(), RegionListWorldEntity(),
            LocRegistryNormal(ZoneUpdateMap(), collision, locs), collision, locs,
            mockk(), mockk(), ZonePlayerActivityBitSet(),
        )
        val areas = AreaChecker(regions, areaIndex)
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
            val hunt = mockk<Hunt>()
            every { hunt.findNpcs(any(), any(), any()) } answers { emptySequence() }
            every { hunt.findPlayers(any(), any(), any()) } answers { if (includeSecondary) sequenceOf(target) else emptySequence() }
            val rays = mockk<RayCastValidator>()
            every { rays.hasLineOfSight(any(), any(), any(), any(), any(), any(), any()) } returns true
            val spellManager = SpellAttackManager(manager, runes)
            with(AncientSpells(hunt, rays, areas, world, worldQueues, random, emptySet(), validators)) {
                SpellAttackRepository(attacks).register(spellManager)
            }
            with(ElementalSpells()) { SpellAttackRepository(attacks).register(spellManager) }

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

        fun tickImpacts() {
            repeat(10) {
                caster.currentMapClock++
                target.currentMapClock = caster.currentMapClock
                primary.currentMapClock = caster.currentMapClock
                worldProcess.process()
                npcProcess.process(primary)
                playerProcess.process(target)
            }
            assertTrue(worldQueues.isEmpty)
        }
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
        private const val FIRE_SURGE = "obj.95_fire_surge"
        private const val TARGET_TELEPORT = "obj.placeholder_caveorb4dot"
        private const val SACK_SLOT = 27
        private val WILD = CoordGrid(3200, 3554)
        private val SAFE = CoordGrid(3222, 3218)
        private val XP_STATS = listOf("stat.magic", "stat.hitpoints")

        // Independent expected native client mapping from the pinned magic_spellbook_hasrunes
        // source receipt; production lookup must come from the generated server-only cache enum.
        private val MAPPINGS = listOf(
            "obj.blighted_sack_entangle" to listOf("obj.20_bind", "obj.50_snare", "obj.79_entangle"),
            "obj.blighted_sack_icebarrage" to listOf("obj.58_ice_rush", "obj.70_ice_burst", "obj.82_ice_blitz", "obj.94_ice_barrage"),
            "obj.blighted_sack_surge" to listOf("obj.62_wind_wave", "obj.65_water_wave", "obj.70_earth_wave", "obj.75_fire_wave", "obj.81_wind_surge", "obj.85_water_surge", "obj.90_earth_surge", FIRE_SURGE),
            "obj.blighted_sack_vengeance" to listOf("obj.93_vengeance_other", "obj.94_vengeance"),
            "obj.blighted_sack_teleblock" to listOf("obj.85_teleport_block", TARGET_TELEPORT),
        )
        private val SUPPORTED_ATTACKS = MAPPINGS.filter { it.first == "obj.blighted_sack_icebarrage" || it.first == "obj.blighted_sack_surge" }
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
