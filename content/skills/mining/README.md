# Mining side drops

The side-drop plugin consumes successful Mining product events. Clue geodes use independent
eligible-tier rolls, visible Mining level, Arthur's vessel opt-out, and claimed Combat Achievement
rewards (`ca_tier_status_* == 2`). Completing X Marks the Spot selects scroll boxes through the
shared quest policy. Ownership and capacity use the Treasure Trails inventory policy.

`dbtable.mining_clue_geodes` supplies each geode, box, tier multiplier, CA variable, and Wilderness
eligibility. Rock base rates remain in `dbtable.mining_rocks`. No client or protocol changes are
required. Rebuild the cache before compiling this module after changing its pack.

## Rates and evidence

For each eligible tier, the implementation rolls once against this denominator:

```text
base = floor(rockBase * 7 / 10)
if matching CA reward claimed: base = floor(base * 95 / 100)
denominator = floor(base * tierTenths / ((100 + visibleMining) * 10))
if eligible imbued-ring Wilderness bonus: denominator = floor(denominator / 2)
```

Tier tenths are beginner 2, easy 17, medium 20, hard 33, elite 100. Beginner receives neither the CA
nor ring bonus. Normal and imbued rings are distinguished, including all charges. The formula,
independent rolls, visible boosts, and modifier ordering follow the developer statements collected
on [Clue geode](https://oldschool.runescape.wiki/w/Clue_geode) (Mod Ash, March 2023). The 2024 rate
change is also documented in the official [Project Rebalance update](https://secure.runescape.com/m=news/project-rebalance-skilling--poll-81-mta-changes?oldschool=1).

Four verified cache corrections accompany this change: gem rocks 211,886; sandstone and granite
741,600; volcanic sulphur 710,000. Other rock data is not represented as newly verified.

The [elite geode page](https://oldschool.runescape.wiki/w/Clue_geode_(elite)) states that imbued rings
double the easy-through-elite rates in the Wilderness. The main geode page cautions that this has
not been reconfirmed since Combat Achievements. This implementation retains that documented
benefit; the post-CA confirmation and its precise rounding order remain evidence limitations.

Integer arithmetic implements the published formula without intermediate floating-point error.
For example, clay at Mining 1 gives an elite denominator of 51,398. A wiki table displays 51,397,
consistent with binary floating-point rounding of `741600 * 0.7` before flooring. No captured
server-side trace has established that single-unit boundary; tests distinguish the chosen exact
formula from that calculator discrepancy.

## Delivery and crystal shards

Each successful roll adds exactly one item to the inventory or creates one private ground item.
Ground rewards are bound to the player UUID, including in group modes, and never reveal publicly.
At scroll capacity, the shared reminder setting suppresses the roll or sends a successful surplus
box directly to the ground. Inventory overflow cannot consume the existing stack.

Trahaearn rewards use the mined rock's region. Each successfully awarded resource, including a
bonus resource, rolls independently at 1/65. Full inventories drop the shard instead of losing it.
The rate is confirmed by the official [September 2024 update](https://secure.runescape.com/m=news/araxxor-cas-poll-82-updates--more?oldschool=1).
Extra-resource rolls and full-inventory ground delivery are described on
[Crystal shard](https://oldschool.runescape.wiki/w/Crystal_shard) and
[Mining iron ore](https://oldschool.runescape.wiki/w/Money_making_guide/Mining_iron_ore).

## Validation and remaining work

Run `:content:skills:mining:test` after `buildCache`. The deterministic suite checks rate boundaries,
enumerates every possible random draw for three tier denominators and the shard roll, and tests
ownership, quest policies, CA states, ring charges, opt-out, capacities, inventory overflow, and
private ground-item conservation. These tests do not require a live client.

This is not a full Mining parity claim:

- Existing lead, nickel, soft-clay, essence, salts, daeyalt, rubium, ancient-essence, and infernal-shale
  base rates or geode eligibility still need authoritative verification. Existing nonzero values
  remain active; this patch neither invents replacement rates nor treats absence from a wiki table
  as proof that a source is ineligible.
- Modern rock drop tables describe geodes as tertiary rewards, while older overview prose says
  they replace ore. This patch retains tertiary delivery. A live capture should resolve that
  wording conflict, enhancer-specific clue rerolls, and exact message/ground timing.
- The existing ground-loot lifetime is retained. Cross-world/logout persistence and the full clue
  ground-item lifecycle are separate unfinished shared systems.
- Zalcano and other dedicated Mining activities need their own correct event integration; this
  patch covers the existing ordinary-rock product source and Trahaearn region only.
- The world currently exposes members content without a world-level F2P policy. This module does
  not reinterpret the account membership flag as a new content restriction.
- Claimed CA flags are respected; the complete achievement tracker/reward lifecycle remains
  separate work. Higher-tier clue-solving and legacy geode opening are also outside this module.
