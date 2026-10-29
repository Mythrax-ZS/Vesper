# Player combat authorization

Normal player attacks and manually targeted combat spells require a positive
`PvPContextPermission`. `PvPContextAuthorizationHook` is bound into the existing
`PvPAttackValidateHook` set, so the same requirement also applies to Ancient spell
secondary player targets, including casts whose primary target is an NPC.

An empty permission set denies combat. A legacy validator returning `Pass` only
abstains; it cannot grant an area or match. A grant does not bypass skull prevention,
combat brackets, restricted weapon checks or any other veto. Manual spell rune
preflight still runs before combat validation.
Authorization rejects self-targeting, invalid or switching participants, different
physical planes and different save modes. The latter is Vesper's mode-isolation
rule, not a claim that OSRS permits different progression modes on one world.

## Wilderness context

The Wilderness content plugin supplies the current positive provider. Both players
must be in the packed `area.wilderness` according to their current coordinates.
Cached attack-menu flags and `inside_wilderness` UI state are not permission. A
copied area also requires both players to occupy the same live allocated region;
matching template coordinates in separate instances is insufficient.

Wilderness depth is calculated from the normalized source coordinate, including
its source plane. The existing depth formulas are retained. This corrects copied
high-Wilderness coordinates falling through to level one. The retained
`minOf(attackerDepth, targetDepth)` bracket rule at unequal depths still needs
independent live confirmation; the same-depth inclusive limit is
[documented by the OSRS Wiki](https://oldschool.runescape.wiki/w/Wilderness?oldid=15384927).

The Wilderness rule and skull/hit callbacks now use current areas rather than the
cached menu flag. This allows a valid first-tick or restored player to fight and
receive the normal skull behavior. The existing persisted Wilderness opt-out is
still respected for either player. It is a legacy Vesper option, not an OSRS rule.

These are admission checks. Existing range, line-of-sight and single/multiway
checks remain in their existing paths. This change does not cancel hits that were
already launched when a participant later changes position.

## Ferox and other remaining rules

The current Ferox polygon is excluded from Wilderness and remains excluded even
with a teleblock variable set. The old teleblock branch used that same polygon,
but its later membership check denied a player once the cached area flag caught
up. It did not implement a reliable exception and has been removed.

Exact Ferox behavior is unfinished. Jagex's
[Ferox launch](https://secure.runescape.com/m=news/ferox-enclave?oldschool=1)
distinguishes the safe interior from an exterior protected strip where a
teleblocked target can be attacked. The current local polygon has not been proved
to distinguish those areas, and sources disagree on some initiation and
retaliation edges. The broader boundary must not be made attackable based only on
either player's teleblock flag. Barrier gameplay also remains missing.

The subsequent [Ferox geography correction](ferox-safe-areas.md) excludes the
reachable upper floors through the town area and the isolated basement through `area.ferox_safe_zone`.
It includes the upper-floor shoulder missed by the ground outline and preserves
neighboring Wilderness areas. The town membership includes its upper floors so
confirmed blighted-supply permission survives the safety correction; the separate
basement marker does not grant unverified supply permissions.
The ground-floor apron, barriers and PvP-world exceptions still require work.

Dedicated PvP worlds require their own world rules, safe zones and continuation
behavior. `WorldType` selects a character save and is not a PvP-world switch.
Seasonal rules, singles-plus/PJ details, real minigame sessions and exact denial
wording remain unfinished. Denial messages introduced here are server-authored.
The [source assessment](../../.tools/parity-validation/pvp-authorization/assessment.json)
separates confirmed rules, local evidence and unresolved behavior. The
[packed-area probe](../../.tools/parity-validation/pvp-authorization/packed-areas.json)
verifies local data, not the correctness of every boundary against Jagex's server.

## Plugin migration

An arena or world plugin must bind a `PvPContextPermission` backed by authoritative
current membership, match phase, accepted rules and instance identity. A static
allow value, client setup fields or a `PvPAttackValidateHook.Pass` is insufficient.
Keep its existing veto hooks; a positive provider cannot override their denial.
Shadow's `MinigameStaffPvPPermission` is a separate weapon restriction and does not
authorize the combat context by itself.

The existing validation interfaces and combat constructors are retained.
`AreaChecker.normalizedCoords` is additive. Custom dispatchers that invoke combat,
weapon or spell handlers directly must perform the same shared validation; those
lower-level methods do not independently re-run the whole admission pipeline.
