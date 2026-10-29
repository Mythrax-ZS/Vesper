package org.rsmod.api.account.character.stats

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.or2.sql.OpenRuneSql
import jakarta.inject.Inject
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.CharacterMetadataList
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.game.entity.Player

private typealias Stat = CharacterStatData.Stat

public class CharacterStatPipeline @Inject constructor(private val applier: CharacterStatApplier) :
    CharacterDataStage.Pipeline {
    override fun append(connection: DatabaseConnection, metadata: CharacterMetadataList) {
        val select =
            connection.prepareStatement(
                OpenRuneSql.text("game/stats/select_for_character.sql"),
            )

        val stats = ArrayList<Stat>(25)
        select.use {
            select.setInt(1, metadata.characterId)
            it.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    val statId = resultSet.getInt("stat_id")
                    val visLevel = resultSet.getInt("vis_level")
                    val baseLevel = resultSet.getInt("base_level")
                    val fineXp = resultSet.getInt("fine_xp")

                    val stat = Stat(statId, visLevel, baseLevel, fineXp)
                    stats += stat
                }
            }
        }

        metadata.add(applier, CharacterStatData(stats))
    }

    override fun capture(player: Player): CharacterStatData {
        val stats = ServerCacheManager.getStats().values.sortedBy { it.id }.map { stat ->
            val symbol = RSCM.getReverseMapping(RSCMType.STAT, stat.id)
            Stat(stat.id, player.statMap.getCurrentLevel(symbol).toInt(), player.statMap.getBaseLevel(symbol).toInt(), player.statMap.getFineXP(symbol))
        }
        return CharacterStatData(java.util.List.copyOf(stats))
    }

    override fun restore(player: Player, data: CharacterDataStage.SaveData) {
        player.statMap.clear()
        applier.apply(player, data as CharacterStatData)
    }

    override fun save(connection: DatabaseConnection, data: CharacterDataStage.SaveData, characterId: Int) {
        val stats = (data as CharacterStatData).stats
        val upsert = connection.prepareStatement(OpenRuneSql.text("game/stats/upsert_stat.sql"))
        upsert.use {
            for (stat in stats) {
                it.setInt(1, characterId)
                it.setInt(2, stat.type)
                it.setInt(3, stat.vis)
                it.setInt(4, stat.base)
                it.setInt(5, stat.fineXp)
                it.addBatch()
            }
            it.executeBatch()
        }
    }
}
