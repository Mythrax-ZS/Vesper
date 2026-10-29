package dev.openrune.map.packing

import dev.openrune.definition.constants.ConstantProvider
import dev.openrune.definition.constants.MappingProvider
import dev.openrune.gamevals.GameValProvider
import dev.openrune.map.area.MapAreaDefinition
import dev.openrune.map.area.MapAreaDefinitions
import dev.openrune.map.area.MapAreaEncoder
import dev.openrune.pack.PluginPack
import dev.openrune.pack.PluginPacks
import dev.openrune.rscm.RSCM.asRSCM
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.shorts.ShortArrayList
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import org.rsmod.game.area.AreaIndex
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey

@Isolated("Temporarily installs the repository gameval provider")
class MapAreaPackerTest {
    @TempDir lateinit var temp: File

    private lateinit var previousProviders: List<MappingProvider>

    @BeforeEach
    fun installSymbols() {
        previousProviders = ConstantProvider.getCurrentProviders().toList()
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, "content").isDirectory }
        val provider = GameValProvider.loadIsolated(root.path + "/")
        // Installing lookup precedence alone does not initialize ConstantProvider's base mapping.
        ConstantProvider.load(provider)
        ConstantProvider.overrideProviders(listOf(provider))
    }

    @AfterEach
    fun restoreSymbols() {
        ConstantProvider.overrideProviders(previousProviders)
    }

    @Test
    fun `only active pack area directories are discovered once in stable order`() {
        val alpha = pack("alpha")
        val beta = pack("beta")
        val disabled = pack("disabled", enabled = false)
        val empty = object : PluginPack() {
            override fun resourceRoot(): File = File(temp, "empty")
        }
        val packs = PluginPacks(temp, listOf(beta, disabled, alpha, beta, empty))
        assertEquals(
            listOf(alpha.mapAreaDirectory()!!.canonicalFile, beta.mapAreaDirectory()!!.canonicalFile),
            packs.mapAreaDirectories(),
        )
    }

    @Test
    fun `legacy and plugin polygons retain overlap repeated area unions and inclusive edges`() {
        val legacy = File(temp, "legacy").apply { mkdirs() }
        val plugin = pack("plugin")
        writeArea(legacy, "legacy", "area.wilderness", 3200, 3200, 3207, 3207)
        writeArea(plugin.mapAreaDirectory()!!, "overlap", "area.multiway", 3204, 3204, 3211, 3211)
        writeArea(plugin.mapAreaDirectory()!!, "extension", "area.wilderness", 3212, 3200, 3215, 3203)
        val collected = MapAreaPacker.loadAndCollect(
            listOf(legacy.toPath()) + PluginPacks(temp, listOf(plugin)).mapAreaDirectories().map(File::toPath)
        )
        val index = index(collected)
        assertEquals(setOf("area.wilderness".asRSCM().toShort()), areasAt(index, 3200, 3200))
        assertEquals(
            setOf("area.wilderness".asRSCM().toShort(), "area.multiway".asRSCM().toShort()),
            areasAt(index, 3207, 3207),
        )
        assertEquals(setOf("area.multiway".asRSCM().toShort()), areasAt(index, 3208, 3208))
        assertEquals(setOf("area.wilderness".asRSCM().toShort()), areasAt(index, 3215, 3203))
        assertTrue(areasAt(index, 3216, 3203).isEmpty())
        assertTrue(areasAt(index, 3204, 3204, level = 1).isEmpty())
    }

    @Test
    fun `input directory order and repeated references produce identical encoded area bytes`() {
        val first = File(temp, "first").apply { mkdirs() }
        val second = File(temp, "second").apply { mkdirs() }
        writeArea(first, "one", "area.wilderness", 3200, 3200, 3207, 3207)
        writeArea(second, "two", "area.multiway", 3204, 3204, 3211, 3211)
        val canonical = MapAreaPacker.loadAndCollect(listOf(first.toPath(), second.toPath()))
        val repeated = MapAreaPacker.loadAndCollect(listOf(second.toPath(), first.toPath(), second.toPath()))
        assertEquals(encoded(canonical), encoded(repeated))
    }

    @Test
    fun `recollecting with a disabled pack removes its areas and preserves legacy areas`() {
        val legacy = File(temp, "legacy").apply { mkdirs() }
        val enabled = pack("plugin")
        writeArea(legacy, "legacy", "area.wilderness", 3200, 3200, 3207, 3207)
        writeArea(enabled.mapAreaDirectory()!!, "plugin", "area.multiway", 3200, 3200, 3207, 3207)
        fun collect(pack: PluginPack): AreaIndex {
            val directories = PluginPacks(temp, listOf(pack)).mapAreaDirectories().map(File::toPath)
            return index(MapAreaPacker.loadAndCollect(listOf(legacy.toPath()) + directories))
        }
        assertTrue("area.multiway".asRSCM().toShort() in areasAt(collect(enabled), 3200, 3200))
        val withoutPlugin = collect(pack("plugin", enabled = false))
        assertFalse("area.multiway".asRSCM().toShort() in areasAt(withoutPlugin, 3200, 3200))
        assertEquals(setOf("area.wilderness".asRSCM().toShort()), areasAt(withoutPlugin, 3200, 3200))
    }

    @Test
    fun `metadata only extension preserves all parent rules in either map registration order`() {
        val legacy = File(temp, "legacy").apply { mkdirs() }
        val plugin = pack("plugin")
        val extension = plugin.mapAreaDirectory()!!
        File(legacy, "parent.toml").writeText(areaEntry(
            "area.wilderness", listOf(0, 1, 2, 3), listOf(3200, 3200, 3263, 3263),
            includes = listOf("area.wilderness_dungeons"), excludes = listOf("area.ferox_enclave"),
        ))
        File(extension, "parent.toml").writeText(areaEntry(
            "area.wilderness", listOf(0, 1, 2, 3), listOf(3328, 3200, 3391, 3263),
            includes = listOf("area.chaos_temple"), excludes = listOf("area.multiway"),
        ))
        File(extension, "metadata.toml").writeText(areaEntry(
            "area.wilderness", excludes = listOf("area.demonic_ruins"),
        ))
        for (origin in listOf(3200, 3328)) {
            writeArea(legacy, "old-safe-$origin", "area.ferox_enclave", origin + 2, 3202, origin + 3, 3203)
            writeArea(extension, "new-safe-$origin", "area.multiway", origin + 4, 3202, origin + 5, 3203)
            writeArea(extension, "metadata-safe-$origin", "area.demonic_ruins", origin + 6, 3202, origin + 7, 3203)
        }
        writeArea(legacy, "old-child", "area.wilderness_dungeons", 3457, 3201, 3459, 3203)
        writeArea(extension, "new-child", "area.chaos_temple", 3521, 3201, 3523, 3203)
        val collected = MapAreaPacker.loadAndCollect(listOf(legacy.toPath(), extension.toPath()))
        for (index in bothRegistrationOrders(collected)) {
            for (origin in listOf(3200, 3328)) {
                assertTrue(inArea(index, "area.wilderness", origin, 3200))
                for (offset in listOf(2, 4, 6)) {
                    assertFalse(inArea(index, "area.wilderness", origin + offset, 3202), "Excluded offset=$offset origin=$origin")
                }
            }
            assertTrue(inArea(index, "area.wilderness", 3458, 3202), "Legacy include must survive")
            assertTrue(inArea(index, "area.wilderness", 3522, 3202), "Plugin include must survive")
        }
    }

    @Test
    fun `partial repeated polygons retain relationship unions across files planes and regions`() {
        val legacy = File(temp, "legacy").apply { mkdirs() }
        val extension = pack("plugin").mapAreaDirectory()!!
        // Neither the complete zone nor the small tile polygon qualifies for map-square promotion.
        File(legacy, "parent.toml").writeText(
            areaEntry("area.wilderness", listOf(0), listOf(3200, 3200, 3207, 3207),
                includes = listOf("area.wilderness_dungeons"), excludes = listOf("area.ferox_enclave")) +
            "\n" + areaEntry("area.wilderness", listOf(2), listOf(3265, 3201, 3271, 3203),
                includes = listOf("area.chaos_temple"), excludes = listOf("area.multiway")),
        )
        File(extension, "parent.toml").writeText(areaEntry(
            "area.wilderness", listOf(1), listOf(3329, 3201, 3335, 3203),
        ))
        writeArea(legacy, "old-child", "area.wilderness_dungeons", 3393, 3201, 3395, 3203)
        writeArea(legacy, "new-child", "area.chaos_temple", 3457, 3201, 3459, 3203)
        for ((name, level, x) in listOf(Triple("zone", 0, 3201), Triple("tile", 2, 3265), Triple("extension", 1, 3329))) {
            File(extension, "$name-safe.toml").writeText(
                areaEntry("area.ferox_enclave", listOf(level), listOf(x, 3201, x + 1, 3203)) + "\n" +
                areaEntry("area.multiway", listOf(level), listOf(x + 2, 3201, x + 3, 3203)),
            )
        }
        val collected = MapAreaPacker.loadAndCollect(listOf(legacy.toPath(), extension.toPath()))
        for (index in bothRegistrationOrders(collected)) {
            for ((level, x) in listOf(0 to 3201, 2 to 3265, 1 to 3329)) {
                assertFalse(inArea(index, "area.wilderness", x, 3202, level), "Legacy exclusion at partial parent level=$level")
                assertFalse(inArea(index, "area.wilderness", x + 2, 3202, level), "Repeated-entry exclusion level=$level")
                assertTrue(inArea(index, "area.wilderness", x + 4, 3202, level), "Unexcluded partial parent level=$level")
                assertFalse(inArea(index, "area.wilderness", x + 4, 3202, (level + 1) % 4), "Do not leak geometry to other planes")
            }
            assertTrue(inArea(index, "area.wilderness", 3394, 3202), "First repeated entry's include")
            assertTrue(inArea(index, "area.wilderness", 3458, 3202), "Second repeated entry's include")
        }
    }

    @Test
    fun `disabling a metadata only pack removes only its parent relationship extension`() {
        val legacy = File(temp, "legacy").apply { mkdirs() }
        val enabled = pack("plugin")
        File(legacy, "parent.toml").writeText(areaEntry(
            "area.wilderness", listOf(0, 1, 2, 3), listOf(3200, 3200, 3263, 3263),
            includes = listOf("area.wilderness_dungeons"), excludes = listOf("area.ferox_enclave"),
        ))
        writeArea(legacy, "old-safe", "area.ferox_enclave", 3202, 3202, 3203, 3203)
        writeArea(legacy, "extension-target", "area.multiway", 3204, 3202, 3205, 3203)
        writeArea(legacy, "old-child", "area.wilderness_dungeons", 3329, 3201, 3331, 3203)
        File(enabled.mapAreaDirectory()!!, "metadata.toml").writeText(areaEntry(
            "area.wilderness", excludes = listOf("area.multiway"),
        ))
        for (active in listOf(true, false, true)) {
            val directories = PluginPacks(temp, listOf(pack("plugin", enabled = active))).mapAreaDirectories()
            val definitions = MapAreaPacker.loadAndCollect(listOf(legacy.toPath()) + directories.map(File::toPath))
            for (index in bothRegistrationOrders(definitions)) {
                assertTrue(inArea(index, "area.multiway", 3204, 3202), "Child geometry remains when plugin is disabled")
                assertEquals(!active, inArea(index, "area.wilderness", 3204, 3202), "Only extension toggles: active=$active")
                assertFalse(inArea(index, "area.wilderness", 3202, 3202), "Original exclusion remains")
                assertTrue(inArea(index, "area.wilderness", 3330, 3202), "Original include remains")
                assertTrue(inArea(index, "area.wilderness", 3200, 3200), "Parent geometry remains")
            }
        }
    }

    private fun areaEntry(
        symbol: String,
        levels: List<Int> = listOf(0),
        bounds: List<Int>? = null,
        includes: List<String> = emptyList(),
        excludes: List<String> = emptyList(),
    ): String = buildString {
        appendLine("[[area]]")
        appendLine("name = \"$symbol\"")
        appendLine("area_id = \"$symbol\"")
        appendLine("levels = [${levels.joinToString()}]")
        appendLine("includes = [${includes.joinToString { "\"$it\"" }}]")
        appendLine("excludes = [${excludes.joinToString { "\"$it\"" }}]")
        if (bounds != null) {
            val (x, z, endX, endZ) = bounds
            appendLine("[[area.polygons]]")
            appendLine("vertices = [[$x, $z], [$endX, $z], [$endX, $endZ], [$x, $endZ]]")
        }
    }

    private fun bothRegistrationOrders(definitions: Map<MapSquareKey, MapAreaDefinition>): List<AreaIndex> =
        listOf(index(definitions), index(definitions.entries.reversed().associate { it.key to it.value }))

    private fun inArea(index: AreaIndex, symbol: String, x: Int, z: Int, level: Int = 0): Boolean {
        val areas = ShortArrayList()
        index.putAreasIncludingParents(CoordGrid(x, z, level), areas)
        return symbol.asRSCM().toShort() in areas
    }

    private fun pack(name: String, enabled: Boolean = true): PluginPack {
        val resourceRoot = File(temp, "$name/pack")
        File(resourceRoot, "map/area").mkdirs()
        return object : PluginPack() {
            override fun isEnabled(projectRoot: File): Boolean = enabled
            override fun resourceRoot(): File = resourceRoot
        }
    }

    private fun writeArea(directory: File, name: String, symbol: String, x: Int, z: Int, endX: Int, endZ: Int) {
        File(directory, "$name.toml").writeText(
            """
            [[area]]
            name = "$name"
            area_id = "$symbol"
            levels = [0]
            [[area.polygons]]
            vertices = [[$x, $z], [$endX, $z], [$endX, $endZ], [$x, $endZ]]
            """.trimIndent()
        )
    }

    private fun index(definitions: Map<MapSquareKey, MapAreaDefinition>): AreaIndex =
        AreaIndex().also { index ->
            definitions.forEach { (square, definition) ->
                MapAreaEncoder.validateAreaLimits(square, definition)
                MapAreaDefinitions.putAreas(index, square, definition)
            }
        }

    private fun areasAt(index: AreaIndex, x: Int, z: Int, level: Int = 0): Set<Short> {
        val areas = ShortArrayList()
        index.putAreas(CoordGrid(x, z, level), areas)
        return areas.toSet()
    }

    private fun encoded(definitions: Map<MapSquareKey, MapAreaDefinition>): Map<MapSquareKey, List<Byte>> =
        definitions.mapValues { (_, definition) ->
            val buffer = Unpooled.buffer()
            try {
                MapAreaEncoder.encode(definition, buffer)
                ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) }.toList()
            } finally {
                buffer.release()
            }
        }
}
