package org.rsmod.api.net.rsprot.handlers

import net.rsprot.protocol.game.incoming.misc.user.MoveGameClick
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.rsmod.game.entity.Player
import org.rsmod.game.movement.RouteRequestCoord
import org.rsmod.map.CoordGrid

class WorldTypeInputTest {
    @Test
    fun `input consumed while switching is discarded and cannot replay when outgoing mode resumes`() {
        val player = Player()
        val original = RouteRequestCoord(CoordGrid(3200, 3200), clientRequest = true)
        player.routeRequest = original
        val destinations = mutableListOf<CoordGrid>()
        val handler = MessageHandler<MoveGameClick> { target, message ->
            val destination = CoordGrid(message.x, message.z)
            destinations += destination
            target.routeRequest = RouteRequestCoord(destination, clientRequest = true)
        }
        player.worldTypeSwitching = true
        handler.consume(player, MoveGameClick(3210, 3210, 0))
        handler.consume(player, MoveGameClick(3220, 3220, 0))
        assertSame(original, player.routeRequest)
        assertTrue(destinations.isEmpty())
        player.worldTypeSwitching = false
        handler.consume(player, MoveGameClick(3230, 3230, 0))
        assertEquals(listOf(CoordGrid(3230, 3230)), destinations)
        assertNotSame(original, player.routeRequest)
    }

    @Test
    fun `switch started by a packet prevents later packets in the same input batch`() {
        val player = Player()
        var actions = 0
        val handler = MessageHandler<MoveGameClick> { target, _ ->
            actions++
            target.worldTypeSwitching = true
        }
        repeat(3) { handler.consume(player, MoveGameClick(3210, 3210, 0)) }
        assertEquals(1, actions)
    }

    @Test
    fun `failed switch recovery cannot run input while awaiting forced logout`() {
        for (invalidPersistence in listOf(false, true)) {
            val player = Player()
            if (invalidPersistence) player.persistenceInvalidated = true else player.forceDisconnect = true
            var ran = false
            val handler = MessageHandler<MoveGameClick> { _, _ -> ran = true }
            handler.consume(player, MoveGameClick(3210, 3210, 0))
            assertFalse(ran)
        }
    }
}
