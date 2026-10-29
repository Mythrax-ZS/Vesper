package org.rsmod.game.hit

import dev.openrune.ServerCacheManager
import dev.openrune.types.ItemServerType
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcUid
import org.rsmod.game.entity.player.PlayerUid

public class Hit(
    public val type: HitType,
    public val hitmark: Hitmark,
    private val sourceUid: Int?,
    private val righthandObj: Int?,
    private val secondaryObj: Int?,
    /**
     * Whether the attack was explicitly declared a magic splash before damage modification.
     * `false` does not imply a successful accuracy roll; ordinary misses and scripted hits also
     * use the default. This outcome is independent of the final [damage].
     */
    public val isMagicSplash: Boolean = false,
) {
    public var retaliation: HitRetaliation? = null
        private set

    init {
        require(!isMagicSplash || type == HitType.Magic) { "Only magic hits can splash." }
    }

    public val damage: Int
        get() = hitmark.damage

    public val isFromNpc: Boolean
        get() = hitmark.isNpcSource

    public val isFromPlayer: Boolean
        get() = hitmark.isPlayerSource

    public fun withRetaliation(retaliation: HitRetaliation?): Hit =
        copy().also { it.retaliation = retaliation }

    public fun copy(
        type: HitType = this.type,
        hitmark: Hitmark = this.hitmark,
        sourceUid: Int? = this.sourceUid,
        righthandObj: Int? = this.righthandObj,
        secondaryObj: Int? = this.secondaryObj,
        isMagicSplash: Boolean = this.isMagicSplash,
    ): Hit = Hit(type, hitmark, sourceUid, righthandObj, secondaryObj, isMagicSplash).also {
        it.retaliation = retaliation
    }

    public operator fun component1(): HitType = type
    public operator fun component2(): Hitmark = hitmark
    private operator fun component3(): Int? = sourceUid
    private operator fun component4(): Int? = righthandObj
    private operator fun component5(): Int? = secondaryObj
    public operator fun component6(): Boolean = isMagicSplash

    override fun equals(other: Any?): Boolean =
        this === other || other is Hit && type == other.type && hitmark == other.hitmark &&
            sourceUid == other.sourceUid && righthandObj == other.righthandObj &&
            secondaryObj == other.secondaryObj && isMagicSplash == other.isMagicSplash

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + hitmark.hashCode()
        result = 31 * result + (sourceUid ?: 0)
        result = 31 * result + (righthandObj ?: 0)
        result = 31 * result + (secondaryObj ?: 0)
        result = 31 * result + isMagicSplash.hashCode()
        return result
    }

    public fun isRighthandObj(type: ItemServerType): Boolean = type.id == righthandObj

    public fun righthandType(): ItemServerType? =
        righthandObj?.let { ServerCacheManager.getItem(it) }

    public fun isSecondaryObj(type: ItemServerType): Boolean = type.id == secondaryObj

    public fun secondaryType(): ItemServerType? =
        secondaryObj?.let { ServerCacheManager.getItem(it) }

    public fun resolveNpcSource(npcList: NpcList): Npc? {
        val uid = checkNotNull(sourceUid) { "Hit did not originate from a source: $this" }
        check(isFromNpc) { "Hit did not originate from an npc: $this" }

        val npcUid = NpcUid(uid)
        return npcUid.resolve(npcList)
    }

    public fun resolvePlayerSource(playerList: PlayerList): Player? {
        val uid = checkNotNull(sourceUid) { "Hit did not originate from a source: $this" }
        check(isFromPlayer) { "Hit did not originate from a player: $this" }

        val playerUid = PlayerUid(uid)
        return playerUid.resolve(playerList)
    }

    override fun toString(): String =
        "Hit(type=$type, isMagicSplash=$isMagicSplash, righthandObj=$righthandObj, secondaryObj=$secondaryObj, hitmark=$hitmark)"
}
