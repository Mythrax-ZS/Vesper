package org.rsmod.content.skills.magic.spell.attacks.ancient

import com.google.inject.Guice
import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.map.area.MapAreaDecoder
import dev.openrune.map.area.MapAreaDefinitions
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.rsmod.annotations.InternalApi
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.magic.MagicSpell
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
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
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
import org.rsmod.api.npc.access.StandardNpcAccessContextFactory
import org.rsmod.api.npc.access.StandardNpcAccessLauncher
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.processor.StandardNpcHitProcessor
import org.rsmod.api.npc.plugin.AiSpawnScript
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.hit.modifier.StandardPlayerHitModifier
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContext
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
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.setCanPvp
import org.rsmod.content.areas.wilderness.WildernessAreaScript.Companion.setWildernessOptOut
import org.rsmod.content.areas.wilderness.WildernessPvPHook
import org.rsmod.content.areas.wilderness.WildernessPvPPermission
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.game.region.RegionListLarge
import org.rsmod.game.region.RegionListSmall
import org.rsmod.game.region.RegionListWorldEntity
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/** Registered spell, real runes/formulas/permission and queue processors; candidate discovery and clear line of sight are controlled. */
@OptIn(InternalApi::class)
@Isolated("Loads native cache and temporarily installs inventory transaction services")
class AncientPvPAuthorizationTest {
    @Test
    fun `ordinary multiway player secondary is denied despite legacy Pass while the NPC cast still lands`() {
        val baseline = Fixture(SAFE_MULTIWAY, secondaryCandidate = false)
        baseline.cast()
        val f = Fixture(SAFE_MULTIWAY)
        f.caster.setCanPvp(true)
        f.secondary.setCanPvp(true)
        f.cast()

        assertEquals(1, f.npcHits().size)
        assertTrue(f.playerHits().isEmpty())
        assertEquals(baseline.xp(), f.xp(), "A denied secondary must not contribute damage XP")
        assertEquals(1, f.random.accuracyDraws)
        assertEquals(listOf(0..18), f.random.damageRanges)
        f.assertPaidOneCast()
        f.assertBeforeImpact()

        f.tickImpacts()
        f.assertPrimaryLanded()
        assertEquals(99, f.secondary.hitpoints)
        assertEquals(99, f.secondary.stat("stat.attack"))
        assertTrue(f.playerImpacts.isEmpty())
        assertEquals(baseline.xp(), f.xp(), "Impact processing must not award the denied player's XP")
    }

    @Test
    fun `packed Wilderness permission admits a real secondary hit and deferred Shadow drain for one rune payment`() {
        val baseline = Fixture(WILDERNESS_MULTIWAY, secondaryCandidate = false)
        baseline.cast()
        val f = Fixture(WILDERNESS_MULTIWAY)
        assertEquals(PvPAttackValidateResult.Pass, f.authorization.validate(f.caster, f.secondary))
        f.cast()

        assertEquals(18, f.playerHits().single().damage)
        assertEquals(HitType.Magic, f.playerHits().single().type)
        assertTrue(f.playerHits().single().isSecondaryObj(f.spell.obj))
        // Preserve the existing level-126 PvP XP multiplier (1.125), independently of admission.
        assertEquals(baseline.xp().getValue("stat.magic") + 405, f.xp().getValue("stat.magic"))
        assertEquals(baseline.xp().getValue("stat.hitpoints") + 269, f.xp().getValue("stat.hitpoints"))
        assertEquals(2, f.random.accuracyDraws)
        assertEquals(listOf(0..18, 0..18), f.random.damageRanges)
        f.assertPaidOneCast()
        f.assertBeforeImpact()

        val launchXp = f.xp()
        f.tickImpacts()
        f.assertPrimaryLanded()
        assertEquals(81, f.secondary.hitpoints)
        assertEquals(90, f.secondary.stat("stat.attack"))
        assertEquals(1, f.playerImpacts.size)
        assertEquals(launchXp, f.xp(), "No second damage or cast XP award on impact")
    }

