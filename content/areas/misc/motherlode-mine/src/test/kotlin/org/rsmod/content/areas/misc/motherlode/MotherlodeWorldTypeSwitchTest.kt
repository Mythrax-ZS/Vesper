package org.rsmod.content.areas.misc.motherlode

import dev.openrune.ServerCacheManager
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.areas.misc.motherlode.circuit.MotherlodeWaterCircuit
import org.rsmod.content.areas.misc.motherlode.scripts.MotherlodeMineScript
import org.rsmod.content.areas.misc.motherlode.veins.MotherlodeVeins
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.entity.player.WorldTypeChangedEvent
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.world.WorldType
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

@ResourceLock("ServerCacheManager")
class MotherlodeWorldTypeSwitchTest {
    @Test
    fun `pending switch pauses the actual cleaning countdown and abort resumes it exactly once`() {
        val f = Fixture()
        val player = f.player()
        f.deposit(player, PayDirtOre.Gold, 5)
        f.tick(12)
        val before = player.vars.backing.toMap()
        player.worldTypeSwitching = true
        f.tick(30)
        assertEquals(before, player.vars.backing.toMap())
        assertEquals(5, player.cleaningPayDirtTotal)
        assertEquals(0, player.sackTotal)
        assertEquals(0, player.statMap.getFineXP("stat.mining"))

        // An aborted switch does not publish WorldTypeChanged or replace the save.
        player.worldTypeSwitching = false
        f.tick()
        assertEquals(0, player.cleaningPayDirtTotal)
        assertEquals(5, player.sackCount(PayDirtOre.Gold))
        assertEquals(f.fineXp(PayDirtOre.Gold, 5), player.statMap.getFineXP("stat.mining"))
        f.tick(15)
        assertEquals(5, player.sackTotal)
        assertEquals(f.fineXp(PayDirtOre.Gold, 5), player.statMap.getFineXP("stat.mining"))
    }

    @Test
    fun `forced disconnect preserves the remaining paydirt and prevents post failure cleaning`() {
        val f = Fixture()
        val player = f.player()
        f.deposit(player, PayDirtOre.Gold, 5)
        f.tick(12)
        val before = player.vars.backing.toMap()
        player.forceDisconnect = true
        f.tick(30)
        assertEquals(before, player.vars.backing.toMap())
        assertEquals(5, player.cleaningPayDirtTotal)
        assertEquals(0, player.sackTotal)
        assertEquals(0, player.statMap.getFineXP("stat.mining"))
    }

    @Test
    fun `delete removes suspended batches even without logout and reconnect cleans saved paydirt once`() {
        for (normalLogout in listOf(false, true)) {
            val f = Fixture()
            val player = f.player()
            f.deposit(player, PayDirtOre.Gold, 5)
            f.tick(12)
            val saved = player.vars.backing.toMap()
            player.forceDisconnect = true
            f.delete(player, normalLogout)
            // Clearing the guard ensures this assertion detects a retained circuit entry.
            player.forceDisconnect = false
            f.tick(15)
            assertEquals(saved, player.vars.backing.toMap())
            assertEquals(0, player.statMap.getFineXP("stat.mining"))

            val reconnect = f.player().apply { vars.backing.putAll(saved) }
            f.login(reconnect)
            f.tick(12)
            assertEquals(5, reconnect.cleaningPayDirtTotal)
            assertEquals(0, reconnect.sackTotal)
            f.tick()
            assertEquals(0, reconnect.cleaningPayDirtTotal)
            assertEquals(5, reconnect.sackCount(PayDirtOre.Gold))
            assertEquals(f.fineXp(PayDirtOre.Gold, 5), reconnect.statMap.getFineXP("stat.mining"))
            f.tick(15)
            assertEquals(5, reconnect.sackTotal)
            assertEquals(f.fineXp(PayDirtOre.Gold, 5), reconnect.statMap.getFineXP("stat.mining"))
        }
    }

    @Test
    fun `successful switch replaces outgoing batch timing and cleans only the target saves paydirt`() {
        val f = Fixture()
        val player = f.player()
        f.deposit(player, PayDirtOre.Gold, 7)
        f.tick(12)
        val outgoing = player.vars.backing.toMap()
        player.worldTypeSwitching = true
        f.tick(5)

        f.replaceSave(player, WorldType.RAGING_ECHOES_LEAGUE) {
            addCleaningPayDirt(PayDirtOre.Mithril, 2)
            addCleaningPayDirt(PayDirtOre.Runite, 1)
            setSackCount(PayDirtOre.Coal, 4)
        }
        f.tick(12)
        assertEquals(3, player.cleaningPayDirtTotal)
        assertEquals(4, player.sackTotal)
        assertEquals(0, player.statMap.getFineXP("stat.mining"))
        f.tick()
        assertEquals(0, player.cleaningPayDirtTotal)
        assertEquals(4, player.sackCount(PayDirtOre.Coal))
        assertEquals(2, player.sackCount(PayDirtOre.Mithril))
        assertEquals(1, player.sackCount(PayDirtOre.Runite))
        assertEquals(0, player.sackCount(PayDirtOre.Gold))
        val targetXp = f.fineXp(PayDirtOre.Mithril, 2) + f.fineXp(PayDirtOre.Runite, 1)
        assertEquals(targetXp, player.statMap.getFineXP("stat.mining"))
        val target = player.vars.backing.toMap()

        f.replaceSave(player, WorldType.MAIN) { vars.backing.putAll(outgoing) }
        f.tick(13)
        assertEquals(7, player.sackCount(PayDirtOre.Gold))
        assertEquals(7, player.sackTotal)
        assertEquals(0, player.cleaningPayDirtTotal)
        assertEquals(f.fineXp(PayDirtOre.Gold, 7), player.statMap.getFineXP("stat.mining"))

        f.replaceSave(player, WorldType.RAGING_ECHOES_LEAGUE) {
            vars.backing.putAll(target)
            statMap.setFineXP("stat.mining", targetXp)
        }
        f.tick(15)
        assertEquals(7, player.sackTotal)
        assertEquals(0, player.cleaningPayDirtTotal)
        assertEquals(targetXp, player.statMap.getFineXP("stat.mining"), "Restored finished ore must not award XP again")
    }

