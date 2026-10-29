# Blighted rune sacks and control spells

The central rune-payment path now recognizes five sack families through the
server-only `enum.blighted_spell_sacks`. One eligible sack replaces the rune
portion of one cast; equipment and other item requirements remain. Spell level,
spellbook, quest and target checks still apply. A splash consumes its paid cast;
secondary targets do not consume additional sacks.

| Sack | Native mapped spells | Runtime scope |
| --- | --- | --- |
| Entangle | Bind, Snare, Entangle | Registered standard control handlers |
| Ancient ice | Ice Rush, Burst, Blitz, Barrage | Existing Ancient handlers |
| Surge | Four Wave and four Surge spells | Existing elemental handlers |
| Teleport | Tele Block, Teleport to Target | Tele Block implemented; target teleport still needs Bounty Hunter gameplay |
| Vengeance | Vengeance, Vengeance Other | Self and Other handlers in `content/skills/magic/lunar-spells`; sack payment follows the central path |

The 19-entry map follows revision-241 native spellbook availability logic.
Jagex's [20 September 2023 update](https://secure.runescape.com/m=news/poll-80--shooting-stars-changes?oldschool=1)
merged the old Bind/Snare sacks into Entangle and Wave into Surge. Mapping a spell
does not implement its runtime, unlocks or minigame state.

## Permission and payment

Current permission comes from packed Wilderness, Ferox town or Mage Arena bank
membership, including normalized copied zones. The native
`blighted_items_allowed` flag is derived for client display; its value does not
authorize server consumption. Supply permission is separate from PvP permission,
so a sack never authorizes attacking a player in safe Ferox or Mage Bank.

Mage Bank permission also now applies to blighted food and potions. Its packed
area covers the isolated native bank square and linked god-cape chamber; this
boundary is a native-map inference rather than a recovered Jagex server predicate.
Jagex's [13 November 2024 update](https://secure.runescape.com/m=news/guardians-of-the-rift-changes-out-now?oldschool=1)
establishes Ferox and Mage Bank supply permission. Ferox's basement marker does
not grant permission; basement eligibility remains unverified.

The implementation chooses an available eligible sack before loose or pouch
runes, otherwise falling back to the existing rune resolver. Existing fake-rune
contexts retain their previous path. Pouch-only payments now complete without an
empty inventory transaction; an exhausted pouch or stale inventory source fails
without granting a free cast. Native client availability checks support
the spell mapping, but do not prove server consumption priority. Sack-versus-rune
priority, unlimited-rune precedence and equipment rune-saving interactions remain
live-verification gaps; no saving roll is introduced for sacks.

## Client and cache integration

The server derives native varbit `blighted_items_allowed` (11028) from its
permission policy. The client uses that flag to display spell availability;
server-side casting checks remain authoritative. The cache override of native
script 5743 preserves its existing procedure signature and redraw arguments,
adding `zeah_temp_2` to its `onvartransmit` trigger list so a permission change
can refresh the spellbook.

Use the rebuilt packaged cache through `./start-server.sh` and
`./start-client.sh` from the workspace root. No additional RuneLite plugin or
resolution setting is required. Native cache/script inspection and local tests
cover the wiring; live in-game highlighting and boundary transitions have not
been visually verified.

## Control spells and limits

Bind, Snare, Entangle and player Tele Block now have cache-backed outcome data
and lifecycle handlers. Tele Block uses cast-time prayer, immediate status
application, a native active/immunity timer, and logout/death/Wilderness-exit
cleanup. See the [spell module README](../content/skills/magic/spell-attacks/README.md)
for exact XP, durations, source revisions and limitations, including incomplete
presentation and Tome of Water charge handling. These additions do not complete
all standard spells, Ferox barriers or exceptional teleport routes.

Jagex [expanded blighted supplies to three minigames](https://secure.runescape.com/m=news/araxxor-cas-poll-82-updates--more?oldschool=1)
in September 2024. Actual Clan Wars, Soul Wars, Castle Wars, Bounty Hunter and
PvP/Deadman-world contexts still need authoritative runtime integrations; stale
UI flags or broad lobby coordinates are not substitutes.

Tests exercise registered casts, actual player packets, native requirements,
real inventory transactions and queued effects, including splashes, exhausted
and maximum stacks, full inventories, denial, and lifecycle boundaries. Build
results are recorded separately. Local tests and a client login smoke do not
establish live OSRS spell or supply acceptance. The source receipts are under
`.tools/parity-validation/rune-sacks/` in the workspace.
