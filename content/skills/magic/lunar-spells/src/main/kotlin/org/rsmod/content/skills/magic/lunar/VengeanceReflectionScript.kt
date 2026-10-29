package org.rsmod.content.skills.magic.lunar

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.annotations.InternalApi
import org.rsmod.api.config.refs.done.hitmark_groups
import org.rsmod.api.enums.NamedEnums
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.access.StandardNpcAccessContextFactory
import org.rsmod.api.player.events.PlayerHitpointsChangedEvent
import org.rsmod.api.player.hit.processor.InstantPlayerHitProcessor
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.script.onPlayerHitpointsChanged
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitRetaliation
import org.rsmod.game.hit.HitSourceSnapshot
import org.rsmod.game.hit.HitType
import org.rsmod.game.hit.Hitmark
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

public class VengeanceReflectionScript @Inject constructor(
    private val state: VengeanceState,
    private val npcs: NpcList,
    private val players: PlayerList,
    private val playerHits: InstantPlayerHitProcessor,
    private val npcContexts: StandardNpcAccessContextFactory,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerHitpointsChanged { reflect(this) }
    }

    @OptIn(InternalApi::class)
    private fun reflect(event: PlayerHitpointsChangedEvent) {
        val player = event.player
        val hit = event.hit
        val retaliation = hit.retaliation ?: return
        if (!player.canProcess || event.oldHitpoints <= event.newHitpoints ||
            !state.isActive(player) || retaliation.reflected || retaliation.damage <= 0 ||
            hit.isMagicSplash || hit.isToxin()
        ) return

        val damage = (retaliation.damage.toLong() * 3 / 4).coerceIn(1L, Hitmark.DAMAGE_BIT_MASK).toInt()
        when (val source = retaliation.source) {
            is HitSourceSnapshot.FromPlayer -> {
                val attacker = source.resolve(players) ?: return
                if (attacker === player || attacker.hitpoints <= 0 || attacker.worldType != player.worldType) return
                if (!state.consume(player)) return
                player.say("Taste vengeance!")
                val reflected = reflectedHit(player, damage)
                with(playerHits) { attacker.process(reflected) }
            }

            is HitSourceSnapshot.FromNpc -> {
                val attacker = source.resolve(npcs) ?: return
                if (attacker.hitpoints <= 0 || NamedEnums.vengeance_recoil_immune[attacker.visType] == 1) return
                if (!state.consume(player)) return
                player.say("Taste vengeance!")
                val reflected = reflectedHit(player, damage)
                attacker.launchBeside {
                    StandardNpcAccess(attacker, this, npcContexts.create()).processQueuedHit(reflected)
                }
            }

            null -> Unit
        }
    }

    private fun reflectedHit(player: Player, damage: Int): Hit {
        val group = hitmark_groups.regular_damage
        val lit = group.lit.asRSCM(RSCMType.HITMARK)
        val mark = Hitmark.fromPlayerSource(
            self = lit,
            source = lit,
            public = group.tint?.asRSCM(RSCMType.HITMARK),
            damage = damage,
            delay = 0,
            slotId = player.slotId,
        )
        return Hit(HitType.Typeless, mark, player.uid.packed, null, null)
            .withRetaliation(HitRetaliation(damage, true, HitSourceSnapshot.capture(player)))
    }

    private fun Hit.isToxin(): Boolean =
        hitmark.self == hitmark_groups.poison_damage.lit.asRSCM(RSCMType.HITMARK) ||
            hitmark.self == hitmark_groups.venom.lit.asRSCM(RSCMType.HITMARK) ||
            hitmark.self == hitmark_groups.disease.lit.asRSCM(RSCMType.HITMARK)
}
