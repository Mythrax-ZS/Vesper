# Fletching

This module implements 131 recipes. It is a partial skill implementation; a green
entry in the generated content progress report does not establish full parity.

| Flow | Implemented scope |
| --- | --- |
| Knife on logs | Arrow shafts from normal, oak, willow, maple, yew, magic and redwood logs; javelin shafts from normal logs |
| Bow cutting and stringing | Shortbows and longbows from normal through magic wood |
| Arrows | Headless arrows; bronze, iron, steel, mithril, adamant, rune, amethyst and dragon arrowheads |
| Darts | Bronze, iron, steel, mithril, adamant, rune, amethyst and dragon tips |
| Bolt feathering | Bronze, blurite, iron, silver, steel, mithril, adamant, rune and dragon unfinished bolts |
| Bolt tips | Opal through onyx, including both oyster pearl sizes; amethyst tips remain in Crafting |
| Bolt tipping | Ten gem/pearl types on ordinary and dragon bolts, plus barbed bronze bolts |
| Javelins | Bronze, iron, steel, mithril, adamant, rune, amethyst and dragon heads |
| Crossbows | Eight stock, assembly and stringing chains, bronze through dragon |
| Wooden shields | Oak, willow, maple, yew, magic and redwood; two logs per shield |
| Broad ammunition | Broad arrows, broad bolts and amethyst broad bolts with the purchased Slayer unlock |

Use a standard knife on logs, a chisel on cut gems or oyster pearls, a bow/crossbow
string on its unstrung weapon, or the two projectile ingredients on each other.
Crossbow assembly combines the stock and limbs while retaining a hammer in the
inventory. Both blurite assembly and stringing require completion of The Knight's
Sword through the realm's quest policy, which defaults to real progress.

Feathering accepts ordinary, red, yellow, orange, blue, stripy, gryphon and
stymphike feathers. The clicked feather type is consumed; other feather types are
not silently substituted or pooled. Arrows and javelins use sets of up to 15;
darts and bolts use up to 10. The last set can be smaller when ingredients run out.
The make-all limit is
10 projectile sets per selection. The standard `skillmulti` interface handles
selection. The existing `option_fletch_dart_or_bolt_makex` varbit selects between
the menu and immediate, single-set dart/bolt feathering, including broad bolts.
Gem tipping always uses timed sets. The Broader Fletching reward is read from the
existing Slayer reward varp using `dbrow.slayer_ammo_unlocked`; it is required for
all three broad recipes. Crafting broad bolts does not require the Slayer level
that is separately needed to equip them.

Recipe data lives in the server-only `dbtable.fletching_recipes`. Runtime scripts
use its generated `FletchingRecipesRow` accessor. OBJ and SEQ symbols resolve at
the pack boundary; this module introduces no client interface or model overrides.
`gamevals.toml` reserves table 61000 and rows 61000–61130. Build the cache before
compiling the runtime module after editing this table.

The recipe XP column stores tenths per recipe unit. Shaft cutting awards XP per
log, ammunition assembly per individual projectile, gem cutting once per gem,
and shields once per pair of logs. Separate input, secondary and output quantities
preserve the six/twelve/twenty-four tip yields and two-log shield cost. Stringing
willow shortbows and maple longbows awards 33.2 and 58.2 XP respectively; their
cutting steps award 33.3 and 58.3. Magic shortbow cutting and stringing both award
83.3 XP, as documented by the individual item creation tables.
The standard stat API applies the realm's configured XP multipliers; these base
values do not force the running realm to 1× XP.

Each completed set uses one atomic inventory transaction. Ingredient deletion and
product insertion either all commit or all roll back, including a full inventory
or an overflowing output stack. Tools are retained. The coroutine rechecks the
current boosted skill level, tool, physical ingredients, Slayer unlock and quest
gate after every delay.
Cancelling the coroutine preserves all ingredients for work not yet completed.
Tick waits leave the player undelayed so the movement handler can clear the pending
action immediately. A regression test exercises that same clear-pending-action
path while a batch is suspended.

The clock-driven tests exercise the implemented schedule:

| Action | First completion | Subsequent completions |
| --- | --- | --- |
| Log cutting, bows and stocks | 2 ticks | 3 ticks |
| Weapon stringing and crossbow assembly | 2 ticks | 2 ticks |
| Automatic arrow/dart/bolt sets and bolt tipping | 2 ticks | 2 ticks |
| Javelin sets | 1 tick | 2 ticks |
| Gem/pearl tips with an ordinary chisel | 5 ticks | 5 ticks |
| Wooden shields | 7 ticks | 7 ticks |

