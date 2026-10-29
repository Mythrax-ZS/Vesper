package org.rsmod.content.skills.fletching

import org.rsmod.api.invtx.add
import org.rsmod.api.invtx.delete
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.fletchingLvl
import org.rsmod.content.quest.manager.Quest
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.content.slayer.core.SlayerTaskManager
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.ProtectedAccessLostException

internal enum class FletchingFailure {
    LEVEL,
    SLAYER_UNLOCK,
    QUEST,
    TOOL,
    MATERIALS,
    SPACE,
}

internal fun Player.fletchingFailure(recipe: FletchingRecipe): FletchingFailure? = when {
    fletchingLvl < recipe.data.level -> FletchingFailure.LEVEL
    recipe.data.slayerUnlock?.let { !SlayerTaskManager.hasUnlockedReward(this, it.bit) } == true ->
        FletchingFailure.SLAYER_UNLOCK
    recipe.data.quest.isNotEmpty() && !QuestRequirements.hasCompleted(this, recipe.data.quest) ->
        FletchingFailure.QUEST
    recipe.tool?.let { inv.physicalCount(it.internalName) == 0 } == true -> FletchingFailure.TOOL
    recipe.availableUnits(inv) == 0 -> FletchingFailure.MATERIALS
    else -> null
}

internal fun ProtectedAccess.fletchOnce(recipe: FletchingRecipe): FletchingFailure? {
    player.fletchingFailure(recipe)?.let { return it }
    val units = minOf(recipe.data.batchSize, recipe.availableUnits(inv))
    val result = player.invTransaction(inv) {
        val inventory = select(inv)
        delete(inventory, recipe.input.id, units * recipe.data.inputCount)
        recipe.secondary?.let { delete(inventory, it.id, units * recipe.data.secondaryCount) }
        add(inventory, recipe.product.id, units * recipe.data.productCount)
    }
    if (result.failure) return FletchingFailure.SPACE
    statAdvance("stat.fletching", recipe.data.xpTenths.toDouble() * units / 10.0)
    return null
}

internal suspend fun ProtectedAccess.fletchBatch(recipe: FletchingRecipe, requestedSets: Int) {
    val sets = requestedSets.coerceIn(0, recipe.availableSets(inv))
    if (sets == 0) return
    ifCloseChat()
    try {
        repeat(sets) { cycle ->
            player.fletchingFailure(recipe)?.let {
                reportFletchingFailure(recipe, it)
                return
            }
            anim(recipe.data.animation.internalName)
            waitFletchingTicks(if (cycle == 0) recipe.data.firstTicks else recipe.data.repeatTicks)
            fletchOnce(recipe)?.let {
                reportFletchingFailure(recipe, it)
                return
            }
        }
    } finally {
        resetAnim()
    }
}

private suspend fun ProtectedAccess.waitFletchingTicks(ticks: Int) {
    val finish = player.currentMapClock + ticks
    coroutine.pause { player.processedMapClock >= finish }
    if (player.activeCoroutine !== coroutine || player.isBusy) {
        throw ProtectedAccessLostException()
    }
}

internal fun ProtectedAccess.reportFletchingFailure(
    recipe: FletchingRecipe,
    failure: FletchingFailure,
) {
    val message = when (failure) {
        FletchingFailure.LEVEL ->
            "You need a Fletching level of ${recipe.data.level} to make that."
        FletchingFailure.SLAYER_UNLOCK ->
            "You need to unlock ${recipe.data.slayerUnlock?.name} from a Slayer master first."
        FletchingFailure.QUEST ->
            "You need to complete ${Quest.get(recipe.data.quest)?.displayName ?: "the required quest"} to make that."
        FletchingFailure.TOOL -> "You need a ${recipe.tool?.name?.lowercase()} to make that."
        FletchingFailure.MATERIALS -> "You don't have enough materials to make that."
        FletchingFailure.SPACE -> "You don't have enough inventory space to make that."
    }
    mes(message)
}
