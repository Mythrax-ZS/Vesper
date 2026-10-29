package org.rsmod.api.spells.runes

import jakarta.inject.Inject
import org.rsmod.game.entity.Player

/** Server authority shared by spell validation and the derived client availability flag. */
public class BlightedRunePackPolicy
@Inject
constructor(private val permissions: Set<@JvmSuppressWildcards BlightedRunePackPermission>) {
    public fun permits(player: Player): Boolean = permissions.any { it.permits(player) }
}
