package org.rsmod.tools.wiki.dumping

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DropTableExportWriterTest {
    @TempDir lateinit var root: Path

    @Test
    fun `manual table is neither overwritten nor migrated to duplicate TOML`() {
        val kotlinRoot = root.resolve("kotlin")
        val output = DropTableOutputLayout.resolveMonsterOutputFile(kotlinRoot, "Cerberus")
        output.parent.createDirectories()
        val original = "// Hand-maintained drop table: verified encounter rules.\nval encounter = 42\n"
        output.writeText(original)
        for (toml in listOf(null, TomlExportConfig(root.resolve("toml")))) {
            var writes = 0
            val result = DropTableExportWriter.exportPage(
                wikiPage = "Cerberus",
                specs = listOf(spec()),
                kotlinRoot = kotlinRoot,
                tomlExport = toml,
                kotlinOutput = true,
                log = DropDumpLog(quiet = true),
                onKotlinWritten = { writes++ },
            )
            assertEquals(original, output.readText())
            assertEquals(output, result.kotlinFile)
            assertTrue(result.tomlFiles.isEmpty())
            assertEquals(0, writes)
            assertFalse(root.resolve("toml").exists())
        }
    }

    @Test
    fun `generated tables still migrate to TOML and remove obsolete Kotlin`() {
        val kotlinRoot = root.resolve("kotlin")
        val output = DropTableOutputLayout.resolveMonsterOutputFile(kotlinRoot, "Cerberus")
        output.parent.createDirectories()
        output.writeText("// Generated table\n")
        val result = DropTableExportWriter.exportPage(
            wikiPage = "Cerberus",
            specs = listOf(spec()),
            kotlinRoot = kotlinRoot,
            tomlExport = TomlExportConfig(root.resolve("toml")),
            kotlinOutput = true,
            log = DropDumpLog(quiet = true),
        )
        assertFalse(output.exists())
        assertEquals(1, result.tomlFiles.size)
        assertTrue(result.tomlFiles.single().readText().contains("obj.rune_platebody"))
    }

    private fun spec() = GeneratedDropTableSpec(
        tableVarName = "cerberusDropTable",
        tableIdentifier = "Cerberus Drops",
        npcRscmKeys = listOf("npc.cerberus_attacking"),
        guaranteed = emptyList(),
        main = listOf(ResolvedDropEntry(
            obj = "obj.rune_platebody", quantity = "1", weight = 1, wikiName = "Rune platebody",
        )),
        mainMaxRoll = 1,
        tertiary = emptyList(),
    )
}
