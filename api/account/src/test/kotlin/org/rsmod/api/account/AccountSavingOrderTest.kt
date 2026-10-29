package org.rsmod.api.account

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.main.CharacterAccountRepository
import org.rsmod.api.account.character.main.CharacterMainSaveData
import org.rsmod.api.account.saver.AccountSavingService
import org.rsmod.api.account.saver.CharacterSaveSnapshot
import org.rsmod.api.account.saver.request.AccountSaveRequest
import org.rsmod.api.account.saver.request.AccountSaveResponse
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.db.jdbc.GameDatabase
import org.rsmod.api.server.config.ServerConfig
import org.rsmod.game.entity.Player
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid

class AccountSavingOrderTest {
    @Test
    fun `failed older same mode save cannot overwrite a newer commit and completes once`() {
        val f = Fixture()
        val older = f.request(1, WorldType.MAIN)
        val newer = f.request(2, WorldType.MAIN)
        f.failures[1] = 1
        f.service.queue(older)
        f.service.queue(newer)
        f.drain()
        assertEquals(2, f.saved(WorldType.MAIN).main.runEnergy)
        assertEquals(Version(2), f.saved(WorldType.MAIN).segment)
        assertTrue(f.responses(1).isEmpty())
        assertEquals(1, f.responses(2).size)
        assertEquals(1, f.pendingCharacters(), "Newer completion must retain ordering while an older retry remains")

        older.player.worldType = WorldType.RAGING_ECHOES_LEAGUE
        older.player.runEnergy = 999
        f.drain()
        assertEquals(listOf(1, 2), f.attempts.map { it.main.runEnergy })
        assertEquals(2, f.saved(WorldType.MAIN).main.runEnergy)
        assertEquals(1, f.responses(1).size)
        assertInstanceOf(AccountSaveResponse.Success::class.java, f.responses(1).single())
        assertEquals(0, f.pendingCharacters())

        f.service.queue(older)
        f.service.queue(newer)
        val reconnect = f.request(3, WorldType.MAIN)
        f.service.queue(reconnect)
        f.drain()
        assertEquals(listOf(1, 2, 3), f.attempts.map { it.main.runEnergy })
        assertEquals(3, f.saved(WorldType.MAIN).main.runEnergy)
        assertEquals(1, f.responses(1).size)
        assertEquals(1, f.responses(2).size)
        assertEquals(1, f.responses(3).size)
        assertEquals(0, f.pendingCharacters())
    }

    @Test
    fun `older different mode retry persists its captured mode without reverting shared identity`() {
        val f = Fixture()
        val older = f.request(1, WorldType.MAIN)
        val newer = f.request(2, WorldType.RAGING_ECHOES_LEAGUE)
        f.failures[1] = 1
        f.service.queue(older)
        f.service.queue(newer)
        f.drain()
        assertEquals("identity-2", f.identities.getValue(73))
        assertFalse(f.commits.containsKey(WorldType.MAIN.key to 73))
        assertEquals(1, f.pendingCharacters())

        older.player.worldType = WorldType.RAGING_ECHOES_LEAGUE
        older.player.displayName = "live-state-must-not-be-read"
        older.player.runEnergy = 999
        f.drain()
        assertEquals(1, f.saved(WorldType.MAIN).main.runEnergy)
        assertEquals(Version(1), f.saved(WorldType.MAIN).segment)
        assertEquals(2, f.saved(WorldType.RAGING_ECHOES_LEAGUE).main.runEnergy)
        assertEquals(Version(2), f.saved(WorldType.RAGING_ECHOES_LEAGUE).segment)
        assertEquals("identity-2", f.identities.getValue(73))
        assertEquals(listOf(true, true, false), f.attempts.map { it.writeIdentity })
        assertEquals(listOf("main", "raging_echoes_league", "main"), f.attempts.map { it.schema })
        assertEquals(1, f.responses(1).size)
        assertEquals(1, f.responses(2).size)
        assertEquals(0, f.pendingCharacters())
    }

    @Test
    fun `a newer save for another character cannot suppress an older retry or its identity`() {
        val f = Fixture()
        val older = f.request(1, WorldType.MAIN)
        val other = f.request(2, WorldType.MAIN, characterId = 74)
        f.failures[1] = 1
        f.service.queue(older)
        f.service.queue(other)
        f.drain()
        assertEquals(1, f.pendingCharacters())
        f.drain()
        assertEquals(1, f.saved(WorldType.MAIN).main.runEnergy)
        assertEquals(2, f.saved(WorldType.MAIN, 74).main.runEnergy)
        assertEquals("identity-1", f.identities.getValue(73))
        assertEquals("identity-2", f.identities.getValue(74))
        assertTrue(f.attempts.all { it.writeIdentity })
        assertEquals(0, f.pendingCharacters())
    }

    @Test
    fun `exhausted retries roll back every attempt and complete once while releasing order state`() {
        val f = Fixture()
        val request = f.request(1, WorldType.MAIN)
        f.failures[1] = 4
        f.service.queue(request)
        repeat(3) {
            f.drain()
            assertTrue(f.responses(1).isEmpty())
            assertEquals(1, f.pendingCharacters())
            assertTrue(f.commits.isEmpty())
        }
        f.drain()
        assertEquals(4, f.attempts.size)
        assertTrue(f.commits.isEmpty())
        assertTrue(f.identities.isEmpty())
        assertInstanceOf(AccountSaveResponse.ExcessiveRetries::class.java, f.responses(1).single())
        assertEquals(0, f.pendingCharacters())
        f.service.queue(request)
        f.drain()
        assertEquals(4, f.attempts.size)
        assertEquals(1, f.responses(1).size)
        assertEquals(0, f.pendingCharacters())
    }

