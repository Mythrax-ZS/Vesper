package org.rsmod.api.account

import com.fasterxml.jackson.databind.ObjectMapper
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.or2.sql.OpenRuneSql
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.account.character.inv.CharacterInventoryApplier
import org.rsmod.api.account.character.inv.CharacterInventoryPipeline
import org.rsmod.api.account.character.inv.CharacterInventorySaveData
import org.rsmod.api.account.character.main.CharacterAccountApplier
import org.rsmod.api.account.character.main.CharacterAccountRepository
import org.rsmod.api.account.character.stats.CharacterStatApplier
import org.rsmod.api.account.character.stats.CharacterStatPipeline
import org.rsmod.api.account.saver.AccountSavingService
import org.rsmod.api.attr.AttributeKey
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class CharacterSnapshotTest {
    private val repository = CharacterAccountRepository(ObjectMapper(), CharacterAccountApplier())
    private val inventories = CharacterInventoryPipeline(CharacterInventoryApplier())
    private val stats = CharacterStatPipeline(CharacterStatApplier())
    private val saver = mockk<AccountSavingService>(relaxed = true)
    private val accounts = AccountManager(mockk(), saver, repository, linkedSetOf(inventories, stats))

    @Test
    fun `capture detaches nested attrs inventory item vars stats and persistent varps`() {
        val player = player()
        val nested = linkedMapOf("caught" to mutableListOf(2, 7))
        player.attr[NESTED] = nested
        val before = accounts.capture(player)
        assertEquals(before, accounts.capture(player))
        nested.getValue("caught").add(99)
        player.inv[0] = InvObj("obj.coins", 900, vars = 17)
        player.vars.backing[PERMANENT_VARP.asRSCM(RSCMType.VARP)] = 888
        player.statMap.setCurrentLevel("stat.hunter", 1)
        player.statMap.setFineXP("stat.hunter", 9)
        player.worldType = WorldType.TWISTED_LEAGUE
        val capturedInv = before.segments.getValue(inventories.javaClass.name) as CharacterInventorySaveData
        val capturedObj = capturedInv.inventories.single { it.key == "inv.inv" }.objs.getValue(0)
        assertEquals(25, capturedObj.count)
        assertEquals(11, capturedObj.vars)
        assertEquals(123, before.main.varps.getValue(PERMANENT_VARP.asRSCM(RSCMType.VARP)))
        assertEquals("{\"caught\":[2,7]}", before.main.attrsJson.getValue("snapshot.test.nested"))
        assertEquals(WorldType.MAIN, before.worldType)
        assertNotEquals(before, accounts.capture(player))
        assertThrows(UnsupportedOperationException::class.java) {
            (capturedInv.inventories as MutableList<*>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (capturedInv.inventories.single { it.key == "inv.inv" }.objs as MutableMap<*, *>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (before.main.varps as MutableMap<*, *>).clear()
        }
    }

    @Test
    fun `equivalent nested map insertion orders have equal snapshots and mutable edits do not`() {
        val player = player()
        player.attr[NESTED] = linkedMapOf("a" to mutableListOf(1), "b" to mutableListOf(2))
        val first = accounts.capture(player)
        player.attr[NESTED] = linkedMapOf("b" to mutableListOf(2), "a" to mutableListOf(1))
        assertEquals(first, accounts.capture(player))
        player.attr[NESTED]!!.getValue("a")[0] = 5
        assertNotEquals(first, accounts.capture(player))
    }

    @Test
    fun `restore replaces target persistent state and does not retain new inventory rows or share snapshot attrs`() {
        val player = player()
        player.attr[NESTED] = linkedMapOf("caught" to mutableListOf(2, 7))
        val saved = accounts.capture(player)
        player.worldType = WorldType.TWISTED_LEAGUE
        player.inv[0] = InvObj("obj.bronze_dagger")
        player.inv[4] = InvObj("obj.logs")
        player.invMap.getOrPut("inv.bank")[0] = InvObj("obj.coins", 500)
        player.runEnergy = 1
        player.coords = CoordGrid(3000, 3000)
        player.vars.backing[PERMANENT_VARP.asRSCM(RSCMType.VARP)] = 999
        player.statMap.setFineXP("stat.hunter", 99)
        player.attr[NESTED] = linkedMapOf("target" to mutableListOf(8))
        player.persistenceSuspended = false
        accounts.restore(player, saved)
        assertFalse(player.persistenceSuspended)
        assertEquals(saved, accounts.capture(player))
        assertSame(player.inv, player.invMap.getValue("inv.inv"))
        assertNull(player.inv[4])
        assertFalse("inv.bank" in player.invMap)
        player.attr[NESTED]!!.getValue("caught").add(100)
        assertEquals("{\"caught\":[2,7]}", saved.main.attrsJson.getValue("snapshot.test.nested"))
        player.persistenceSuspended = true
        accounts.restore(player, saved)
        assertTrue(player.persistenceSuspended)
        assertEquals(saved, accounts.capture(player))
    }

    @Test
    fun `snapshot from a different character cannot restore or enqueue and stale checkpoint cannot enqueue`() {
        val player = player()
        val saved = accounts.capture(player)
        val other = player().apply { characterId++ }
        assertThrows(IllegalArgumentException::class.java) { accounts.restore(other, saved) }
        assertThrows(IllegalArgumentException::class.java) { accounts.save(other, saved) {} }
        player.inv[0] = InvObj("obj.coins", 26)
        assertThrows(IllegalArgumentException::class.java) { accounts.save(player, saved) {} }
        verify(exactly = 0) { saver.queue(any()) }
    }

    @Test
    fun `repository and pipelines bind captured JDBC values after live character switches and mutates`() {
        val player = player()
        player.attr[NESTED] = linkedMapOf("caught" to mutableListOf(2, 7))
        val saved = accounts.capture(player)
        player.worldType = WorldType.TWISTED_LEAGUE
        player.coords = CoordGrid(3300, 3400, 2)
        player.runEnergy = 9999
        player.displayName = "Target mode"
        player.inv[0] = InvObj("obj.coins", 999, vars = 88)
        player.vars.backing[PERMANENT_VARP.asRSCM(RSCMType.VARP)] = 999
        player.attr[NESTED]!!.getValue("caught").clear()
        player.statMap.setCurrentLevel("stat.hunter", 1)
        player.statMap.setFineXP("stat.hunter", 1)
        val jdbc = Jdbc()
        repository.save(jdbc.connection, saved.main, saved.characterId, 7)
        inventories.save(jdbc.connection, saved.segments.getValue(inventories.javaClass.name), saved.characterId)
        stats.save(jdbc.connection, saved.segments.getValue(stats.javaClass.name), saved.characterId)
        val progress = jdbc.single("game/character/character_progress_update_set_presence.sql").values
        assertEquals(3200, progress[1])
        assertEquals(3201, progress[2])
        assertEquals(0, progress[3])
        assertEquals(5432, progress[5])
        assertEquals(125, progress[6])
        assertEquals(7, progress[7])
        assertEquals(saved.characterId, progress[8])
        val obj = jdbc.single("game/inventory/upsert_inventory_obj.sql").batches.single()
        assertEquals("inv.inv", obj[2])
        assertEquals(0, obj[3])
        assertEquals("obj.coins", obj[4])
        assertEquals(25, obj[5])
        assertEquals(11, obj[6])
        val hunter = jdbc.single("game/stats/upsert_stat.sql").batches.single { it[2] == "stat.hunter".asRSCM(RSCMType.STAT) }
        assertEquals(45, hunter[3])
        assertEquals(52, hunter[4])
        assertEquals(101010, hunter[5])
        val varp = jdbc.single("game/character/character_varps_insert.sql").batches.single { it[2] == PERMANENT_VARP }
        assertEquals(123, varp[3])
        val attrs = jdbc.single("game/character/character_attrs_insert.sql").batches.single()
        assertEquals("{\"caught\":[2,7]}", attrs[3])
    }

    private fun player(): Player = Player().apply {
        accountId = 41
        characterId = 73
        worldType = WorldType.MAIN
        username = "snapshot-test"
        displayName = "Snapshot test"
        members = true
        coords = CoordGrid(3200, 3201)
        lastLogin = LocalDateTime.of(2026, 10, 30, 1, 2)
        runEnergy = 5432
        xpRate = 1.25
        inv = invMap.getOrPut("inv.inv")
        inv.owner = this
        worn = invMap.getOrPut("inv.worn")
        worn.owner = this
        inv[0] = InvObj("obj.coins", 25, vars = 11)
        vars.backing[PERMANENT_VARP.asRSCM(RSCMType.VARP)] = 123
        statMap.setCurrentLevel("stat.hunter", 45)
        statMap.setBaseLevel("stat.hunter", 52)
        statMap.setFineXP("stat.hunter", 101010)
    }

    private class Jdbc {
        val statements = mutableListOf<Statement>()
        val connection = DatabaseConnection(mockk<Connection> {
            every { prepareStatement(any<String>()) } answers {
                Statement(firstArg()).also { statements += it }.prepared
            }
        })

        fun single(path: String): Statement = statements.single { it.sql == OpenRuneSql.text(path) }

        class Statement(val sql: String) {
            val values = mutableMapOf<Int, Any?>()
            val batches = mutableListOf<Map<Int, Any?>>()
            val prepared = mockk<PreparedStatement>(relaxed = true)

            init {
                every { prepared.setInt(any(), any()) } answers { values[firstArg()] = secondArg<Int>(); Unit }
                every { prepared.setString(any(), any()) } answers { values[firstArg()] = secondArg<String>(); Unit }
                every { prepared.setBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); Unit }
                every { prepared.setTimestamp(any(), any()) } answers { values[firstArg()] = secondArg(); Unit }
                every { prepared.addBatch() } answers { batches += values.toMap(); Unit }
                every { prepared.executeUpdate() } returns 1
                every { prepared.executeBatch() } answers { IntArray(batches.size) { 1 } }
                every { prepared.executeQuery() } answers {
                    mockk<ResultSet>(relaxed = true) {
                        every { next() } returnsMany listOf(true, false)
                        every { getInt("character_id") } answers { values.getValue(1) as Int }
                        every { getString("inv") } answers { values.getValue(2) as String }
                    }
                }
            }
        }
    }

    companion object {
        private const val PERMANENT_VARP = "varp.agility_recovery_start"
        private val NESTED = AttributeKey<MutableMap<String, MutableList<Int>>>("snapshot.test.nested")

        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
