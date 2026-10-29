# Bird snares

This module implements bird snares for crimson swifts, golden warblers, copper
longtails, cerulean twitches, and tropical wagtails. It is a partial Hunter
implementation, with the timing and lifecycle limitations below.

`BirdSnareManager` keeps live traps and exclusive bird reservations. A trap can
only catch a matching NPC that already exists nearby. The bird uses ordinary NPC
movement to reach the trap before the catch roll. A successful catch despawns that
NPC through its normal repository; failed catches release it. The existing map
spawns provide the birds.

The generated `HunterSnareBirdsRow` table supplies species, native level-guide
rows, visuals, reward quantities, XP, and success-roll endpoints. All referenced
NPC, item, and location types are resolved to the loaded cache definitions. The
trap-limit enum uses native guide levels: two traps at level 1, then three, four,
and five at levels 40, 60, and 80. Visible boosts can unlock prey and trap limits;
invisible levels only enter the existing catch calculation. The Wilderness extra
trap rule for black chinchompas and black salamanders does not apply to these birds.

Lay consumes one snare from the selected stack and creates one trap. Placement is
immediate and does not lock movement. Check returns the snare and caught products;
Reset claims the products while keeping the snare placed. Broken Reset is a
world-only transition and works with a full inventory. Dismantle returns an empty
or broken trap. Inventory transfers are prepared before changing the world, and
only committed after that world change succeeds. Failed capacity/overflow checks
leave the trap, its fixed reward roll, and XP untouched.

The native ground-item Lay option routes the player onto the item's tile, then
converts exactly one registered snare into a trap. Ground visibility, save mode,
expiry, current level, trap limit, and the existing ironman pickup rules are
checked before placement. A remaining ground stack keeps its identity, ownership,
and original reveal/despawn timers. Failed placement leaves it untouched. This
flow currently works with a full inventory because it does not move the snare
through inventory; that exact OSRS capacity behavior remains unverified.

Check and Reset use cancellable coroutines. Moving or starting another action can
cancel the animation wait. The trap identity, ownership, location, expiry, and
inventory are checked again before any reward. Only its owner can claim it.

Open, full, and broken traps have 100-tick lifetimes; reaching full or broken
starts a fresh lifetime. Expiry and logout currently convert the trap into one
owned ground snare using `PlayerDropTimers.resolve`, with no inventory refund.
Unclaimed caught products and XP are forfeited. A failed ground insertion retains
the trap for retry; a failed subsequent location deletion cannot emit another
snare. World-type changes use the same cleanup, preserving the save mode captured
when the trap was placed. Its resulting ground snare cannot cross into another
save mode, including after public reveal.

## Evidence and remaining parity gaps

