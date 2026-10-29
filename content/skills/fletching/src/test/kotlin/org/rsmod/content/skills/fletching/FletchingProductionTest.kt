package org.rsmod.content.skills.fletching

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.table.QuestRow
import org.rsmod.api.table.fletching.FletchingRecipesRow
import org.rsmod.api.table.slayer.SlayerUnlockRow
import org.rsmod.content.quest.area.falador.knightssword.KnightsSwordQuest
import org.rsmod.content.quest.manager.Quest
import org.rsmod.content.quest.manager.QuestRequirementPolicy
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.content.slayer.core.SlayerTaskManager
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class FletchingProductionTest {
    @Test
    fun `cutting replaces a log in a full inventory without consuming the knife`() {
        val f = Fixture()
        f.fill()
        f.put(0, "obj.knife")
        f.put(1, "obj.logs")
        f.start("shafts_logs", 1)
        f.advance(1)
        f.assertCount("obj.logs", 1)
        assertEquals(0, f.xp())
        f.advance(1)
        f.assertCount("obj.knife", 1)
        f.assertCount("obj.logs", 0)
        f.assertCount("obj.arrow_shaft", 15)
        f.assertCount("obj.bronze_dagger", 26)
        assertEquals(50, f.xp())
        assertTrue(f.finished())
    }

    @Test
    fun `stringing consumes one of each ingredient and repeated clicks cannot duplicate a bow`() {
        val f = Fixture()
        f.fill()
        f.put(0, "obj.unstrung_magic_longbow")
        f.put(1, "obj.bow_string")
        assertNull(f.once("string_magic_longbow"))
        val after = f.snapshot()
        repeat(2) { assertEquals(FletchingFailure.MATERIALS, f.once("string_magic_longbow")) }
        assertEquals(after, f.snapshot())
        f.assertCount("obj.magic_longbow", 1)
        f.assertCount("obj.unstrung_magic_longbow", 0)
        f.assertCount("obj.bow_string", 0)
        f.assertCount("obj.bronze_dagger", 26)
        assertEquals(915, f.xp())
    }

    @Test
    fun `no space for a new projectile stack rolls back both ingredient removals and XP`() {
        val f = Fixture()
        f.fill()
        f.put(0, "obj.feather", 31)
        f.put(1, "obj.arrow_shaft", 31)
        val before = f.snapshot()
        repeat(2) { assertEquals(FletchingFailure.SPACE, f.once("headless_arrow")) }
        assertEquals(before, f.snapshot())
        assertEquals(0, f.xp())
        f.player.inv[2] = null
        assertNull(f.once("headless_arrow"))
        f.assertCount("obj.headless_arrow", 15)
        f.assertCount("obj.feather", 16)
        f.assertCount("obj.arrow_shaft", 16)
        assertEquals(150, f.xp())
    }

    @Test
    fun `projectile stack overflow rolls back and the exact maximum succeeds once`() {
        val f = Fixture()
        f.put(0, "obj.bronze_dart_tip", 30)
        f.put(1, "obj.feather", 30)
        f.put(2, "obj.bronze_dart", Int.MAX_VALUE - 9)
        val before = f.snapshot()
        assertEquals(FletchingFailure.SPACE, f.once("dart_bronze"))
        assertEquals(before, f.snapshot())
        assertEquals(0, f.xp())
        f.put(2, "obj.bronze_dart", Int.MAX_VALUE - 10)
        assertNull(f.once("dart_bronze"))
        val after = f.snapshot()
        assertEquals(FletchingFailure.SPACE, f.once("dart_bronze"))
        assertEquals(after, f.snapshot())
        f.assertCount("obj.bronze_dart", Int.MAX_VALUE)
        f.assertCount("obj.feather", 20)
        f.assertCount("obj.bronze_dart_tip", 20)
        assertEquals(180, f.xp())
    }

    @Test
    fun `same tick manual dart clicks consume remaining tips once and award proportional XP`() {
        val f = Fixture()
        f.put(0, "obj.bronze_dart_tip", 17)
        f.put(1, "obj.feather", 30)
        assertNull(f.once("dart_bronze"))
        assertNull(f.once("dart_bronze"))
        repeat(3) { assertEquals(FletchingFailure.MATERIALS, f.once("dart_bronze")) }
        f.assertCount("obj.bronze_dart", 17)
        f.assertCount("obj.feather", 13)
        f.assertCount("obj.bronze_dart_tip", 0)
        assertEquals(306, f.xp())
    }

    @Test
    fun `final incomplete arrow set consumes only the available matching components`() {
        val f = Fixture()
        f.put(0, "obj.headless_arrow", 20)
        f.put(1, "obj.bronze_arrowheads", 17)
        f.start("arrow_bronze", Int.MAX_VALUE)
        f.advance(4)
        f.assertCount("obj.bronze_arrow", 17)
        f.assertCount("obj.headless_arrow", 3)
        f.assertCount("obj.bronze_arrowheads", 0)
        assertEquals(221, f.xp())
        assertTrue(f.finished())
    }

    @Test
    fun `a full inventory can make bolts when the consumed feathers release a slot`() {
        val f = Fixture()
        f.fill()
        f.put(0, "obj.xbows_crossbow_bolts_runite_unfeathered", 100)
        f.put(1, "obj.feather", 10)
        assertNull(f.once("bolt_runite"))
        f.assertCount("obj.xbows_crossbow_bolts_runite", 10)
        f.assertCount("obj.xbows_crossbow_bolts_runite_unfeathered", 90)
        f.assertCount("obj.feather", 0)
        assertEquals(1000, f.xp())
    }

    @Test
    fun `materials vanishing during the animation grant neither product nor XP`() {
        val f = Fixture()
        f.put(0, "obj.unstrung_shortbow")
        f.put(1, "obj.bow_string")
        f.start("string_shortbow", 1)
        f.player.inv[1] = null
        val before = f.snapshot()
        f.advance(2)
        assertEquals(before, f.snapshot())
        assertEquals(0, f.xp())
        assertTrue(f.finished())
    }

    @Test
    fun `space disappearing during the animation cannot destroy either input`() {
        val f = Fixture()
        f.put(0, "obj.feather", 30)
        f.put(1, "obj.arrow_shaft", 30)
        f.start("headless_arrow", 2)
        for (slot in 2 until f.player.inv.size) f.put(slot, "obj.bronze_dagger")
        val before = f.snapshot()
        f.advance(2)
        assertEquals(before, f.snapshot())
        assertEquals(0, f.xp())
        assertTrue(f.finished())
    }

    @Test
    fun `cutting waits two ticks initially and three ticks between subsequent logs`() {
        val f = Fixture()
        f.put(0, "obj.knife")
        f.put(1, "obj.logs")
        f.put(2, "obj.logs")
        f.start("cut_shortbow", 2)
        f.advance(2)
        f.assertCount("obj.unstrung_shortbow", 1)
        f.advance(2)
        f.assertCount("obj.unstrung_shortbow", 1)
        f.advance(1)
        f.assertCount("obj.unstrung_shortbow", 2)
        assertEquals(100, f.xp())
        assertTrue(f.finished())
    }

    @Test
    fun `movement clear pending action preserves unfinished ingredients without future XP`() {
        val f = Fixture()
        f.put(0, "obj.knife")
        f.put(1, "obj.logs")
        f.put(2, "obj.logs")
        f.start("cut_shortbow", 2)
        f.advance(2)
        val before = f.snapshot()
        f.cancel()
        f.advance(5)
        assertEquals(before, f.snapshot())
        assertEquals(50, f.xp())
        assertTrue(f.finished())
    }

    @Test
    fun `tool and boosted level requirements are rechecked before consuming logs`() {
        val tool = Fixture()
        tool.put(0, "obj.knife")
        tool.put(1, "obj.magic_logs")
        tool.start("cut_magic_shortbow", 1)
        tool.player.inv[0] = null
        val beforeTool = tool.snapshot()
        tool.advance(2)
        assertEquals(beforeTool, tool.snapshot())
        assertEquals(0, tool.xp())

        val level = Fixture()
        level.player.statMap.setCurrentLevel("stat.fletching", 80)
        level.put(0, "obj.knife")
        level.put(1, "obj.magic_logs")
        level.start("cut_magic_shortbow", 1)
        level.player.statMap.setCurrentLevel("stat.fletching", 79)
        val beforeLevel = level.snapshot()
        level.advance(2)
        assertEquals(beforeLevel, level.snapshot())
        assertEquals(0, level.xp())
    }

    @Test
    fun `maximum input stacks cannot overflow the make all calculation or exceed ten sets`() {
        val f = Fixture()
        f.put(0, "obj.arrow_shaft", Int.MAX_VALUE)
        f.put(1, "obj.feather", Int.MAX_VALUE)
        f.start("headless_arrow", Int.MAX_VALUE)
        f.advance(20)
        assertTrue(f.finished())
        f.assertCount("obj.headless_arrow", 150)
        f.assertCount("obj.arrow_shaft", Int.MAX_VALUE - 150)
        f.assertCount("obj.feather", Int.MAX_VALUE - 150)
        assertEquals(1500, f.xp())
    }

    @Test
    fun `shields consume two logs per product and preserve an odd remaining log`() {
        val f = Fixture()
        f.fill()
        f.put(0, "obj.knife")
        for (slot in 1..5) f.put(slot, "obj.maple_logs")
        f.start("shield_maple", Int.MAX_VALUE)
        f.advance(6)
        f.assertCount("obj.maple_shield", 0)
        f.advance(1)
        f.assertCount("obj.maple_shield", 1)
        f.assertCount("obj.maple_logs", 3)
        assertEquals(1165, f.xp())
        f.advance(6)
        f.assertCount("obj.maple_shield", 1)
        f.advance(1)
        f.assertCount("obj.maple_shield", 2)
        f.assertCount("obj.maple_logs", 1)
        f.assertCount("obj.knife", 1)
        f.assertCount("obj.bronze_dagger", 22)
        assertEquals(2330, f.xp())
        assertTrue(f.finished())
        val before = f.snapshot()
        assertEquals(FletchingFailure.MATERIALS, f.once("shield_maple"))
        assertEquals(before, f.snapshot())
    }

    @Test
    fun `losing the second shield log or cancelling its seven tick action preserves remaining logs`() {
        for (cancel in listOf(false, true)) {
            val f = Fixture()
            f.put(0, "obj.knife")
            f.put(1, "obj.oak_logs")
            f.put(2, "obj.oak_logs")
            f.start("shield_oak", 1)
            f.advance(3)
            if (cancel) f.cancel() else f.player.inv[2] = null
            val before = f.snapshot()
            f.advance(8)
            assertEquals(before, f.snapshot())
            assertEquals(0, f.xp())
            assertTrue(f.finished())
        }
    }

    @Test
    fun `pearl and gemstone cuts produce six twelve or twenty four tips per input with one XP award`() {
        for ((key, input, output, count, xp) in listOf(
            GemCut("pearl", "smalloysterpearls", "pearl_bolttips", 6, 32),
            GemCut("pearls", "bigoysterpearls", "pearl_bolttips", 24, 32),
            GemCut("sapphire", "sapphire", "xbows_bolt_tips_sapphire", 12, 40),
            GemCut("onyx", "onyx", "xbows_bolt_tips_onyx", 24, 94),
        )) {
            val f = Fixture()
            f.fill()
            f.put(0, "obj.chisel")
            f.put(1, "obj.$input")
            f.start("cut_tips_$key", 1)
            f.advance(4)
            f.assertCount("obj.$input", 1)
            f.advance(1)
            f.assertCount("obj.$input", 0)
            f.assertCount("obj.$output", count)
            f.assertCount("obj.chisel", 1)
            f.assertCount("obj.bronze_dagger", 26)
            assertEquals(xp, f.xp())
        }
    }

    @Test
    fun `gem yield overflow rolls back the gem and succeeds at the exact stack boundary`() {
        val f = Fixture()
        f.put(0, "obj.chisel")
        f.put(1, "obj.onyx")
        f.put(2, "obj.xbows_bolt_tips_onyx", Int.MAX_VALUE - 23)
        val before = f.snapshot()
        assertEquals(FletchingFailure.SPACE, f.once("cut_tips_onyx"))
        assertEquals(before, f.snapshot())
        assertEquals(0, f.xp())
        f.put(2, "obj.xbows_bolt_tips_onyx", Int.MAX_VALUE - 24)
        assertNull(f.once("cut_tips_onyx"))
        f.assertCount("obj.xbows_bolt_tips_onyx", Int.MAX_VALUE)
        f.assertCount("obj.onyx", 0)
        f.assertCount("obj.chisel", 1)
        assertEquals(94, f.xp())
    }

    @Test
    fun `dragon bolt tipping consumes a partial final set and cannot consume the tips twice`() {
        val f = Fixture()
        f.put(0, "obj.dragon_bolts", 17)
        f.put(1, "obj.xbows_bolt_tips_ruby", 13)
        f.start("tip_dragon_bolt_ruby", 2)
        f.advance(2)
        f.assertCount("obj.dragon_bolts_unenchanted_ruby", 10)
        f.advance(2)
        f.assertCount("obj.dragon_bolts_unenchanted_ruby", 13)
        f.assertCount("obj.dragon_bolts", 4)
        f.assertCount("obj.xbows_bolt_tips_ruby", 0)
        assertEquals(819, f.xp())
        val before = f.snapshot()
        repeat(2) { assertEquals(FletchingFailure.MATERIALS, f.once("tip_dragon_bolt_ruby")) }
        assertEquals(before, f.snapshot())
    }

    @Test
    fun `javelins complete the first set at one tick then repeat every two ticks`() {
        val f = Fixture()
        f.put(0, "obj.javelin_shaft", 45)
        f.put(1, "obj.rune_javelin_head", 45)
        f.start("javelin_rune", 3)
        f.assertCount("obj.rune_javelin", 0)
        f.advance(1)
        f.assertCount("obj.rune_javelin", 15)
        f.advance(1)
        f.assertCount("obj.rune_javelin", 15)
        f.advance(1)
        f.assertCount("obj.rune_javelin", 30)
        f.cancel()
        f.advance(5)
        f.assertCount("obj.rune_javelin", 30)
        f.assertCount("obj.javelin_shaft", 15)
        f.assertCount("obj.rune_javelin_head", 15)
        assertEquals(3720, f.xp())
    }

    @Test
    fun `crossbow assembly requires and retains the hammer and stringing consumes the string`() {
        val f = Fixture()
        f.put(0, "obj.xbows_crossbow_stock_magic")
        f.put(1, "obj.xbows_crossbow_limbs_dragon")
        val before = f.snapshot()
        assertEquals(FletchingFailure.TOOL, f.once("assemble_crossbow_dragon"))
        assertEquals(before, f.snapshot())
        f.put(2, "obj.hammer")
        f.start("assemble_crossbow_dragon", 1)
        f.advance(1)
        f.assertCount("obj.xbows_crossbow_unstrung_dragon", 0)
        f.advance(1)
        f.assertCount("obj.xbows_crossbow_unstrung_dragon", 1)
        f.assertCount("obj.xbows_crossbow_stock_magic", 0)
        f.assertCount("obj.xbows_crossbow_limbs_dragon", 0)
        f.assertCount("obj.hammer", 1)
        assertEquals(1350, f.xp())
        f.put(1, "obj.xbows_crossbow_string")
        assertNull(f.once("string_crossbow_dragon"))
        f.assertCount("obj.xbows_crossbow_dragon", 1)
        f.assertCount("obj.xbows_crossbow_unstrung_dragon", 0)
        f.assertCount("obj.xbows_crossbow_string", 0)
        f.assertCount("obj.hammer", 1)
        assertEquals(2050, f.xp())
    }

    @Test
    fun `losing the hammer during crossbow assembly preserves both consumed ingredients`() {
        val f = Fixture()
        f.put(0, "obj.xbows_crossbow_stock_wood")
        f.put(1, "obj.xbows_crossbow_limbs_bronze")
        f.put(2, "obj.hammer")
        f.start("assemble_crossbow_bronze", 1)
        f.player.inv[2] = null
        val before = f.snapshot()
        f.advance(2)
        assertEquals(before, f.snapshot())
        assertEquals(0, f.xp())
    }

    @Test
    fun `all broad ammunition recipes require the actual purchased Slayer unlock`() {
        for (key in listOf("arrow_broad", "bolt_broad", "tip_bolt_amethyst_broad")) {
            val f = Fixture()
            val recipe = FletchingRecipe(FletchingRecipesRow.getRow("dbrow.fletching_$key"))
            f.put(0, recipe.input.internalName, recipe.data.batchSize)
            f.put(1, checkNotNull(recipe.secondary).internalName, recipe.data.batchSize)
            val before = f.snapshot()
            assertEquals(FletchingFailure.SLAYER_UNLOCK, f.once(key))
            assertEquals(before, f.snapshot())
            assertEquals(0, f.xp())
            f.unlockBroaderFletching()
            assertNull(f.once(key))
            f.assertCount(recipe.input.internalName, 0)
            f.assertCount(recipe.secondary.internalName, 0)
            f.assertCount(recipe.product.internalName, recipe.data.batchSize)
            assertEquals(recipe.data.xpTenths * recipe.data.batchSize, f.xp())
        }
    }

    @Test
    fun `revoking the Slayer unlock while an action is pending cannot create broad ammo`() {
        val f = Fixture()
        f.put(0, "obj.slayer_broad_bolt_unfinished", 20)
        f.put(1, "obj.feather", 20)
        f.unlockBroaderFletching()
        f.start("bolt_broad", 2)
        f.advance(2)
        f.assertCount("obj.slayer_broad_bolt", 10)
        VarPlayerIntMapSetter.set(f.player, "varp.slayer_rewards_unlocks", 0)
        val before = f.snapshot()
        f.advance(2)
        assertEquals(before, f.snapshot())
        assertEquals(300, f.xp())
    }

    @Test
    fun `blurite assembly and stringing require the real completed quest stage`() {
        for (key in listOf("assemble_crossbow_blurite", "string_crossbow_blurite")) {
            val f = Fixture()
            val recipe = FletchingRecipe(FletchingRecipesRow.getRow("dbrow.fletching_$key"))
            f.put(0, recipe.input.internalName)
            f.put(1, checkNotNull(recipe.secondary).internalName)
            f.put(2, "obj.hammer")
            val before = f.snapshot()
            assertEquals(FletchingFailure.QUEST, f.once(key))
            f.knightsSwordStage(QuestRow.getRow("dbrow.quest_knightssword").endstate - 1)
            assertEquals(FletchingFailure.QUEST, f.once(key))
            assertEquals(before, f.snapshot())
            f.knightsSwordStage(QuestRow.getRow("dbrow.quest_knightssword").endstate)
            assertNull(f.once(key))
            f.assertCount(recipe.product.internalName, 1)
            f.assertCount(recipe.input.internalName, 0)
            f.assertCount(recipe.secondary.internalName, 0)
            f.assertCount("obj.hammer", 1)
        }
    }

    @Test
    fun `quest progress is rechecked when the crossbow animation finishes`() {
        val f = Fixture()
        f.put(0, "obj.xbows_crossbow_unstrung_blurite")
        f.put(1, "obj.xbows_crossbow_string")
        f.knightsSwordStage(QuestRow.getRow("dbrow.quest_knightssword").endstate)
        f.start("string_crossbow_blurite", 1)
        f.knightsSwordStage(0)
        val before = f.snapshot()
        f.advance(2)
        assertEquals(before, f.snapshot())
        assertEquals(0, f.xp())
    }

    @Test
    fun `each alternative feather is consumed exclusively without pooling other feather types`() {
        for (row in FletchingRecipesRow.all().filter { it.secondaryAlternatives.isNotEmpty() }) {
            for (recipe in FletchingRecipe.variants(row)) {
                val f = Fixture()
                f.unlockBroaderFletching()
                val selected = checkNotNull(recipe.secondary)
                val untouched = FletchingRecipe.variants(row).first { it.secondary?.id != selected.id }.secondary!!
                f.put(0, recipe.input.internalName, 20)
                f.put(1, selected.internalName, 3)
                f.put(2, untouched.internalName, 20)
                assertNull(f.once(recipe))
                f.assertCount(recipe.product.internalName, 3)
                f.assertCount(recipe.input.internalName, 17)
                f.assertCount(selected.internalName, 0)
                f.assertCount(untouched.internalName, 20)
                assertEquals(row.xpTenths * 3, f.xp())
                assertEquals(FletchingFailure.MATERIALS, f.once(recipe))
            }
        }
    }

    @Test
    fun `bow cutting and stringing conserve the separate fine XP amounts`() {
        for ((wood, bow, expectedXp) in listOf(
            Triple("willow", "willow_shortbow", 665),
            Triple("maple", "maple_longbow", 1165),
            Triple("magic", "magic_shortbow", 1666),
        )) {
            val f = Fixture()
            f.put(0, "obj.knife")
            f.put(1, "obj.${wood}_logs")
            f.put(2, "obj.bow_string")
            assertNull(f.once("cut_$bow"))
            assertNull(f.once("string_$bow"))
            f.assertCount("obj.$bow", 1)
            f.assertCount("obj.${wood}_logs", 0)
            f.assertCount("obj.unstrung_$bow", 0)
            f.assertCount("obj.bow_string", 0)
            assertEquals(expectedXp, f.xp())
        }
    }

    private data class GemCut(
        val key: String, val input: String, val output: String, val count: Int, val xp: Int,
    )

    @OptIn(InternalApi::class)
    private class Fixture {
        private val coroutine = GameCoroutine("fletching-test")
        private val events = EventBus()
        private var result: Result<Unit>? = null
        val player = Player().apply {
            client = SilentClient()
            uuid = 927L
            slotId = 1
            assignUid()
            currentMapClock = 100
            processedMapClock = 100
            inv = inventory("inv.inv", 28)
            worn = inventory("inv.worn", 14)
            statMap.setBaseLevel("stat.fletching", 99)
            statMap.setCurrentLevel("stat.fletching", 99)
            xpRate = 1.0
            globalXpRate = 1.0
        }
        private val access = ProtectedAccess(
            player, coroutine,
            ProtectedAccessContextFactory.empty().copy(getEventBus = { events }),
        )

        fun put(slot: Int, item: String, count: Int = 1) { player.inv[slot] = InvObj(item, count) }

        fun fill() { for (slot in player.inv.indices) put(slot, "obj.bronze_dagger") }

        fun once(key: String) = access.fletchOnce(recipe(key))

        fun once(recipe: FletchingRecipe) = access.fletchOnce(recipe)

        fun unlockBroaderFletching() = SlayerTaskManager.unlockReward(
            access, SlayerUnlockRow.getRow("dbrow.slayer_ammo_unlocked").bit,
        )

        fun knightsSwordStage(stage: Int) =
            VarPlayerIntMapSetter.set(player, "varbit.knights_sword_progress", stage)

        fun start(key: String, sets: Int) {
            player.activeCoroutine = coroutine
            val block: suspend () -> Unit = { access.fletchBatch(recipe(key), sets) }
            block.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) { this@Fixture.result = result }
            })
            result?.getOrThrow()
        }

        fun advance(ticks: Int) {
            repeat(ticks) {
                player.currentMapClock++
                player.processedMapClock = player.currentMapClock
                coroutine.advance()
                result?.exceptionOrNull()?.let { if (it !is CancellationException) throw it }
            }
        }

        fun cancel() {
            assertFalse(coroutine.isIdle)
            assertFalse(player.isDelayed)
            player.clearPendingAction(events)
            assertNull(player.activeCoroutine)
        }

        fun finished(): Boolean = result != null && coroutine.isIdle

        fun snapshot() = player.inv.objs.toList()

        fun xp() = player.statMap.getFineXP("stat.fletching")

        fun assertCount(item: String, count: Int) =
            assertEquals(count, player.inv.physicalCount(item), item)

        private fun recipe(key: String) =
            FletchingRecipe(FletchingRecipesRow.getRow("dbrow.fletching_$key"))

        private fun inventory(name: String, size: Int) = Inventory(
            checkNotNull(ServerCacheManager.getInventory(name.asRSCM())), arrayOfNulls(size),
        )
    }

    private class SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private val RESTORATIONS = mutableListOf<() -> Unit>()

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            ServerCacheManager.init(241).close()
            val previousPolicy = QuestRequirements.activePolicy()
            RESTORATIONS += { QuestRequirements.install(previousPolicy) }
            QuestRequirements.install(QuestRequirementPolicy())
            val registryField = Quest::class.java.getDeclaredField("questsByKey").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val registry = registryField.get(null) as MutableMap<String, Quest>
            val previousQuests = registry.toMap()
            RESTORATIONS += { registry.clear(); registry.putAll(previousQuests) }
            KnightsSwordQuest()
            for ((owner, name) in listOf(
                "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
            )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val previous = field.get(null)
                RESTORATIONS += { field.set(null, previous) }
            }
            val previousStorage = InvVirtualStorageHolder.instance
            RESTORATIONS += { InvVirtualStorageHolder.instance = previousStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic
        @AfterAll
        fun restoreGlobals() {
            RESTORATIONS.asReversed().forEach { it() }
            RESTORATIONS.clear()
        }
    }
}