    @Test
    fun `existing veto mode isolation opt out and Wilderness level restrictions reject only the secondary`() {
        val baseline = Fixture(WILDERNESS_MULTIWAY, secondaryCandidate = false)
        baseline.cast()
        val variants = listOf<(Fixture) -> Unit>(
            { it.denyLegacy = true },
            { it.secondary.worldType = WorldType.RAGING_ECHOES_LEAGUE },
            { it.secondary.setWildernessOptOut(true) },
            { it.secondary.appearance.combatLevel = 3 },
            { VarPlayerIntMapSetter.set(it.caster, "varbit.skull_prevent_enabled", 1) },
        )
        for ((index, configure) in variants.withIndex()) {
            val f = Fixture(WILDERNESS_MULTIWAY)
            configure(f)
            f.cast()
            assertTrue(f.playerHits().isEmpty(), "secondary veto variant=$index")
            assertEquals(baseline.xp(), f.xp(), "secondary XP variant=$index")
            f.assertPaidOneCast()
            f.tickImpacts()
            f.assertPrimaryLanded()
            assertEquals(99, f.secondary.hitpoints, "secondary HP variant=$index")
            assertEquals(99, f.secondary.stat("stat.attack"), "secondary effect variant=$index")
        }
    }

    @Test
    fun `authorized launch remains queued after the secondary moves into a safe area`() {
        val f = Fixture(WILDERNESS_MULTIWAY)
        f.cast()
        assertEquals(1, f.playerHits().size)
        f.secondary.coords = SAFE_MULTIWAY
        assertTrue(f.authorization.validate(f.caster, f.secondary) is PvPAttackValidateResult.Deny)
        f.assertBeforeImpact()

        f.tickImpacts()
        assertEquals(81, f.secondary.hitpoints)
        assertEquals(90, f.secondary.stat("stat.attack"))
        assertEquals(1, f.playerImpacts.size, "Admission checks must not become a blanket impact cancellation")
        f.assertPrimaryLanded()
    }

    @Test
    fun `empty native rune inventory prevents repeated NPC and secondary launches or XP`() {
        val f = Fixture(WILDERNESS_MULTIWAY)
        f.cast()
        f.assertPaidOneCast()
        val xp = f.xp()
        val npcHits = f.npcHits()
        val playerHits = f.playerHits()
        val effects = f.worldQueues.size
        f.cast()
        assertEquals(xp, f.xp())
        assertEquals(npcHits, f.npcHits())
        assertEquals(playerHits, f.playerHits())
        assertEquals(effects, f.worldQueues.size)
        assertEquals(2, f.random.accuracyDraws)
        f.assertPaidOneCast()
    }

    private class Fixture(centre: CoordGrid, secondaryCandidate: Boolean = true) {
        val caster = newPlayer(1, centre.translate(-3, 0))
        val secondary = newPlayer(2, centre.translate(1, 0))
        val primary = Npc("npc.king_dragon", centre).apply {
            slotId = 1
            assignUid()
            currentMapClock = 100
        }
        private val originalNpcHp = primary.hitpoints
        private val originalNpcAttack = primary.attackLvl
        private val beforeXp = XP_STATS.associateWith { caster.statMap.getFineXP(it) }
        val random = CastRandom()
        private val events = EventBus()
        private val scripts = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
        private val players = PlayerList().also { it[caster.slotId] = caster; it[secondary.slotId] = secondary }
        private val npcs = NpcList().also { it[primary.slotId] = primary }
        private val collision = CollisionFlagMap()
        private val locs = LocZoneStorage()
        private val regions = RegionRegistry(
            RegionListSmall(), RegionListLarge(), RegionListWorldEntity(),
            LocRegistryNormal(ZoneUpdateMap(), collision, locs), collision, locs,
            mockk(), mockk(), ZonePlayerActivityBitSet(),
        )
        private val areas = AreaChecker(regions, areaIndex)
        val authorization = PvPContextAuthorizationHook(setOf(WildernessPvPPermission(areas, regions)))
        private val wildernessRules = WildernessPvPHook(areas)
        var denyLegacy = false
        private val legacy = PvPAttackValidateHook { _, _ ->
            if (denyLegacy) PvPAttackValidateResult.Deny("Test match veto") else PvPAttackValidateResult.Pass
        }
        private val styles = AttackStyles().also { with(WeaponAttackStylesScript(it)) { scripts.startup() } }
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
        private val manager = PlayerAttackManager(
            random, events, world, accuracy, maxima, npcModifier, playerModifier,
            mockk(), mockk(), mockk(), mockk(), emptySet(),
        )
        private val protectedContext = mockk<ProtectedAccessContext>().also {
            every { it.areaChecker } returns areas
            every { it.eventBus } returns events
            every { it.random } returns random
            every { it.playerList } returns players
            every { it.npcList } returns npcs
            every { it.hitModifier } returns playerModifier
        }
        private val access = ProtectedAccess(caster, GameCoroutine("ancient-pvp-admission"), protectedContext)
        val worldQueues = WorldQueueList()
        private val worldProcess = WorldQueueListProcess(worldQueues)
        private val npcProcess = NpcQueueProcessor(
            events,
            StandardNpcAccessLauncher(StandardNpcAccessContextFactory(
                random, npcModifier, StandardNpcHitProcessor(players, events, emptySet(), emptySet()),
            )),
        )
        private val playerProcess = PlayerQueueProcessor(events, ProtectedAccessLauncher(
            mockk<ProtectedAccessContextFactory>().also { every { it.create() } returns protectedContext },
        ))
        private val registry = SpellAttackRegistry()
        val spell: MagicSpell = checkNotNull(
            Guice.createInjector(MagicSpellModule()).getInstance(MagicSpellRegistry::class.java).getObjSpell(item(SPELL)),
        )
        val npcImpacts = mutableListOf<Hit>()
        val playerImpacts = mutableListOf<Hit>()

