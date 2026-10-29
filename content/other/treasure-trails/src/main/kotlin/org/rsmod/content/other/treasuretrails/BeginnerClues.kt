package org.rsmod.content.other.treasuretrails

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.NpcServerType
import org.rsmod.api.enums.TreasureTrailsBeginnerEnums
import org.rsmod.api.table.cluehelper.CluehelperClueAnagramRow
import org.rsmod.api.table.cluehelper.CluehelperClueCrypticRow
import org.rsmod.api.table.cluehelper.CluehelperClueMapRow
import org.rsmod.map.CoordGrid

internal sealed interface BeginnerClue {
    val rowId: Int

    data class Map(val data: CluehelperClueMapRow, val mapInterface: String) : BeginnerClue {
        override val rowId: Int get() = data.rowId
        fun matches(coords: CoordGrid): Boolean = coords == data.target.coord
    }

    data class Anagram(val data: CluehelperClueAnagramRow) : BeginnerClue {
        override val rowId: Int get() = data.rowId
        fun matches(type: NpcServerType): Boolean = data.target.any {
            it.npc.id == type.id || it.fallbackNpc?.id == type.id
        }
    }

    data class Cryptic(val data: CluehelperClueCrypticRow, val charlie: Boolean) : BeginnerClue {
        override val rowId: Int get() = data.rowId
        val hotCold: Boolean get() = rowId == RELDO_ROW.asRSCM()
        fun matches(type: NpcServerType): Boolean = data.target.any {
            it.npc.id == type.id || it.fallbackNpc?.id == type.id
        }
    }
}

internal object BeginnerClues {
    val ALL: List<BeginnerClue> by lazy {
        val maps = TreasureTrailsBeginnerEnums.treasure_trails_beginner_maps.map { (row, mapInterface) ->
            BeginnerClue.Map(
                CluehelperClueMapRow.getRow(row.id),
                RSCM.getReverseMapping(RSCMType.INTERFACE, checkNotNull(mapInterface) {
                    "Missing native map interface for beginner clue row ${row.id}"
                }.id),
            )
        }
        val anagrams = CluehelperClueAnagramRow.all().filter { it.difficulty == 0 }.map {
            check(it.challenge == null && it.requirements == null) {
                "Beginner anagram ${it.rowId} has an unsupported requirement or challenge"
            }
            BeginnerClue.Anagram(it)
        }
        // Charlie and Reldo have separate workflows; talking alone must never solve them.
        val cryptics = listOf(
            "dbrow.cluehelper_cryptic_beginner_hans",
            "dbrow.cluehelper_cryptic_beginner_cook",
            "dbrow.cluehelper_cryptic_beginner_hunding",
            "dbrow.cluehelper_cryptic_beginner_shantay",
            "dbrow.cluehelper_cryptic_beginner_charlie_the_tramp",
            RELDO_ROW,
        ).map { symbol ->
            val row = CluehelperClueCrypticRow.getRow(symbol)
            check(row.difficulty == 0 && row.challenge == null && row.outfit == null &&
                row.requirements.isEmpty() && row.target.isNotEmpty()) {
                "Beginner cryptic ${row.rowId} has an unsupported requirement or challenge"
            }
            BeginnerClue.Cryptic(row, row.rowId == CHARLIE_ROW.asRSCM())
        }
        (maps + anagrams + cryptics).also { check(it.isNotEmpty()) }
    }

    fun get(rowId: Int): BeginnerClue? = ALL.firstOrNull { it.rowId == rowId }
}

internal const val CHARLIE_ROW = "dbrow.cluehelper_cryptic_beginner_charlie_the_tramp"