    @Test
    fun `duplicate queued request and throwing completion cannot write twice or block later saves`() {
        val f = Fixture()
        val first = f.request(1, WorldType.MAIN, throwOnCompletion = true)
        val second = f.request(2, WorldType.MAIN)
        f.service.queue(first)
        f.service.queue(first)
        f.service.queue(second)
        f.drain()
        assertEquals(listOf(1, 2), f.attempts.map { it.main.runEnergy })
        assertEquals(2, f.saved(WorldType.MAIN).main.runEnergy)
        assertEquals(1, f.responses(1).size)
        assertEquals(1, f.responses(2).size)
        assertEquals(0, f.pendingCharacters())
        f.service.queue(first)
        f.service.queue(second)
        f.drain()
        assertEquals(2, f.attempts.size)
        assertEquals(1, f.responses(1).size)
        assertEquals(1, f.responses(2).size)
        assertEquals(0, f.pendingCharacters())
    }

    private data class Version(val number: Int) : CharacterDataStage.SaveData

    private data class Write(
        val schema: String,
        val characterId: Int,
        val main: CharacterMainSaveData,
        val segment: Version,
        val writeIdentity: Boolean,
    )

    private class Fixture {
        private val database = mockk<GameDatabase>()
        private val repository = mockk<CharacterAccountRepository>()
        private val pipeline = mockk<CharacterDataStage.Pipeline>()
        private val connection = DatabaseConnection(mockk<Connection>())
        private val callbacks = mutableMapOf<Int, MutableList<AccountSaveResponse>>()
        private var stagedMain: CharacterMainSaveData? = null
        private var stagedSegment: Version? = null
        private var stagedCharacter = 0
        private var stagedIdentity = false
        val failures = mutableMapOf<Int, Int>()
        val attempts = mutableListOf<Write>()
        val commits = mutableMapOf<Pair<String, Int>, Write>()
        val identities = mutableMapOf<Int, String?>()
        val service: AccountSavingService

        init {
            every { repository.save(any(), any(), any(), any(), any()) } answers {
                stagedMain = secondArg()
                stagedCharacter = thirdArg()
                stagedIdentity = arg(4)
                Unit
            }
            every { pipeline.save(any(), any(), any()) } answers {
                assertEquals(stagedCharacter, thirdArg<Int>())
                stagedSegment = secondArg()
                Unit
            }
            coEvery { database.withSchemaTransaction<Unit>(any(), any()) } coAnswers {
                stagedMain = null
                stagedSegment = null
                val schema = firstArg<String>()
                secondArg<(DatabaseConnection) -> Unit>()(connection)
                val write = Write(schema, stagedCharacter, checkNotNull(stagedMain), checkNotNull(stagedSegment), stagedIdentity)
                attempts += write
                val remainingFailures = failures.getOrDefault(write.main.runEnergy, 0)
                if (remainingFailures > 0) {
                    failures[write.main.runEnergy] = remainingFailures - 1
                    throw SQLException("Injected transaction rollback")
                }
                commits[schema to write.characterId] = write
                if (write.writeIdentity) identities[write.characterId] = write.main.displayName
            }
            val config = mockk<ServerConfig> { every { world } returns 1 }
            service = AccountSavingService(database, repository, setOf(pipeline), config)
        }

        fun request(
            version: Int,
            worldType: WorldType,
            characterId: Int = 73,
            throwOnCompletion: Boolean = false,
        ): AccountSaveRequest {
            val player = Player().apply {
                accountId = 41
                this.characterId = characterId
                this.worldType = worldType
            }
            val main = CharacterMainSaveData(
                displayName = "identity-$version",
                members = true,
                coords = CoordGrid(3200, 3200),
                lastLogin = LocalDateTime.of(2026, 10, 30, 1, 2),
                runEnergy = version,
                xpRatePercent = 100,
                clearPresence = false,
                varps = emptyMap(),
                attrsJson = emptyMap(),
            )
            val snapshot = CharacterSaveSnapshot(41, characterId, worldType, main, mapOf(pipeline.javaClass.name to Version(version)))
            return AccountSaveRequest(player, snapshot) { response ->
                callbacks.getOrPut(version) { mutableListOf() } += response
                if (throwOnCompletion) error("Injected callback failure")
            }
        }

        fun responses(version: Int): List<AccountSaveResponse> = callbacks[version].orEmpty()

        fun saved(mode: WorldType, characterId: Int = 73): Write = commits.getValue(mode.key to characterId)

        fun drain() = runBlocking { service.run() }

        fun pendingCharacters(): Int {
            val order = service.javaClass.getDeclaredField("saveOrder").apply { isAccessible = true }.get(service)
            val pending = order.javaClass.getDeclaredField("characters").apply { isAccessible = true }.get(order) as Map<*, *>
            return pending.size
        }
    }
}