        init {
            assertTrue(areas.inArea("area.multiway", primary.coords), "Primary must exercise native multiway targeting")
            assertTrue(areas.inArea("area.multiway", secondary.coords), "Secondary must reach authorization, not fail multiway")
            assertEquals(Spellbook.Ancients, spell.spellbook)
            // AncientSpells supplies its attack-specific maximum to the real formula; the
            // cache's generic spell descriptor has a placeholder maximum for this spell.
            assertEquals(SPELL, AncientSpell.ShadowBurst.obj)
            assertEquals(18, AncientSpell.ShadowBurst.maxHit)
            assertTrue(spell.objReqs.isNotEmpty())
            assertEquals(spell.objReqs.size, spell.objReqs.map { it.obj.id }.distinct().size)
            for ((slot, requirement) in spell.objReqs.withIndex()) {
                assertEquals(null, requirement.wornSlot)
                caster.inv[slot] = InvObj(requirement.obj, requirement.count)
            }
            VarPlayerIntMapSetter.set(caster, "varbit.spellbook", Spellbook.Ancients.varValue)
            with(AiSpawnScript()) { scripts.startup() }
            with(NpcHitScript()) { scripts.startup() }
            with(PlayerHitScript()) { scripts.startup() }
            events.publish(NpcStateEvents.Create(primary))
            assertTrue(primary.combatXpMultiplier > 0)
            events.subscribeKeyed(NpcHitEvents.Impact::class.java, primary.id.toLong()) { npcImpacts += hit }
            events.subscribeUnbound(PlayerHitEvents.Impact::class.java) { playerImpacts += hit }

            // Native geometry/rules/formulas are real. Candidate discovery and clear line of sight
            // are controlled so safe-area candidates must be rejected by authorization itself.
            val hunt = mockk<Hunt>()
            every { hunt.findNpcs(any(), any(), any()) } answers { emptySequence() }
            every { hunt.findPlayers(any(), any(), any()) } answers {
                if (secondaryCandidate) sequenceOf(secondary) else emptySequence()
            }
            val rays = mockk<RayCastValidator>()
            every { rays.hasLineOfSight(any(), any(), any(), any(), any(), any(), any()) } returns true
            with(AncientSpells(hunt, rays, areas, world, worldQueues, random, emptySet(), linkedSetOf(legacy, authorization, wildernessRules))) {
                SpellAttackRepository(registry).register(SpellAttackManager(manager, runeManager(scripts)))
            }
        }

        fun cast() {
            val attack = CombatAttack.Spell(null, spell, defensive = false)
            val handler = checkNotNull(registry[SPELL])
            runImmediately { with(handler) { access.attack(primary, attack) } }
        }

