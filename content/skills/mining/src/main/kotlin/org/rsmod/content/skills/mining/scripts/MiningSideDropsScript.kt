package org.rsmod.content.skills.mining.scripts

import jakarta.inject.Inject
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.area.checker.isInWilderness
import org.rsmod.api.config.constants
import org.rsmod.api.droptable.wearingImbuedRingOfWealth
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.events.skilling.SkillingActionCompleteEvent
import org.rsmod.api.player.events.skilling.SkillingActionContext
import org.rsmod.api.player.events.skilling.SkillingProductSource
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.miningLvl
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.table.mining.MiningClueGeodesRow
import org.rsmod.api.table.mining.MiningRocksRow
import org.rsmod.content.other.treasuretrails.ClueDropPolicy
import org.rsmod.content.other.treasuretrails.ClueOwnership
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.content.skills.mining.configs.isGemRock
import org.rsmod.content.skills.mining.miningGeodeDenominator
import org.rsmod.game.entity.Player
import org.rsmod.game.obj.Obj
import org.rsmod.map.square.MapSquareKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class MiningSideDropsScript
@Inject
constructor(
    private val random: GameRandom,
    private val objRepo: ObjRepository,
    private val areaChecker: AreaChecker,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onEvent<SkillingActionCompleteEvent> { complete(this) }
    }

    internal fun complete(event: SkillingActionCompleteEvent) {
        val product = event.context as? SkillingActionContext.Product ?: return
        if (product.skill != "stat.mining" || product.count <= 0) return
        val source = product.source as? SkillingProductSource.Mining ?: return
        // Random gems from ordinary rocks replace the ore-success attempt. A gem-rock resource is
        // a successful mining action, but the ordinary mining gem pre-roll is not.
        if (!source.rockData.isGemRock && product.item.startsWith("obj.uncut_")) return
        if (!product.isBonus) rollClueGeodes(event.player, source.rockData)
        // Enhancers publish a separate successful product event. Their extra resources also roll
        // for shards; do not let the clue rule above suppress this independent reward.
        if (MapSquareKey.from(source.rock.coords).id == TRAHAEARN_REGION) {
            repeat(product.count) { rollCrystalShard(event.player) }
        }
    }

    internal fun rollClueGeodes(player: Player, rock: MiningRocksRow) {
        if (rock.clueBaseChance <= 0 || player.vars["varbit.vesseled_clues_disabled"] != 0) return
        val boxes = QuestRequirements.hasCompleted(player, "quest_xmarksthespot")
        val wilderness = player.wearingImbuedRingOfWealth() && player.coords.isInWilderness(areaChecker)
        for (row in MiningClueGeodesRow.all()) {
            val reward = if (boxes) row.box else row.geode
            val symbol = reward.internalName
            if (!ClueDropPolicy.mayDrop(player, symbol)) continue
            val tier = checkNotNull(ClueOwnership.tier(symbol))
            val atCapacity = ClueOwnership.count(player, tier) >= ClueOwnership.capacity(player, tier)
            // Legacy vessels cannot accumulate past capacity even if a customised quest policy
            // changes back from completed to incomplete while boxes are still owned.
            if (!boxes && atCapacity) continue
            val ca = row.combatReward.isNotEmpty() && player.vars[row.combatReward] == 2
            val denominator = miningGeodeDenominator(
                rock.clueBaseChance, player.miningLvl, row.multiplierTenths,
                combatReward = ca, wildernessBonus = wilderness && row.wildernessBonus,
            )
            if (random.of(denominator) != 0) continue
            val inInventory = award(player, symbol, groundOnly = atCapacity)
            val name = if (boxes) "scroll box" else "clue geode"
            player.mes(
                if (inInventory) "You find a $name!"
                else "You find a $name, which falls to the ground.",
            )
        }
    }

    private fun rollCrystalShard(player: Player) {
        if (random.of(CRYSTAL_SHARD_DENOMINATOR) != 0) return
        val inInventory = award(player, "obj.prif_crystal_shard")
        player.mes(
            if (inInventory) "You have obtained a crystal shard!"
            else "You have obtained a crystal shard, which falls to the ground.",
        )
    }

    /** Each successful roll reaches exactly one destination, including full or overflowing stacks. */
    private fun award(player: Player, symbol: String, groundOnly: Boolean = false): Boolean {
        if (!groundOnly && player.invAdd(player.inv, symbol).success) return true
        val obj = Obj.fromPersonal(player, player.coords, symbol, 1)
        val duration = player.lootDropDuration ?: constants.lootdrop_duration
        check(objRepo.add(obj, duration)) { "Mining reward could not be dropped: $symbol at ${player.coords}" }
        return false
    }

    private companion object {
        private const val TRAHAEARN_REGION = 13250
        private const val CRYSTAL_SHARD_DENOMINATOR = 65
    }
}