    @Test
    fun `switching to a save with no pending paydirt removes the outgoing queue`() {
        val f = Fixture()
        val player = f.player()
        f.deposit(player, PayDirtOre.Gold, 5)
        f.tick(12)
        f.replaceSave(player, WorldType.RAGING_ECHOES_LEAGUE) {}
        // A new target-mode deposit must receive its own full cleaning interval.
        f.deposit(player, PayDirtOre.Mithril, 3)
        f.tick(12)
        assertEquals(3, player.cleaningPayDirtTotal)
        assertEquals(0, player.sackTotal)
        f.tick()
        assertEquals(3, player.sackCount(PayDirtOre.Mithril))
        assertEquals(0, player.cleaningPayDirtTotal)
        assertEquals(f.fineXp(PayDirtOre.Mithril, 3), player.statMap.getFineXP("stat.mining"))
    }

    @Test
    fun `one switching player does not stop another players washing machine batch`() {
        val f = Fixture()
        val pending = f.player()
        val active = f.player()
        f.deposit(pending, PayDirtOre.Gold, 2)
        f.deposit(active, PayDirtOre.Mithril, 3)
        pending.worldTypeSwitching = true
        f.tick(13)
        assertEquals(2, pending.cleaningPayDirtTotal)
        assertEquals(0, pending.sackTotal)
        assertEquals(0, pending.statMap.getFineXP("stat.mining"))
        assertEquals(0, active.cleaningPayDirtTotal)
        assertEquals(3, active.sackCount(PayDirtOre.Mithril))
        assertEquals(f.fineXp(PayDirtOre.Mithril, 3), active.statMap.getFineXP("stat.mining"))
        pending.worldTypeSwitching = false
        f.tick(12)
        assertEquals(2, pending.cleaningPayDirtTotal)
        f.tick()
        assertEquals(2, pending.sackCount(PayDirtOre.Gold))
        assertEquals(3, active.sackTotal)
    }

    private class Fixture {
        private val events = EventBus()
        private val clock = MapClock(100)
        private val collision = CollisionFlagMap().apply {
            for (x in 3712..3775 step 8) for (z in 5632..5695 step 8) allocateIfAbsent(x, z, 0)
        }
        private val circuit = MotherlodeWaterCircuit(
            mockk<LocRepository>(relaxed = true), mockk<NpcRepository>(relaxed = true),
            collision, clock, XpModifiers(emptySet()),
        )

        init {
            with(MotherlodeMineScript(events, mockk<MotherlodeVeins>(relaxed = true), circuit)) {
                ScriptContext(events, CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @OptIn(InternalApi::class)
        fun player(): Player = Player().apply {
            worldType = WorldType.MAIN
            coords = MotherlodeMine.LOWER_HOPPER
            statMap.setBaseLevel("stat.mining", 99)
            statMap.setCurrentLevel("stat.mining", 99)
        }

        fun deposit(player: Player, ore: PayDirtOre, count: Int) {
            player.addCleaningPayDirt(ore, count)
            circuit.deposit(player, count)
        }

        fun login(player: Player) {
            events.publish(SessionStateEvent.Login(player))
        }

        fun delete(player: Player, normalLogout: Boolean) {
            if (normalLogout) events.publish(SessionStateEvent.Logout(player))
            repeat(2) { events.publish(SessionStateEvent.Delete(player)) }
        }

        fun tick(count: Int = 1) {
            repeat(count) {
                clock.cycle++
                assertTrue(events.publish(GameLifecycle.LateCycle))
            }
        }

        @OptIn(InternalApi::class)
        fun replaceSave(player: Player, target: WorldType, load: Player.() -> Unit) {
            val from = player.worldType
            player.worldTypeSwitching = true
            player.vars.backing.clear()
            player.statMap.clear()
            player.statMap.setBaseLevel("stat.mining", 99)
            player.statMap.setCurrentLevel("stat.mining", 99)
            player.load()
            player.worldType = target
            player.worldTypeSwitching = false
            // Match WorldTypeSwitchService: Login is synchronous and precedes Changed.
            events.publish(SessionStateEvent.Login(player))
            events.publish(WorldTypeChangedEvent(player, from, target))
        }

        fun fineXp(ore: PayDirtOre, count: Int): Int = (ore.xp * count * 10).toInt()
    }

    companion object {
        @JvmStatic @BeforeAll fun cache() { ServerCacheManager.init(241).close() }
    }
}
