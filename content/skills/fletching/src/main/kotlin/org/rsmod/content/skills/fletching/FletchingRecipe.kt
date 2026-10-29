package org.rsmod.content.skills.fletching

import dev.openrune.types.ItemServerType
import org.rsmod.api.table.fletching.FletchingRecipesRow
import org.rsmod.content.skills.Material
import org.rsmod.content.skills.SkillMultiEntry
import org.rsmod.game.inv.Inventory

internal enum class FletchingKind {
    CUT,
    STRING,
    ARROW,
    PROJECTILE,
    MAKE,
}

internal class FletchingRecipe(
    val data: FletchingRecipesRow,
    secondaryOverride: ItemServerType? = null,
) {
    val kind = FletchingKind.valueOf(data.kind)
    val input = data.input
    val secondary = secondaryOverride ?: data.secondary
    val tool = data.tool
    val product = data.product
    val entry = SkillMultiEntry(
        product.internalName,
        listOfNotNull(
            Material(input.internalName, data.inputCount),
            secondary?.let { Material(it.internalName, data.secondaryCount) },
        ),
    )

    init {
        require(data.batchSize in 1..15)
        require(data.productCount > 0 && data.productCount <= Int.MAX_VALUE / data.batchSize)
        require(data.inputCount > 0 && data.inputCount <= Int.MAX_VALUE / data.batchSize)
        require(data.secondaryCount > 0 && data.secondaryCount <= Int.MAX_VALUE / data.batchSize)
        require(data.level in 1..99 && data.xpTenths > 0)
        require(data.firstTicks > 0 && data.repeatTicks > 0)
        require(secondary != null || tool != null)
        require(input.id != secondary?.id && input.id != product.id)
        require(secondary?.id != product.id)
        require(tool?.id != input.id && tool?.id != secondary?.id && tool?.id != product.id)
        require(secondaryOverride == null || data.secondaryAlternatives.any { it.id == secondaryOverride.id })
        require(data.secondaryAlternatives.isEmpty() || data.secondary != null)
    }

    fun availableUnits(inventory: Inventory): Int {
        val first = inventory.physicalCount(input.internalName) / data.inputCount
        return secondary?.let {
            minOf(first, inventory.physicalCount(it.internalName) / data.secondaryCount)
        } ?: first
    }

    fun availableSets(inventory: Inventory): Int {
        val units = availableUnits(inventory)
        val sets = (units.toLong() + data.batchSize - 1) / data.batchSize
        val limit = if (data.batchSize > 1) MAX_PROJECTILE_SETS else inventory.size
        return sets.coerceAtMost(limit.toLong()).toInt()
    }

    companion object {
        private const val MAX_PROJECTILE_SETS = 10

        fun variants(data: FletchingRecipesRow): List<FletchingRecipe> =
            listOf(FletchingRecipe(data)) + data.secondaryAlternatives.map { FletchingRecipe(data, it) }
    }
}
