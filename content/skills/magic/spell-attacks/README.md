# Magic spell attacks

The module registers elemental, Ancient, Arceuus, and standard control-spell handlers. Registration is not a claim that every spell or mechanic has full OSRS parity.

## Standard binding and Tele Block

Bind, Snare, Entangle, and Tele Block use native spell objects for their level, spellbook, UI target, and rune requirements. The server-only `standard_control_spells` table supplies effect durations, base damage, outcome XP, and symbolic presentation references. Ordinary runes and blighted sacks share the central `MagicRuneManager` payment path; a rejected payment launches no effects or XP.

| Spell | Base hold/block | Base damage | Splash XP | Successful cast XP |
| --- | --- | --- | --- | --- |
| Bind | 8 ticks | 0 | 30 | 31 |
| Snare | 16 ticks | 2 | 60 | 61 plus damage XP |
| Entangle | 24 ticks | 5 | 70 | 89 plus damage XP |
| Tele Block | 500 ticks; 250 with Protect from Magic | None | 83.6 | 95 |

These are base XP values before realm multipliers. Binding uses the existing shared damage formula and five-tick thaw immunity. The Swampbark helm, body, and legs each add two ticks; Protect from Magic does not halve these three binding durations. Existing frozen or immune targets are rejected before payment. Player binding tracks the caster and releases when the caster leaves the ten-tile range or is no longer present. Pending effects reject stale NPC lifecycles, player sessions, and successful world-mode changes.

Tele Block applies when the cast succeeds, using Protect from Magic at that point. Its visual impact does not determine the effect's start. The native timer contains active ticks plus a 100-tick immunity suffix. It cannot be refreshed while active or immune. The plugin decrements that timer, announces the final 30 seconds and expiration, and clears it on logout, death, successful world-mode change, or leaving the currently supported Wilderness context. Killing the original caster clears the killer's block without immunity; full account identity is stored in temporary variables so a caster's relog or reused protocol UID cannot confuse that rule. A caster's logout alone does not clear victims' blocks.

Status processing pauses during an outstanding account-mode switch. A failed switch preserves the status and resumes its original deadline; successful switches clear it. A paid binding effect waiting in a global queue is deferred while a participant is quiesced, then either resumes or is invalidated by the actual lifecycle change.

The integration fixture exercises native player-target packets, registered handlers, real rune transactions and formulas, hit/soft-effect queues, and session/death hooks. It includes resource exhaustion, missing requirements, full inventories, maximum sack stacks, repeat casts, lifecycle invalidation, and timer boundaries. These tests verify the implementation; they are not live-game measurements.

## Evidence and remaining limits

- [The Path of Glouphrie, 13 September 2023](https://secure.runescape.com/m=news/the-path-of-glouphrie?oldschool=1) establishes successful binding XP. Current [Bind](https://oldschool.runescape.wiki/w/Bind), [Snare](https://oldschool.runescape.wiki/w/Snare), and [Entangle](https://oldschool.runescape.wiki/w/Entangle) pages supply durations, splash XP, base damage, and Swampbark behavior. Saved revisions are 15165009, 15175486, and 15364849 respectively. Snare's own current infobox says base damage 2; another item's page describes a maximum of 3. The precise discrepancy remains independently unverified.
- [PvP Balancing, 5 September 2019](https://secure.runescape.com/m=news/pvp-balancing-lms-and-elf-graphics?oldschool=1) removes Protect from Magic's duration reduction for standard binding spells.
- [Tele Block revision 13768807](https://oldschool.runescape.wiki/w/Tele_Block?oldid=13768807) explicitly corrects failure XP from the 2017 news post's 84 to 83.6. [Revision 14312525](https://oldschool.runescape.wiki/w/Tele_Block?oldid=14312525) explicitly describes cast-time prayer and effect application, distinguishing the projectile visual. Current page revision 15372902 has less precise wording. No live packet/tick trace independently confirms those timings here.
- Native revision 241 supplies the control-spell objects, animations, binding graphics, Tele Block impact/failure graphics, and timer varbit. The [RuneLite timer implementation](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/timersandbuffs/TimersAndBuffsPlugin.java) and native `teleblock_timer_tooltip` CS2 establish the 100-tick UI offset. Source/probe receipts are under the workspace's `.tools/parity-validation/rune-sacks/`.
- Binding effect scheduling and visual travel currently reuse the existing `projanim.bind`/`projanim.magic_spell` engine profiles. Native animation lengths do not establish live script delays. Exact launch/impact cadence, fade-warning timing/audio, projectile heights, and every presentation detail remain unverified.
- Tele Block's successful travelling graphic is deliberately absent until identified. Native historical ID 344 is now `ballista_special`; using that old number would show the wrong graphic. The identified failure projectile and impact remain enabled.
- The shared formula provides charged Tome of Water binding accuracy, but the existing server lacks a grounded Tome charge-storage/consumption path. Tome charge payment and its charging interface remain unfinished; this module does not fabricate a storage convention.
- Current Tele Block authorization/lifetime covers actual packed Wilderness geometry. PvP/Deadman world rules and minigame contexts need their own authoritative runtime integration. Ferox barriers/apron routing, Corporeal cave entry, and exceptional teleport routes require separate complete interaction handlers; merely checking a timer is not full coverage of those routes.
- Successful Tele Block has no damage hitsplat. Real packet dispatch supplies target-only combat/logout protection; the caster's existing combat clock is preserved. Exact auto-retaliation behavior for non-damaging Tele Block remains unverified.
- Rune-saving equipment interactions with blighted sacks, priority details beyond the central payment policy, other unregistered standard spells, and Vengeance/target-teleport runtime coverage are separate parity work. Existing legacy spell handlers retain their own known limitations.