Manual dart/bolt feathering has no delay. The javelin first/repeat schedule is
inferred from the documented one-set-per-tick and two-sets-per-three-ticks restart
rates. These are server coroutine tests; packet and animation timing still need
measurement in a live client.

Run the module's `test` task after `:or-cache:buildCache`. The tests load the real
server cache, force both XP multipliers to 1× and cover transaction conservation,
full inventory, exact stack boundaries, repeated clicks, incomplete sets,
disappearing ingredients/tools/space, expiring boosts, cancellation, real unlock
and quest progress, selected feather consumption, ratio yields, fine XP and
bounded make-all calculations. The new interaction pairs were checked against
the existing Crafting hand recipes and their actual trigger/tool expansion:
264 Fletching recipe variants intersected none of the 294 Crafting pairs.
Crafting retains ownership of uncut gems, amethyst heads/tips, spinning crossbow
strings, and leather-covered shields. A live check should also cover both item-use directions,
all make-X buttons, movement cancellation and settings toggles.

Recipe evidence was checked against the OSRS Wiki on 29 October 2026:

- [Arrow shafts](https://oldschool.runescape.wiki/w/Arrow_shaft) documents log
  requirements, yields and the two-then-three-tick cutting sequence.
- [Fletching training](https://oldschool.runescape.wiki/w/Fletching_training)
  gives bow levels, XP and cutting/stringing cadence.
- [Headless arrow production](https://oldschool.runescape.wiki/w/Money_making_guide/Fletching_headless_arrows)
  documents ten sets of fifteen per selection.
- [Fletching](https://oldschool.runescape.wiki/w/Fletching) gives arrow, dart and
  bolt requirements and per-projectile XP.
- [Bronze darts](https://oldschool.runescape.wiki/w/Bronze_dart) documents instant
  feathering and distinguishes it from the quest-gated Smithing step.
- [Unfinished bolts](https://oldschool.runescape.wiki/w/Unfinished_bolts) gives the
  metal bolt feathering requirements.
- [Settings](https://oldschool.runescape.wiki/w/Settings) documents the optional
  make-X interface for darts and bolts.
- [Bolt tips](https://oldschool.runescape.wiki/w/Bolt_tips) and
  [topaz bolt tips](https://oldschool.runescape.wiki/w/Topaz_bolt_tips) give gem
  yields, cutting XP and five-tick cutting, including 39 XP per ten topaz bolts
  on either steel or dragon bases.
- [Broad bolts](https://oldschool.runescape.wiki/w/Broad_bolts) documents the
  purchased unlock for broad feathering and amethyst broad tipping.
- [Crossbows](https://oldschool.runescape.wiki/w/Crossbows),
  [oak stock](https://oldschool.runescape.wiki/w/Oak_stock) and
  [blurite crossbow](https://oldschool.runescape.wiki/w/Blurite_crossbow) give
  stock, assembly and stringing requirements, including the quest gate.
- [Wooden shields](https://oldschool.runescape.wiki/w/Wooden_shields) and
  [oak shield](https://oldschool.runescape.wiki/w/Oak_shield) give the two-log
  cost and seven-tick action.
- [Javelins](https://oldschool.runescape.wiki/w/Javelin) gives head assembly
  requirements, XP and faster initial-set behavior.
- [Stripy feathers](https://oldschool.runescape.wiki/w/Stripy_feather),
  [gryphon feathers](https://oldschool.runescape.wiki/w/Gryphon_feather) and
  [stymphike feathers](https://oldschool.runescape.wiki/w/Stymphike_feather)
  document the ordinary feather alternatives.
- Individual [willow shortbow](https://oldschool.runescape.wiki/w/Willow_shortbow),
  [maple longbow](https://oldschool.runescape.wiki/w/Maple_longbow) and
  [magic shortbow](https://oldschool.runescape.wiki/w/Magic_shortbow) creation
  tables distinguish the exact stringing XP from rounded training summaries.

Remaining work includes Fletching knife effects, jeweller's chisel effects,
Imcando hammer support, bow string spools, ogre/brutal ammunition, specialist
weapons, newer seeking/atlatl recipes, totems, workshop interfaces and diary hooks.
Interface appearance, action interruption latency and animation/sound fidelity
still require comparison in a live client.
