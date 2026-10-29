package org.rsmod.api.game.process.npc.hunt

import dev.openrune.ServerCacheManager
import dev.openrune.types.HuntModeType
import dev.openrune.types.NpcServerType
import dev.openrune.types.hunt.HuntCondition
import dev.openrune.types.hunt.HuntType
import dev.openrune.types.hunt.HuntVis
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.rsmod.annotations.InternalApi
import org.rsmod.api.hunt.Hunt
import org.rsmod.api.npc.aggression.AggressionTolerance
import org.rsmod.api.random.GameRandom
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcUid
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.inv.InvObj
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.obj.Obj
import org.rsmod.map.CoordGrid

@OptIn(InternalApi::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class NpcHuntSelectionTest {
    @ParameterizedTest
    @EnumSource(TargetKind::class)
    fun `a single eligible candidate is always selected even at the maximum roll`(kind: TargetKind) {
        val fixture = Fixture(kind)
        assertEquals(fixture.eligible[0].key, fixture.select(listOf(fixture.eligible[0])))
        assertEquals(listOf(0..0), fixture.random.bounds)
    }

    @ParameterizedTest
    @EnumSource(TargetKind::class)
    fun `an empty candidate sequence produces no target or random draw`(kind: TargetKind) {
        val fixture = Fixture(kind)
        assertNull(fixture.select(emptyList()))
        assertTrue(fixture.random.bounds.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(TargetKind::class)
    fun `ineligible candidates never enter the reservoir`(kind: TargetKind) {
        val fixture = Fixture(kind)
        fixture.filterCategory()
        val chosen = fixture.select(listOf(fixture.excluded, fixture.eligible[0], fixture.excluded))
        assertEquals(fixture.eligible[0].key, chosen)
        assertEquals(listOf(0..0), fixture.random.bounds)

        assertNull(fixture.select(listOf(fixture.excluded, fixture.excluded)))
        assertTrue(fixture.random.bounds.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(TargetKind::class)
    fun `each of three eligible targets wins exactly one third of all roll combinations`(kind: TargetKind) {
        val fixture = Fixture(kind)
        fixture.filterCategory()
        val candidates = listOf(
            fixture.excluded,
            fixture.eligible[0],
            fixture.eligible[1],
            fixture.excluded,
            fixture.eligible[2],
        )
        val outcomes = mutableListOf<Any?>()
        for (secondRoll in 0..1) {
            for (thirdRoll in 0..2) {
                outcomes += fixture.select(candidates, listOf(0, secondRoll, thirdRoll))
                assertEquals(listOf(0..0, 0..1, 0..2), fixture.random.bounds)
            }
        }

        assertEquals(fixture.eligible.associate { it.key to 2 }, outcomes.groupingBy { it }.eachCount())
    }

    @ParameterizedTest
    @EnumSource(TargetKind::class, names = ["Npc", "Obj", "Loc"])
    fun `identity checks exclude other types within the same category`(kind: TargetKind) {
        val fixture = Fixture(kind)
        fixture.filter(id = fixture.eligible[0].id)
        val chosen = fixture.select(listOf(fixture.otherType, fixture.eligible[0], fixture.excluded))
        assertEquals(fixture.eligible[0].key, chosen)
        assertEquals(listOf(0..0), fixture.random.bounds)
    }

    @ParameterizedTest
    @EnumSource(TargetKind::class, names = ["Npc", "Obj", "Loc"])
    fun `identity and category requirements must both match`(kind: TargetKind) {
        val fixture = Fixture(kind)
        fixture.filter(id = fixture.eligible[0].id, category = fixture.eligible[0].category)
        assertEquals(fixture.eligible[0].key, fixture.select(listOf(fixture.otherType, fixture.eligible[0])))
        assertEquals(listOf(0..0), fixture.random.bounds)

        fixture.filter(id = fixture.eligible[0].id, category = fixture.excluded.category)
        assertNull(fixture.select(fixture.eligible + fixture.excluded))
        assertTrue(fixture.random.bounds.isEmpty())
    }

    @Test
    fun `player visibility tolerance and busy filters remain independent of selection`() {
        val fixture = Fixture(TargetKind.Player)
        val hidden = fixture.eligible[0].value as Player
        val busy = fixture.eligible[1].value as Player
        hidden.hidden = true
        busy.delay = busy.currentMapClock + 1
        fixture.mode.checkNotBusy = true
        assertEquals(fixture.eligible[2].key, fixture.select(fixture.eligible + fixture.excluded))
        assertEquals(listOf(0..0), fixture.random.bounds)

        fixture.mode.checkNotBusy = false
        assertEquals(fixture.eligible[1].key, fixture.select(listOf(fixture.excluded, fixture.eligible[1])))
        assertEquals(listOf(0..0), fixture.random.bounds)
    }

    enum class TargetKind {
        Npc, Obj, Loc, Player
    }

    private data class TypeInfo(val id: Int, val category: Int)

    private data class Candidate(val value: Any, val key: Any, val id: Int, val category: Int)

    private class Fixture(private val kind: TargetKind) {
        val random = SelectionRandom()
        private val clock = MapClock(10)
        private val hunt = mockk<Hunt>()
        private val tolerance = mockk<AggressionTolerance>()
        private val source = Npc(NpcServerType(id = 1, huntRange = HUNT_RANGE), SOURCE_COORDS)
        private var candidates: List<Candidate> = emptyList()
        private val processor = NpcHuntProcessor(
            random, clock, hunt, PlayerList(), NpcList(), mockk(), mockk(), mockk(), mockk()
        )
        private val playerProcessor = NpcPlayerHuntProcessor(random, clock, hunt, tolerance)
        val mode = HuntModeType(type = when (kind) {
            TargetKind.Npc -> HuntType.Npc
            TargetKind.Obj -> HuntType.Obj
            TargetKind.Loc -> HuntType.Scenery
            TargetKind.Player -> HuntType.Player
        })
        val eligible: List<Candidate>
        val excluded: Candidate
        val otherType: Candidate

        init {
            if (kind == TargetKind.Player) {
                eligible = (1..3).map { player(it) }
                excluded = player(4)
                otherType = player(5)
                every { tolerance.isTolerant(source, any()) } answers { secondArg<Player>() === excluded.value }
            } else {
                val types = when (kind) {
                    TargetKind.Npc -> ServerCacheManager.getNpcs().values.map { TypeInfo(it.id, it.category) }
                    TargetKind.Obj -> ServerCacheManager.getItems().values.map { TypeInfo(it.id, it.category) }
                    TargetKind.Loc -> ServerCacheManager.getObjects().values.map { TypeInfo(it.id, it.category) }
                    TargetKind.Player -> error("Handled above")
                }
                val sameCategory = types.filter { it.category >= 0 && it.id != it.category }
                    .groupBy { it.category }.values.first { it.size >= 2 }
                val type = sameCategory.first()
                eligible = (1..3).map { candidate(type, it) }
                otherType = candidate(sameCategory[1], 4)
                excluded = candidate(types.first { it.category != type.category }, 5)
            }
            every { hunt.findNpcs(SOURCE_COORDS, HUNT_RANGE, HuntVis.Off) } answers {
                candidates.asSequence().map { it.value as Npc }
            }
            every { hunt.findObjs(SOURCE_COORDS, HUNT_RANGE, HuntVis.Off) } answers {
                candidates.asSequence().map { it.value as Obj }
            }
            every { hunt.findLocs(SOURCE_COORDS, HUNT_RANGE, HuntVis.Off) } answers {
                candidates.asSequence().map { it.value as LocInfo }
            }
            every { hunt.findPlayers(SOURCE_COORDS, HUNT_RANGE, HuntVis.Off) } answers {
                candidates.asSequence().map { it.value as Player }
            }
        }

        fun filterCategory() = filter(category = eligible[0].category)

        fun filter(id: Int? = null, category: Int? = null) {
            when (kind) {
                TargetKind.Npc -> mode.checkNpc = HuntCondition.NpcCondition(id, category)
                TargetKind.Obj -> mode.checkObj = HuntCondition.ObjCondition(id, category)
                TargetKind.Loc -> mode.checkLoc = HuntCondition.LocCondition(id, category)
                TargetKind.Player -> Unit
            }
        }

        fun select(candidates: List<Candidate>, rolls: List<Int>? = null): Any? {
            this.candidates = candidates
            random.reset(rolls)
            source.resetHunt()
            val owner = if (kind == TargetKind.Player) playerProcessor else processor
            val method = owner.javaClass.getDeclaredMethod("hunt$kind", Npc::class.java, HuntModeType::class.java)
            method.isAccessible = true
            method.invoke(owner, source, mode)
            random.assertConsumed()
            return when (kind) {
                TargetKind.Npc -> source.huntNpc.takeUnless { it == NpcUid.NULL }
                TargetKind.Obj -> source.huntObj
                TargetKind.Loc -> source.huntLoc
                TargetKind.Player -> source.huntPlayer.takeUnless { it == PlayerUid.NULL }
            }
        }

        private fun candidate(type: TypeInfo, slot: Int): Candidate {
            val coords = SOURCE_COORDS.translate(slot, 0)
            val value: Any = when (kind) {
                TargetKind.Npc -> Npc(checkNotNull(ServerCacheManager.getNpc(type.id)), coords).apply {
                    slotId = slot
                    assignUid()
                }
                TargetKind.Obj -> Obj.fromServer(clock, coords, InvObj(checkNotNull(ServerCacheManager.getItem(type.id)), 1))
                TargetKind.Loc -> LocInfo(2, coords, LocEntity(type.id, LocShape.CentrepieceStraight.id, 0))
                TargetKind.Player -> error("Use player factory")
            }
            return Candidate(value, if (value is Npc) value.uid else value, type.id, type.category)
        }

        private fun player(slot: Int): Candidate {
            val value = Player().apply {
                slotId = slot
                uuid = slot.toLong()
                assignUid()
                currentMapClock = clock.cycle
                processedMapClock = clock.cycle
            }
            return Candidate(value, value.uid, slot, -1)
        }
    }

    private class SelectionRandom : GameRandom {
        val bounds = mutableListOf<IntRange>()
        private var rolls: ArrayDeque<Int>? = null

        fun reset(rolls: List<Int>?) {
            bounds.clear()
            this.rolls = rolls?.let { ArrayDeque(it) }
        }

        fun assertConsumed() {
            assertTrue(rolls.isNullOrEmpty(), "Every supplied roll must be consumed")
        }

        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            val range = minInclusive..maxInclusive
            bounds += range
            val roll = rolls?.removeFirst() ?: maxInclusive
            assertTrue(roll in range, "Roll $roll is outside $range")
            return roll
        }

        override fun of(maxExclusive: Int): Int = error("Unexpected exclusive roll")

        override fun randomDouble(): Double = error("Unexpected double roll")
    }

    companion object {
        private const val HUNT_RANGE = 5
        private val SOURCE_COORDS = CoordGrid(3200, 3200)

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            ServerCacheManager.init(241).close()
        }
    }
}
