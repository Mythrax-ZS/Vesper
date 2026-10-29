package org.rsmod.api.account

import com.fasterxml.jackson.databind.ObjectMapper
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.inject.Provider
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.CharacterMetadataList
import org.rsmod.api.account.character.inv.CharacterInventoryApplier
import org.rsmod.api.account.character.inv.CharacterInventoryPipeline
import org.rsmod.api.account.character.inv.CharacterInventorySaveData
import org.rsmod.api.account.character.main.CharacterAccountApplier
import org.rsmod.api.account.character.main.CharacterAccountRepository
import org.rsmod.api.account.character.stats.CharacterStatApplier
import org.rsmod.api.account.character.stats.CharacterStatPipeline
import org.rsmod.api.account.saver.AccountSavingService
import org.rsmod.api.account.saver.request.AccountSaveResponse
import org.rsmod.api.attr.AttributeKey
import org.rsmod.api.db.DatabaseConfig
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.db.jdbc.GameConnection
import org.rsmod.api.db.jdbc.GameDatabase
import org.rsmod.api.server.config.ServerConfig
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class AccountSnapshotPostgresTest {
    @TempDir
    lateinit var directory: Path

    @Test
    @Timeout(120)
    fun `real PostgreSQL transactions preserve captured modes and order failed retries without stale identity writes`() = runBlocking {
        EmbeddedPostgres.builder()
            .setDataDirectory(directory.resolve("pgdata").toFile())
            .setCleanDataDirectory(true)
            .setPGStartupWait(Duration.ofSeconds(30))
            .setRegisterShutdownHook(false)
            .start().use { postgres ->
                val config = DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "", false)
                val server = ServerConfig("snapshot-test", 0, 241, "test", 1,
                    worldTypes = listOf(WorldType.MAIN.key, WorldType.TWISTED_LEAGUE.key))
                val provider = Provider { config }
                val database = GameDatabase(provider, Provider { server })
                database.connect(GameConnection(provider))
                try {
                    exercise(database, server)
                } finally {
                    database.close()
                }
            }
    }

    private suspend fun exercise(database: GameDatabase, server: ServerConfig) {
        val repository = CharacterAccountRepository(ObjectMapper(), CharacterAccountApplier())
        val inventory = FailAfterInventoryWrite(CharacterInventoryPipeline(CharacterInventoryApplier()))
        val pipelines = linkedSetOf<CharacterDataStage.Pipeline>(inventory, CharacterStatPipeline(CharacterStatApplier()))
        val saver = AccountSavingService(database, repository, pipelines, server)
        val accounts = AccountManager(mockk(), saver, repository, pipelines)
        val (accountId, characterId) = database.withTransactionBlocking(WorldType.MAIN.key) { connection ->
            val account = checkNotNull(repository.insertOrSelectAccountId(connection, "snapshot_sql", "fixture-hash"))
            val character = checkNotNull(repository.insertAndSelectCharacterId(connection, account))
            account to character
        }
        for (mode in listOf(WorldType.MAIN, WorldType.TWISTED_LEAGUE)) {
            database.withTransactionBlocking(mode.key) { repository.ensureProgressRow(it, characterId) }
            repeat(2) {
                assertTrue(firstVisit(database, repository, mode), "$mode remains a first visit before its first persisted save")
            }
        }
        val player = Player().apply {
            this.accountId = accountId
            this.characterId = characterId
            username = "snapshot_sql"
            members = true
            lastLogin = LocalDateTime.of(2026, 10, 30, 1, 2)
            xpRate = 1.0
            inv = invMap.getOrPut("inv.inv")
            inv.owner = this
            worn = invMap.getOrPut("inv.worn")
            worn.owner = this
        }
        val completed = mutableListOf<String>()
        fun enqueue(label: String) {
            accounts.save(player) { response ->
                assertTrue(response is AccountSaveResponse.Success, "$label: $response")
                completed += label
            }
        }

        player.install(WorldType.MAIN, 11, "Captured")
        enqueue("captured")
        player.install(WorldType.TWISTED_LEAGUE, 99, "Live mutation")
        saver.run()
        assertEquals(listOf("captured"), completed)
        assertStored(database, characterId, WorldType.MAIN, 11, "Captured")
        assertNull(read(database, characterId, WorldType.TWISTED_LEAGUE).count)
        assertFalse(firstVisit(database, repository, WorldType.MAIN))
        assertTrue(firstVisit(database, repository, WorldType.TWISTED_LEAGUE))

        player.install(WorldType.MAIN, 20, "Old main")
        inventory.fail(20, times = 2)
        enqueue("old-main")
        saver.run()
        assertStored(database, characterId, WorldType.MAIN, 11, "Captured")
        assertEquals(listOf("captured"), completed)

        player.install(WorldType.MAIN, 30, "New main")
        enqueue("new-main")
        saver.run()
        assertStored(database, characterId, WorldType.MAIN, 30, "New main")
        assertEquals(listOf("captured", "new-main"), completed)
        saver.run()
        assertStored(database, characterId, WorldType.MAIN, 30, "New main")
        assertEquals(listOf("captured", "new-main", "old-main"), completed)
        assertEquals(2, inventory.attempts.count { it == 20 }, "Superseded retry must not write again")

        player.install(WorldType.MAIN, 40, "Old cross")
        inventory.fail(40, times = 2)
        enqueue("old-cross")
        saver.run()
        assertStored(database, characterId, WorldType.MAIN, 30, "New main")
        player.install(WorldType.TWISTED_LEAGUE, 50, "League new")
        enqueue("new-league")
        saver.run()
        assertStored(database, characterId, WorldType.TWISTED_LEAGUE, 50, "League new")
        assertFalse(firstVisit(database, repository, WorldType.TWISTED_LEAGUE))
        assertStored(database, characterId, WorldType.MAIN, 30, "League new")
        player.install(WorldType.TWISTED_LEAGUE, 999, "Never queued")
        saver.run()
        assertStored(database, characterId, WorldType.MAIN, 40, "League new")
        assertStored(database, characterId, WorldType.TWISTED_LEAGUE, 50, "League new")
        assertEquals(3, inventory.attempts.count { it == 40 })
        assertEquals(listOf("captured", "new-main", "old-main", "new-league", "old-cross"), completed)
    }

    private fun firstVisit(database: GameDatabase, repository: CharacterAccountRepository, mode: WorldType): Boolean =
        database.withTransactionBlocking(mode.key) { connection ->
            var firstVisit = false
            val metadata = repository.selectAndCreateMetadataList(connection, "snapshot_sql") { firstVisit = true }
            assertNotNull(metadata)
            firstVisit
        }

    private fun Player.install(mode: WorldType, quantity: Int, name: String) {
        worldType = mode
        displayName = name
        coords = CoordGrid(3200 + quantity, 3201)
        runEnergy = quantity * 10
        inv[0] = InvObj("obj.coins", quantity, vars = quantity + 100)
        vars.backing[PERMANENT_VARP.asRSCM(RSCMType.VARP)] = quantity
        attr[SAVED_ATTR] = linkedMapOf("quantity" to quantity)
        statMap.setBaseLevel("stat.hunter", 52)
        statMap.setCurrentLevel("stat.hunter", 45)
        statMap.setFineXP("stat.hunter", quantity * 100)
    }

    private fun assertStored(database: GameDatabase, characterId: Int, mode: WorldType, quantity: Int, name: String) {
        val state = read(database, characterId, mode)
        assertEquals(quantity, state.count, "$mode inventory")
        assertEquals(quantity + 100, state.itemVars, "$mode item vars")
        assertEquals(quantity * 100, state.fineXp, "$mode XP")
        assertEquals(3200 + quantity, state.x, "$mode coordinates")
        assertEquals(quantity * 10, state.energy, "$mode energy")
        assertEquals(quantity, state.varp, "$mode persistent varp")
        assertEquals("{\"quantity\":$quantity}", state.attrJson, "$mode persistent attrs")
        assertEquals(name, state.displayName, "Shared character identity")
    }

    private fun read(database: GameDatabase, characterId: Int, mode: WorldType): Stored =
        database.withTransactionBlocking(mode.key) { connection ->
            val sql = """
                SELECT p.x, p.run_energy, c.display_name, o.count, o.vars, s.fine_xp,
                       v.value AS saved_varp, a.value_json
                FROM character_progress p
                JOIN public.account_characters c ON c.id = p.character_id
                LEFT JOIN inventory_objs o ON o.character_id = p.character_id AND o.inv = 'inv.inv' AND o.slot = 0
                LEFT JOIN stats s ON s.character_id = p.character_id AND s.stat_id = ?
                LEFT JOIN character_varps v ON v.character_id = p.character_id AND v.varp = ?
                LEFT JOIN character_attrs a ON a.character_id = p.character_id AND a.attr = ?
                WHERE p.character_id = ?
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, "stat.hunter".asRSCM(RSCMType.STAT))
                statement.setString(2, PERMANENT_VARP)
                statement.setString(3, "snapshot.sql.quantity")
                statement.setInt(4, characterId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    Stored(result.getInt("count").takeUnless { result.wasNull() }, result.getInt("vars"),
                        result.getInt("fine_xp"), result.getInt("x"), result.getInt("run_energy"),
                        result.getInt("saved_varp"), result.getString("value_json"), result.getString("display_name"))
                }
            }
        }

    private data class Stored(val count: Int?, val itemVars: Int, val fineXp: Int, val x: Int,
        val energy: Int, val varp: Int, val attrJson: String?, val displayName: String)

    private class FailAfterInventoryWrite(private val delegate: CharacterInventoryPipeline) : CharacterDataStage.Pipeline {
        val attempts = mutableListOf<Int>()
        private val failures = mutableMapOf<Int, Int>()

        fun fail(quantity: Int, times: Int) { failures[quantity] = times }
        override fun append(connection: DatabaseConnection, metadata: CharacterMetadataList) = delegate.append(connection, metadata)
        override fun capture(player: Player): CharacterDataStage.SaveData = delegate.capture(player)
        override fun restore(player: Player, data: CharacterDataStage.SaveData) = delegate.restore(player, data)

        override fun save(connection: DatabaseConnection, data: CharacterDataStage.SaveData, characterId: Int) {
            delegate.save(connection, data, characterId)
            val quantity = (data as CharacterInventorySaveData).inventories.single { it.key == "inv.inv" }.objs.getValue(0).count
            attempts += quantity
            connection.prepareStatement("SELECT count FROM inventory_objs WHERE character_id = ? AND inv = 'inv.inv' AND slot = 0").use { statement ->
                statement.setInt(1, characterId)
                statement.executeQuery().use { result -> assertTrue(result.next()); assertEquals(quantity, result.getInt(1)) }
            }
            val remaining = failures.getOrDefault(quantity, 0)
            if (remaining > 0) {
                failures[quantity] = remaining - 1
                throw IllegalStateException("Injected failure after real inventory writes")
            }
        }
    }

    companion object {
        private const val PERMANENT_VARP = "varp.agility_recovery_start"
        private val SAVED_ATTR = AttributeKey<MutableMap<String, Int>>("snapshot.sql.quantity")

        @JvmStatic
        @BeforeAll
        fun cache() { ServerCacheManager.init(241).close() }
    }
}
