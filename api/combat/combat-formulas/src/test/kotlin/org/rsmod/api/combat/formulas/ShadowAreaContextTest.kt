package org.rsmod.api.combat.formulas

import com.google.inject.AbstractModule
import com.google.inject.Guice
import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.map.area.MapAreaDecoder
import dev.openrune.map.area.MapAreaDefinitions
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import io.mockk.mockk
import java.util.EnumSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.commons.styles.MagicAttackStyle
import org.rsmod.api.combat.formulas.accuracy.magic.PvNMagicAccuracy
import org.rsmod.api.combat.formulas.accuracy.magic.PvPMagicAccuracy
import org.rsmod.api.combat.formulas.attributes.CombatNpcAttributes
import org.rsmod.api.combat.formulas.attributes.CombatStaffAttributes
import org.rsmod.api.combat.formulas.attributes.collector.CombatMagicAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.CombatNpcAttributeCollector
import org.rsmod.api.combat.formulas.attributes.collector.DamageReductionAttributeCollector
import org.rsmod.api.combat.formulas.maxhit.magic.PvNMagicMaxHit
import org.rsmod.api.combat.formulas.maxhit.magic.PvPMagicMaxHit
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.loc.LocRegistryNormal
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.registry.region.RegionRegistryResult
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.events.EventBus
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.region.RegionListLarge
import org.rsmod.game.region.RegionListSmall
import org.rsmod.game.region.RegionListWorldEntity
import org.rsmod.game.region.zone.RegionZoneCopy
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class ShadowAreaContextTest {
    @Test
    fun `Guice selects the area-aware constructor while legacy construction remains available`() {
        val f = Fixture()
        f.source.coords = CoordGrid(3552, 5152)
        val injector = Guice.createInjector(object : AbstractModule() {
            override fun configure() {
                bind(AreaChecker::class.java).toInstance(f.areas)
            }
        })
        val injected = injector.getInstance(CombatMagicAttributeCollector::class.java)
        assertTrue(CombatStaffAttributes.TumekensShadowInAmascut in injected.staffCollect(f.source, NoRandom))
        assertFalse(CombatStaffAttributes.TumekensShadowInAmascut in CombatMagicAttributeCollector().staffCollect(f.source, NoRandom))
    }

    @Test
    fun `packed room boundaries and centres enable the powered passive on every level`() {
        val f = Fixture()
        for (room in ROOM_ORIGINS) {
            for (level in 0..3) {
                for ((dx, dz) in listOf(0 to 0, 63 to 0, 63 to 63, 0 to 63, 32 to 32)) {
                    val tile = room.translate(dx, dz).copy(level = level)
                    f.source.coords = tile
                    assertTrue(f.areas.inArea("area.toa_raid", tile), "Packed area omits $tile")
                    assertTrue(f.inRaid(), "Collector omits $tile")
                    assertEquals(43_120, f.staffRoll(), "$tile accuracy")
                    assertEquals(44, f.staffMax(), "$tile damage")
                }
            }
        }
    }

    @Test
    fun `external lobby and immediately adjacent map tiles do not inherit the passive`() {
        val f = Fixture()
        val outside = ROOM_ORIGINS.flatMap { room ->
            listOf(room.translate(-1, 32), room.translate(64, 32), room.translate(32, -1), room.translate(32, 64))
        } + listOf(CoordGrid(3369, 9117), CoordGrid(3222, 3218))
        for (tile in outside) {
            for (level in 0..3) {
                f.source.coords = tile.copy(level = level)
                assertFalse(f.areas.inArea("area.toa_raid", f.source.coords), "Area leaks into ${f.source.coords}")
                assertFalse(f.inRaid())
                assertEquals(34_100, f.staffRoll())
                assertEquals(42, f.staffMax())
            }
        }
    }

    @Test
    fun `entry level zero and stale party settings cannot substitute for current position`() {
        val f = Fixture()
        VarPlayerIntMapSetter.set(f.source, "varbit.toa_client_raid_level", 0)
        VarPlayerIntMapSetter.set(f.source, "varbit.toa_client_partystatus", 0)
        f.source.coords = CoordGrid(3552, 5152)
        assertTrue(f.inRaid())
        assertEquals(43_120, f.staffRoll())
        assertEquals(44, f.staffMax())

        VarPlayerIntMapSetter.set(f.source, "varbit.toa_client_raid_level", 500)
        VarPlayerIntMapSetter.set(f.source, "varbit.toa_client_partystatus", 2)
        f.source.coords = CoordGrid(3369, 9117)
        assertFalse(f.inRaid())
        assertEquals(34_100, f.staffRoll())
        assertEquals(42, f.staffMax())

        f.source.coords = CoordGrid(3680, 5152)
        assertTrue(f.inRaid())
        f.source.coords = CoordGrid(3222, 3218)
        assertFalse(f.inRaid())
    }

    @Test
    fun `actual registered zone copies normalize all rotations and source levels`() {
        val f = Fixture()
        val region = (f.regions.registerSmall() as RegionRegistryResult.Add.Success).region
        val offsets = listOf(1 to 2, 2 to 6, 6 to 5, 5 to 1)
        for (level in 0..3) {
            for (rotation in 0..3) {
                val normal = CoordGrid(3905, 5122, level)
                val destination = region.southWest.translate(rotation * 8, level * 8).copy(level = (level + 1) % 4)
                f.regions.registerZone(
                    region,
                    ZoneKey.from(destination),
                    RegionZoneCopy(ZoneKey.from(normal), rotation, null),
                )
                val (dx, dz) = offsets[rotation]
                f.source.coords = destination.translate(dx, dz)

                assertEquals(normal, f.regions.normalizeCoords(f.source.coords), "rotation=$rotation sourceLevel=$level")
                assertTrue(f.inRaid())
                assertEquals(43_120, f.staffRoll())
                assertEquals(44, f.staffMax())
            }
        }
    }

    @Test
    fun `copied external lobby and an unregistered instance zone are excluded`() {
        val f = Fixture()
        val region = (f.regions.registerSmall() as RegionRegistryResult.Add.Success).region
        val destination = region.southWest
        f.regions.registerZone(
            region,
            ZoneKey.from(destination),
            RegionZoneCopy(ZoneKey.from(CoordGrid(3369, 9117)), 0, null),
        )
        f.source.coords = destination.translate(1, 5)
        assertEquals(CoordGrid(3369, 9117), f.regions.normalizeCoords(f.source.coords))
        assertFalse(f.inRaid())
        assertEquals(42, f.staffMax())

        f.source.coords = destination.translate(8, 0)
        assertEquals(CoordGrid.NULL, f.regions.normalizeCoords(f.source.coords))
        assertFalse(f.inRaid())
        assertEquals(34_100, f.staffRoll())
    }

    @Test
    fun `native raid NPC identity and legacy NPC attribute do not enable relocated Shadow bonus`() {
        val f = Fixture()
        f.source.coords = CoordGrid(3222, 3218)
        val relocated = Npc("npc.toa_zebak", f.source.coords.translate(2, 0))
        assertFalse(f.inRaid())
        assertEquals(42, f.staffMax(relocated))
        val legacy = EnumSet.of(CombatNpcAttributes.Amascut)
        assertEquals(34_100, f.staffRoll(legacy))
        assertEquals(42, f.modifiedStaffMax(legacy))

        f.source.coords = CoordGrid(3552, 5152)
        assertEquals(44, f.staffMax())
        assertEquals(44, f.staffMax(relocated))
    }

    @Test
    fun `manual spells and PvP formulas remain raw inside a real packed raid area`() {
        val f = Fixture()
        f.source.coords = CoordGrid(3552, 5152)
        assertTrue(f.inRaid())
        assertEquals(43_120, f.staffRoll())
        assertEquals(15_768, f.spellRoll())
        assertEquals(0..25, f.spellMax())
        assertEquals(15_768, f.pvpSpellRoll())
        assertEquals(0..25, f.pvpSpellMax())
        assertEquals(16_060, f.pvpAccuracy.computeStaffAttackRoll(f.source, MagicAttackStyle.Accurate))
        assertEquals(36, f.pvpMax.getStaffMaxHit(f.source, f.opponent, 34, 1.0))
    }

    @Test
    fun `uncharged Shadow is excluded even inside the raid`() {
        val f = Fixture()
        f.source.coords = CoordGrid(3552, 5152)
        f.equip("obj.tumekens_shadow_uncharged")
        assertTrue(f.areas.inArea("area.toa_raid", f.source.coords))
        assertFalse(f.inRaid())
        assertFalse(CombatStaffAttributes.TumekensShadow in f.staffAttributes())
        assertEquals(16_060, f.staffRoll())
        assertEquals(36, f.staffMax())
    }

    @Test
    fun `actual location enables the equipment cap before Augury`() {
        val f = Fixture()
        f.equip(
            "obj.ancestral_hat", "obj.ancestral_robe_bottom", "obj.confliction_gauntlets",
            "obj.avernic_treads_magic", "obj.ma2_saradomin_cape", "obj.magus_ring",
        )
        f.source.coords = CoordGrid(3552, 5152)
        assertEquals(84_920, f.staffRoll())
        assertEquals(68, f.staffMax())
        VarPlayerIntMapSetter.set(f.source, "varbit.prayer_augury", 1)
        assertEquals(103_448, f.staffRoll())
        assertEquals(69, f.staffMax())
        f.source.coords = CoordGrid(3369, 9117)
        assertEquals(79_730, f.staffRoll())
        assertEquals(62, f.staffMax())
    }

    private class Fixture {
        val source = player()
        val opponent = player()
        private val collision = CollisionFlagMap()
        private val locs = LocZoneStorage()
        val regions = RegionRegistry(
            RegionListSmall(), RegionListLarge(), RegionListWorldEntity(),
            LocRegistryNormal(ZoneUpdateMap(), collision, locs), collision, locs,
            mockk(), mockk(), ZonePlayerActivityBitSet(),
        )
        val areas = AreaChecker(regions, areaIndex)
        private val collector = CombatMagicAttributeCollector(areas)
        private val npcCollector = CombatNpcAttributeCollector()
        private val bonuses = WornBonuses()
        private val npc = Npc("npc.king_dragon", CoordGrid(2271, 4695))
        private val accuracy = PvNMagicAccuracy(NoRandom, bonuses, collector, npcCollector)
        private val maximum = PvNMagicMaxHit(NoRandom, bonuses, npcCollector, collector)
        private val styles = AttackStyles().also {
            with(WeaponAttackStylesScript(it)) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
        val pvpAccuracy = PvPMagicAccuracy(NoRandom, bonuses, styles, collector)
        val pvpMax = PvPMagicMaxHit(NoRandom, bonuses, collector, DamageReductionAttributeCollector(styles))
        private val spell = item("obj.95_fire_surge")

        init { equip("obj.tumekens_shadow", "obj.occult_necklace", "obj.ancestral_robe_top") }

        fun equip(vararg symbols: String) {
            for (symbol in symbols) {
                val type = item(symbol)
                source.worn[type.wearpos1] = InvObj(type)
            }
        }

        fun staffAttributes(): EnumSet<CombatStaffAttributes> = collector.staffCollect(source, NoRandom)

        fun inRaid(): Boolean = CombatStaffAttributes.TumekensShadowInAmascut in staffAttributes()

        private fun npcAttributes(): EnumSet<CombatNpcAttributes> =
            npcCollector.collect(npc.visType, npc, npc.hitpoints, npc.baseHitpointsLvl, slayerTask = false)

        fun staffRoll(attrs: EnumSet<CombatNpcAttributes> = npcAttributes()): Int =
            accuracy.computeStaffAttackRoll(source, MagicAttackStyle.Accurate, staffAttributes(), attrs)

        fun staffMax(target: Npc = npc): Int = maximum.getStaffMaxHit(source, target, 34, 1.0)

        fun modifiedStaffMax(attrs: EnumSet<CombatNpcAttributes>): Int =
            maximum.computeStaffModifiedDamage(source, 34, staffAttributes(), attrs)

        private fun spellAttributes() = collector.spellCollect(source, spell, Spellbook.Standard, false, NoRandom)

        fun spellRoll(): Int = accuracy.computeSpellAttackRoll(source, 0, spellAttributes(), npcAttributes())

        fun spellMax(): IntRange = maximum.getSpellMaxHit(source, npc, spell, Spellbook.Standard, 24, 5, false)

        fun pvpSpellRoll(): Int = pvpAccuracy.computeSpellAttackRoll(source, spellAttributes())

        fun pvpSpellMax(): IntRange = pvpMax.getSpellMaxHit(source, opponent, spell, Spellbook.Standard, 24, false)
    }

    private object NoRandom : GameRandom {
        override fun of(maxExclusive: Int): Int = error("Area context unexpectedly consumed RNG: $maxExclusive")
        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Area context unexpectedly rolled damage")
        override fun randomDouble(): Double = error("Area context unexpectedly consumed floating-point RNG")
    }

    companion object {
        // Source model: LlemonDuck/tombs-of-amascut cec1665058a3244bf41daddc0e1f43177a0b3edf RaidRoom.java.
        private val ROOM_ORIGINS = listOf(
            CoordGrid(3520, 5120), CoordGrid(3904, 5248), CoordGrid(3904, 5376),
            CoordGrid(3520, 5248), CoordGrid(3520, 5376), CoordGrid(3776, 5248),
            CoordGrid(3776, 5376), CoordGrid(3648, 5248), CoordGrid(3648, 5376),
            CoordGrid(3776, 5120), CoordGrid(3904, 5120), CoordGrid(3648, 5120),
        )
        private lateinit var areaIndex: AreaIndex

        @JvmStatic
        @BeforeAll
        fun cache() {
            val cache = ServerCacheManager.init(241)
            try {
                areaIndex = AreaIndex()
                for (region in cache.archives(MAPS)) {
                    if (region > 25286) continue
                    if (7 !in cache.files(MAPS, region)) continue
                    if (cache.data(MAPS, region, 0) == null || cache.data(MAPS, region, 1) == null) continue
                    val data = checkNotNull(cache.data(MAPS, region, 7)) {
                        "Declared area metadata is missing for map square $region"
                    }
                    val decoded = MapAreaDecoder.decode(InlineByteBuf(data))
                    MapAreaDefinitions.putAreas(areaIndex, MapSquareKey(region), decoded)
                }
            } finally {
                cache.close()
            }
        }

        private fun item(symbol: String): ItemServerType = checkNotNull(ServerCacheManager.getItem(symbol.asRSCM()))

        private fun player(): Player = Player().apply {
            worldType = WorldType.MAIN
            inv = invMap.getOrPut("inv.inv")
            worn = invMap.getOrPut("inv.worn")
            for (stat in listOf("stat.magic", "stat.defence", "stat.hitpoints")) {
                statMap.setBaseLevel(stat, 99)
                statMap.setCurrentLevel(stat, 99)
            }
        }
    }
}
