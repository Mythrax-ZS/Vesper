# Ferox safe-area geography

The Wilderness pack extends `area.ferox_enclave` to its reachable upper floors
and excludes the basement through a separate `area.ferox_safe_zone`. Normal player combat, Ancient
secondary selection, Wilderness depth and area events consume the same packed
membership. Copied zones use their normalized source coordinates.

Jagex describes the town as safe, with three northern lobby floors, in the
[Ferox launch](https://secure.runescape.com/m=news/ferox-enclave?oldschool=1).
Its [30 April 2025 update](https://secure.runescape.com/m=news/poll-84-batch-ii?oldschool=1)
also corrects unsafe southern upper-floor tiles on PvP worlds. The
[Ferox dungeon](https://oldschool.runescape.wiki/w/Special:PermanentLink/15211887)
is outside the Wilderness. These sources establish the intended behavior;
the local revision-241 map provides the geometry.

| Physical plane | Correction | Packed tiles |
| --- | --- | ---: |
| 1 | Town footprint with the northwestern upper-floor shoulder included | 865 |
| 2 | Existing town footprint applied to its second upper floor | 827 |
| 0, underground | Isolated dungeon square, inclusive x=3136–3199, z=9984–10047 | 4096 |

The first-floor shoulder covers 17 occupiable authored tiles missed by simply
copying the ground outline. The corrected polygons contain all 278 and 231
occupiable authored Ferox floor tiles examined on planes 1 and 2. Plane 3 has
roof tiles but no actionable native transport to them; this correction does not
classify those roofs. The separate eastern Wilderness tower remains attackable.

The dungeon has native Ferox exits, its Soul Wars portal and abandoned lobby
scenery. Native terrain, loc definitions, bridge flags and the production step
validator show no traversable crossing from its square to an adjacent square,
including diagonals. All 112 examined static components remain within it.
Much scenery authored on plane 1 resolves to physical plane 0 through bridge
flags. The northern and western Wilderness caves remain outside the exclusion.
The [geometry assessment](../../.tools/parity-validation/ferox-safe-areas/assessment.json)
records the evidence, playable controls and exact polygons.

The ground-floor town footprint stays unchanged. Its upper floors are also part
of `area.ferox_enclave`, preserving their blighted-potion eligibility after they
lose Wilderness status. Blighted food now uses the same town exception as potions,
matching Jagex's [13 November 2024 update](https://secure.runescape.com/m=news/guardians-of-the-rift-changes-out-now?oldschool=1).

The basement safety marker does not grant supply permission. Removing its erroneous
Wilderness status makes the current local handlers deny blighted food and potions
there. The examined sources establish town permission but do not settle basement
eligibility; this denial is not a verified OSRS prohibition. The later
[rune-sack and control-spell checkpoint](rune-sacks.md) adds sack payment in the
supported areas and Mage Bank permission for sacks, food and potions. Other safe
lobbies and PvP-world supply contexts remain unfinished. Surface apron geometry
is unchanged; its exact teleblock, retaliation and barrier behavior remains unfinished.
This is not a complete PvP-world or minigame implementation.

## Packing and verification

The pack contributes a metadata-only Wilderness entry with the new exclusion,
two upper-floor town polygons and a basement polygon. The packer combines entries by resolved
gameval before rasterization and unions their include/exclude lists. It emits the
same complete relationships for full-square, zone and individual-tile areas.
This avoids losing plugin exclusions or earlier relationships when map archives
are loaded in a different order. Public runtime combat interfaces are unchanged.

Regression tests exercise metadata composition and enable/disable transitions,
native and rotated-copy permission checks, actual packet/event combat dispatch,
resource preservation on denial, legitimate adjacent combat, and already queued
hits after moving into safety. Consumable tests use registered food and potion
handlers and real inventory transactions. They do not simulate live walking or barrier clicks.
The independent cache audit compares every existing area, checks the 5,788 intended
Wilderness removals, 1,692 upstairs town additions and 4,096 basement-marker tiles,
and preserves all other memberships. Native static geometry
and local runtime tests are not a recovered Jagex server predicate or a live OSRS
acceptance test.