- [RuneLite's Hunter plugin](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/hunter/HunterPlugin.java)
  establishes placement and movement in the same tick, and resets its trap timer
  on full/broken states. [HunterTrap](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/hunter/HunterTrap.java)
  sets that timer to one minute. These client observations support the current
  implementation; they do not disclose every server rule.
- The three-tick search interval and two-tile radius are **inferred from box
  traps**, whose mechanism Mod Ash described. Bird-specific attraction cadence,
  range, and competition ordering remain unverified. There is no extra random
  timer that manufactures a catch when no bird is present.
- Native cache sequences drive the visuals. Animation-derived waits for checking,
  resetting, landing, and transitions are provisional. Animation length is not
  proof of native action timing. Landing contains 26 client cycles followed by a
  10,000-cycle held frame; capture contains 27 cycles followed by a 200-cycle hold.
  The cache library estimates one tick for each after omitting the held frame;
  OpenRune's server conversion adds five cycles and produces two ticks. Neither
  estimate establishes the OSRS script delay. Native failing, broken, trapping,
  and full location definitions already supply their own animations. Exact
  placement cooldown and automatic player
  stepping remain unverified; the player currently moves off the trap manually.
- Caught NPCs use their configured `respawnRate`. Its present default originates
  in OpenRune's conversion, not verified OSRS bird respawn data.
- The ground conversion on logout/expiry, forfeiture of unclaimed products, and
  ordinary ground-item visibility/lifetime are interim rules. Bird-specific
  logout, hopping, death, instances, and ground-item rules remain unverified.
  Neither the snare item's static spawn timer nor RuneLite overlay cleanup is
  evidence for those rules.
- Capacity/overflow failures currently preserve every item, the placed trap, its
  fixed catch roll, and XP. Exact OSRS bird-snare slot gates, product handling with
  a full inventory, ground Lay's intermediate inventory use and treatment of a
  multi-snare ground stack, and bonecrusher/meat-pouch interactions remain
  unverified. The native item definition establishes the ground Lay option, not
  those server rules.
- The per-species Wiki gives 61.2 XP for copper longtails and 95.2 for tropical
  wagtails; RuneLite calculator values still differ. Cerulean twitch's 64.5 XP is
  corroborated. Current recipe values follow the individual Wiki pages and are
  base XP; realm/player multipliers still apply.
- Smoking, anti-odour salt, Horn of Plenty, Hunter outfit-specific bonuses, Guild
  rumours, native Hunter-overlay timers, and other
  Hunter techniques are not implemented here. Existing general XP/invisible-level
  modifiers are honored if provided. Mod Ash's cited smoke bonus is +2%, but the
  [salt documentation](https://oldschool.runescape.wiki/w/Anti-odour_salt) explicitly
  leaves additive versus multiplicative arithmetic unresolved. No guessed smoke
  probability is applied and salt is not consumed without its effect.

The checked Wiki revisions include [Bird snare, revision 15368303](https://oldschool.runescape.wiki/w/Special:PermanentLink/15368303),
[Hunter, revision 15375251](https://oldschool.runescape.wiki/w/Special:PermanentLink/15375251),
[Copper longtail, revision 15385034](https://oldschool.runescape.wiki/w/Special:PermanentLink/15385034),
[Cerulean twitch, revision 15381175](https://oldschool.runescape.wiki/w/Special:PermanentLink/15381175),
and [Tropical wagtail, revision 15381180](https://oldschool.runescape.wiki/w/Special:PermanentLink/15381180).
[Source receipts](evidence/sources.json) record all five birds, revision dates,
source hashes, RuneLite references, and the precise box-trap inference sources.
[Native client definitions](evidence/native-client-definitions.json) preserve the
symbolic location and raw animation evidence from the revision-241 cache.
[Native item options](evidence/native-snare-items.json) confirm ground op 4 Lay,
ground op 3 Take, inventory Lay, and snare/salt stackability.

Jagex's [7 October 2026 update](https://secure.runescape.com/m=news/pete-kayers-challenges?oldschool=1)
adds Reset, and the [19 August 2026 Hunter update](https://secure.runescape.com/m=news/summer-sweep-up---hunter--skilling?oldschool=1)
adds two traps at level 1. These changes are reflected in the loaded cache.

## Validation

`BirdSnareTest` exercises production manager/script methods using real inventory
transactions, location/NPC/object repositories, Hunt queries, and NPC movement.
It covers single ownership, limits and visible boosts, all five catches, no-bird
and ineligible-bird cases, exclusive reservations, failure/reset, full inventories,
stack overflow, stale inputs/locations, repeated requests, cancellation, expiry,
ground insertion/deletion failure, and logout. Reserved birds release promptly on
blocked/exhausted routes; external interactions and reused NPC identities survive
cleanup. Native deletion/respawn events invalidate reservations even if a respawn
keeps the same NPC reference and UID. A suspended check cannot claim a replacement
trap at the same tile. Mode-switch collapse preserves the original save mode. XP
checks use explicit 1x rates. These tests establish the implemented invariants;
they do not verify the chosen values against OSRS. Native-client observation is
still needed to close the timing and lifecycle gaps.

Ground-Lay tests execute the actual approach handler, player interaction processor,
and player routing. They cover blocked arrival, cancellation, replaced sources,
full inventory, stack limits, failure rollback, source timers, count packets,
save-mode visibility, and the production ironman take hook. These establish item
conservation for the chosen flow, not native inventory-slot or action timing parity.
