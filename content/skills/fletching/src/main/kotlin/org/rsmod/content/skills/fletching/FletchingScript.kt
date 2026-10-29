package org.rsmod.content.skills.fletching

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.table.fletching.FletchingRecipesRow
import org.rsmod.content.skills.SkillMultiConfig
import org.rsmod.content.skills.SkillingActionType
import org.rsmod.content.skills.openSkillMulti
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class FletchingScript : PluginScript() {
    override fun ScriptContext.startup() {
        val recipes = FletchingRecipesRow.all().flatMap { FletchingRecipe.variants(it) }
        val groups = recipes.groupBy { recipe ->
            recipe.input to checkNotNull(recipe.secondary ?: recipe.tool)
        }
        for ((pair, products) in groups) {
            onOpHeldU(pair.first, pair.second) { selectFletching(products) }
        }
    }

    private suspend fun ProtectedAccess.selectFletching(recipes: List<FletchingRecipe>) {
        val first = recipes.first()
        if (first.kind == FletchingKind.PROJECTILE &&
            vars["varbit.option_fletch_dart_or_bolt_makex"] == 0
        ) {
            fletchOnce(first)?.let { reportFletchingFailure(first, it) }
            return
        }
        val byOutput = recipes.associateBy { it.product.internalName }
        val action = when (first.kind) {
            FletchingKind.CUT -> SkillingActionType.CUT
            FletchingKind.STRING -> SkillingActionType.STRING
            FletchingKind.MAKE -> SkillingActionType.MAKE
            FletchingKind.ARROW, FletchingKind.PROJECTILE -> SkillingActionType.MAKE_SETS
        }
        openSkillMulti(
            SkillMultiConfig(
                actionType = action,
                verb = if (first.kind == FletchingKind.STRING) "string" else "make",
                entries = recipes.map { it.entry },
                maxCountProvider = { inventory, entry ->
                    byOutput.getValue(entry.internal).availableSets(inventory)
                },
                eventsEndAtMaxCount = true,
            ),
        ) { selection ->
            fletchBatch(byOutput.getValue(selection.entry.internal), selection.amount)
        }
    }
}
