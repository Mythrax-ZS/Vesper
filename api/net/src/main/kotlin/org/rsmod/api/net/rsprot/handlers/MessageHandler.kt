package org.rsmod.api.net.rsprot.handlers

import net.rsprot.protocol.message.IncomingGameMessage
import net.rsprot.protocol.message.codec.incoming.MessageConsumer
import org.rsmod.game.entity.Player

fun interface MessageHandler<in T : IncomingGameMessage> : MessageConsumer<Player, T> {
    fun handle(player: Player, message: T)

    override fun consume(receiver: Player, message: T) {
        if (receiver.worldTypeSwitching || receiver.forceDisconnect || receiver.persistenceInvalidated) return
        handle(receiver, message)
    }
}