        fun npcHits(): List<Hit> = buildList {
            val iterator = primary.queueList.iterator() ?: return@buildList
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.id == "queue.hit".asRSCM()) add(queued.args as Hit)
            }
            iterator.cleanUp()
        }

        fun playerHits(): List<Hit> = buildList {
            val iterator = secondary.queueList.iterator() ?: return@buildList
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.id == "queue.hit".asRSCM()) add(queued.args as Hit)
            }
            iterator.cleanUp()
        }

        fun xp(): Map<String, Int> = XP_STATS.associateWith { caster.statMap.getFineXP(it) - beforeXp.getValue(it) }

        fun assertPaidOneCast() {
            for (slot in spell.objReqs.indices) assertEquals(null, caster.inv[slot], "Native rune requirement slot=$slot")
            val multiplier = primary.combatXpMultiplier / 1000.0
            assertTrue(xp().getValue("stat.magic") >= (spell.castXp * 10).toInt() + (18 * 2.0 * multiplier * 10).toInt())
        }

        fun assertBeforeImpact() {
            assertEquals(originalNpcHp, primary.hitpoints)
            assertEquals(originalNpcAttack, primary.attackLvl)
            assertEquals(99, secondary.hitpoints)
            assertEquals(99, secondary.stat("stat.attack"))
            assertTrue(npcImpacts.isEmpty())
            assertTrue(playerImpacts.isEmpty())
        }

        fun tickImpacts() {
            repeat(10) {
                caster.currentMapClock++
                secondary.currentMapClock = caster.currentMapClock
                primary.currentMapClock = caster.currentMapClock
                worldProcess.process()
                npcProcess.process(primary)
                playerProcess.process(secondary)
            }
            assertTrue(worldQueues.isEmpty)
        }

        fun assertPrimaryLanded() {
            assertEquals(1, npcImpacts.size)
            assertEquals(18, npcImpacts.single().damage)
            assertEquals(originalNpcHp - 18, primary.hitpoints)
            assertEquals(originalNpcAttack - originalNpcAttack / 10, primary.attackLvl)
        }
    }

    private class CastRandom : GameRandom {
        var accuracyDraws = 0
        val damageRanges = mutableListOf<IntRange>()
        override fun of(maxExclusive: Int): Int = when (maxExclusive) {
            10000 -> { accuracyDraws++; 0 }
            4 -> 0 // Real player impact chooses one of the four native male body hit sounds.
            else -> error("Unexpected RNG bound $maxExclusive")
        }
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            assertEquals(0..18, minInclusive..maxInclusive)
            damageRanges += minInclusive..maxInclusive
            return maxInclusive
        }
        override fun randomDouble(): Double = error("Unexpected floating-point RNG")
    }

    companion object {
        private const val SPELL = "obj.64_shadow_burst"
        private val SAFE_MULTIWAY = CoordGrid(3200, 3524)
        private val WILDERNESS_MULTIWAY = CoordGrid(3200, 3554)
        private val XP_STATS = listOf("stat.magic", "stat.hitpoints")
        private lateinit var areaIndex: AreaIndex
        private val restorations = mutableListOf<() -> Unit>()

        @JvmStatic
        @BeforeAll
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
            } finally {
                cache.close()
            }
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

        @JvmStatic
        @AfterAll
        fun restoreGlobals() {
            restorations.asReversed().forEach { it() }
            restorations.clear()
        }

        private fun newPlayer(slot: Int, coords: CoordGrid): Player = Player().apply {
            slotId = slot
            uuid = slot.toLong()
            worldType = WorldType.MAIN
            assignUid()
            this.coords = coords
            currentMapClock = 100
            processedMapClock = 100
            appearance.combatLevel = 126
            inv = Inventory.create("inv.inv")
            worn = Inventory.create("inv.worn")
            for (stat in listOf("stat.attack", "stat.strength", "stat.defence", "stat.ranged", "stat.magic", "stat.hitpoints")) {
                statMap.setBaseLevel(stat, 99)
                statMap.setCurrentLevel(stat, 99)
                statMap.setXP(stat, PlayerSkillXPTable.getXPFromLevel(99))
            }
        }

        private fun item(symbol: String): ItemServerType = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))

        private fun runeManager(context: ScriptContext): MagicRuneManager {
            val fake = FakeRuneRepository()
            val combo = ComboRuneRepository()
            val compact = CompactRuneRepository()
            val unlimited = UnlimitedRuneRepository()
            val staff = StaffSubstituteRepository()
            val substitutes = RuneSubstituteRepository()
            for (script in listOf(
                FakeRuneScript(fake), ComboRuneScript(combo), CompactRuneScript(compact),
                UnlimitedRuneScript(unlimited), StaffSubstituteScript(staff), RuneSubstituteScript(substitutes),
            )) {
                with(script) { context.startup() }
            }
            return MagicRuneManager(fake, combo, compact, unlimited, staff, substitutes, emptySet())
        }

        private fun runImmediately(action: suspend () -> Unit) {
            var result: Result<Unit>? = null
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result) { "Ancient cast unexpectedly suspended" }.getOrThrow()
        }
    }
}
