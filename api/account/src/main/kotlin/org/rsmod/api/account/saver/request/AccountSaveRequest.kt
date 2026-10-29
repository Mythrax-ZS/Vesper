package org.rsmod.api.account.saver.request

import org.rsmod.api.account.saver.CharacterSaveSnapshot
import org.rsmod.game.entity.Player

/** The live player is retained only for completion notification, never database values. */
public class AccountSaveRequest(
    public val player: Player,
    public val snapshot: CharacterSaveSnapshot,
    public val callback: AccountSaveCallback,
) {
    public var attempts: Int = 0
        internal set
    internal var sequence: Long = 0
    internal var completed: Boolean = false
}
