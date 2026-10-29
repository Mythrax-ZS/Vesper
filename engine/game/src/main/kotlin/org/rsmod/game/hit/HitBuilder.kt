package org.rsmod.game.hit

import dev.openrune.ServerCacheManager
import dev.openrune.types.ItemServerType

public class HitBuilder(
    public val type: HitType,
    public var damage: Int,
    public val sourceUid: Int?,
    public val sourceSlot: Int?,
    public val isFromNpc: Boolean,
    public val isFromPlayer: Boolean,
    public var clientDelay: Int?,
    private var righthandType: Int?,
    private var secondaryType: Int?,
    private val targetHitmark: Int,
    private val sourceHitmark: Int,
    private val publicHitmark: Int?,
    private val zeroDamageHitmarkLit: Int?,
    private val zeroDamageHitmarkTint: Int?,
    private val maxDamageHitmarkLit: Int?,
    private val targetMaxDamageThreshold: Int,
    private val sourceMaxDamageThreshold: Int,
    /**
     * Original splash outcome, independent of damage changes made by modifiers.
     * See [Hit.isMagicSplash].
     */
    public val isMagicSplash: Boolean = false,
) {
    init {
        require(!isMagicSplash || type == HitType.Magic) { "Only magic hits can splash." }
    }

    /**
     * Percentage (0-100) of a protection prayer's damage block that this hit ignores. `0` (the
     * default) preserves the prayer's normal full block.
     */
    public var penetration: Int = 0

    /** NPC attack details used by damage modifiers, including deferred impact processing. */
    public var npcDamageContext: NpcDamageContext? = null

    /** Launch-time health limit, applied after NPC mitigation and before prayer protection. */
    public var npcDamageCap: Int? = null

    /** Explicit equipment mitigation policy for ordinary player-versus-player attack hits. */
    public var pvpDamageReduction: Boolean = false

    /** Per-hit NPC armour eligibility. It does not change the target's baseline or override. */
    public var npcFlatArmourPolicy: NpcFlatArmourPolicy = NpcFlatArmourPolicy.Standard

    public var reflectionDamage: Int? = null
    public var isReflected: Boolean = false
    public var sourceSnapshot: HitSourceSnapshot? = null

    public fun isRighthandObj(type: ItemServerType): Boolean = type.id == righthandType

    public fun isSecondaryObj(type: ItemServerType): Boolean = type.id == secondaryType

    public fun righthandType(): ItemServerType? =
        righthandType?.let { ServerCacheManager.getItem(it) }

    public fun secondaryType(): ItemServerType? =
        secondaryType?.let { ServerCacheManager.getItem(it) }

    public fun build(): Hit {
        val hitmark = buildHitmark()
        return Hit(
            type = type,
            hitmark = hitmark,
            sourceUid = sourceUid,
            righthandObj = righthandType,
            secondaryObj = secondaryType,
            isMagicSplash = isMagicSplash,
        ).withRetaliation(HitRetaliation(reflectionDamage ?: damage, isReflected, sourceSnapshot))
    }

    private fun buildHitmark(): Hitmark {
        val clientDelay = checkNotNull(clientDelay) { "`clientDelay` must be set." }

        var target = targetHitmark
        var source = sourceHitmark
        var public = publicHitmark

        if (damage == 0) {
            target = zeroDamageHitmarkLit ?: targetHitmark
            source = zeroDamageHitmarkLit ?: sourceHitmark
            if (publicHitmark != null) {
                public = zeroDamageHitmarkTint ?: publicHitmark
            }
        } else {
            if (damage >= targetMaxDamageThreshold) {
                target = maxDamageHitmarkLit ?: targetHitmark
            }
            if (damage >= sourceMaxDamageThreshold) {
                source = maxDamageHitmarkLit ?: sourceHitmark
            }
        }

        return when {
            sourceSlot == null ->
                Hitmark.fromNoSource(
                    self = target,
                    source = source,
                    public = public,
                    damage = damage,
                    delay = clientDelay,
                )

            isFromNpc ->
                Hitmark.fromNpcSource(
                    self = target,
                    source = source,
                    public = public,
                    damage = damage,
                    delay = clientDelay,
                    slotId = sourceSlot,
                )

            isFromPlayer ->
                Hitmark.fromPlayerSource(
                    self = target,
                    source = source,
                    public = public,
                    damage = damage,
                    delay = clientDelay,
                    slotId = sourceSlot,
                )

            else -> throw IllegalStateException() // Should be unreachable.
        }
    }
}
