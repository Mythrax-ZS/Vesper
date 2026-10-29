## TODO
- Explain types' computeIdentityHash.
- Nested EnumTypes in EnumProvider. Due to type-erasure, it is extremely tedious
to even begin to try and make the inner EnumTypes type-safe (checked during
boot-up). For now, we will just trust that the developers are careful (BAD!),
but solely because the other choice is to load them via reflection and try to
do comparisons that way, which may have a significant impact in future
scalability when it comes to boot-up performance.
- InvTransactions uses a cached singleton. It would become annoying for coders
to have to constantly get access to the required "lookup" maps in order to
perform an inv transaction. We willfully allow this antipattern for ease-of-use.
- Private type reference subclasses not allowed. Must be internal or public.
- .local in symbols directory being loaded by default and overrides any possible
colliding symbols from the root symbols.
- All game-related randoms should be generated through `GameRandom` functions.
- The great teleport/telejump tragedy of 24' where we had to choose between
anti-patterns that would blow a huge hit to testability or jeopardizing
user-friendliness by requiring the what-should-be simple teleport functions to
take a [CollisionFlagMap] instance arg. We went with the latter. The biggest
weakness of this approach is increasing the barrier-to-entry when a developer
wants to perform simple tasks. Teleporting _should_ be a simple task, but alas,
if we want to emulate core mechanics, teleporting needs to _instantly_ update
the collision flag map. We weighed our options from using singletons of the
collision map, to passing the collision map to each player/npc, to having a pub
sub system strictly for map changes - though at that point it'd be practically
the same thing as passing the game map pointer to the entities in question.
The solution of taking in the collision flag map as an arg is something that
will, *eventually*, solve itself by way of adding API-friendly wrapper classes
for systems such as "fade out" teleports, teleporting after interacting with a
loc, and so on. Though admittedly, it's a cumbersome ask for beginners to have
to deal with until those systems are in place.
- Decision to not make ClientScript arguments type-safe. Just not worth it,
especially if you consider that the arguments would have to be converted to
their primitive form dynamically during runtime.
- `delay` functions, AKA `p_delay`, do _not_ follow the "official" convention.
This means that calling `delay(1)` will delay the player by 1 tick, not 2.
`delay(0)` will not delay the player at all.
- Mention the purpose of each api submodule (in a README within api module).
- Inconsistency in OpLocHandler debug message vs. LocUInteractions debug
message. This comes down to the packet handler not having the detailed view of
the multiloc based on the player's vars. Meanwhile, OpLocU (as well as OpNpcU)
are handled at the "content-level" due to how they were changed from being
individual packets. The content-level scripts are exposed to the "visual" type
for these entities.
- Tests that involve obj transactions ([org.rsmod.objtx]) are not thread-safe
and the test class should be marked with `@Execution(ExecutionMode.SAME_THREAD)`
- `LocRegistry.findAll(zoneKey)` performed within a region's zone will not
return locs that did not originally belong to the copied normal zone. This is
intentional by design to prevent inconsistencies and unexpected behavior. This
scenario is rare but can occur under specific conditions - when a loc's angle,
dimensions, and the requested region rotation cause it to shift into an
unexpected region zone. This is an extremely niche case.
- Similarly, `findAll` performed in a region will skip any locs that have
shifted into a different zone.
- Any interaction on locs in region areas that are outside the region's working
area are discarded/ignored.
- If multiple zones in a region are copies of the same zone,
`Region.normal[...]` will resolve to the last one of these registered zones.
Since regions register zones column by column, from bottom to top, this means
the zone closest to the north-east boundary takes precedence.
- Zone update processing occurs in a fixed order, from the bottom-left build
area zone to the top-right zone. This means all `ZoneProt` updates are sent in
a predictable order, rather than in the order the updates occurred. For
example, if `zone(0, 1, 0)` receives a `soundarea` update first and
`zone(0, 0, 0)` receives one afterward, the latter will still be sent first due
to fixed iteration order; whereas the official game would send `zone(0, 1, 0)`
first since it was the earliest update.

## Level-up popups

`LevelUpScript` sets `varbit.busy` while `levelup_display` is open and clears
it through the interface close hook. Keep cleanup tied to the interface,
not the suspended dialogue: walking or a hit can cancel that dialogue.
Disabled popups do not set the flag.

## Types
```
engine
    └─ game
        └─ type
```
#### <u>_Internal `id` field_</u>
```kotlin
class ItemServerType(internal var id: Int?)
```
There was a conscious decision to allow for configuration types' "id" to be
mutable, and nullable.
#### What problem does this solve?
- Type-safety:
  - For years, frameworks used magic numbers for config types, whether it was
    for worn obj conditions, or to define a rewards table. This comes with
    obvious flaws (see [magic-numbers]). With time people moved onto static
    classes that held each individual id with a reasonably-named field. Again,
    this still comes with its problems. For example, you are still able to
    compare an obj to a constant that's not an obj:
    `if (player.righthand == Spotanims.iron_arrow_projectile)`.
- Consistency between references and configs in cache:
  - Another major flaw with the traditional approach, is that there was never a
    feasible way to make sure all references actually exist in the game cache.
    As the developer, you just had to trust that these constants were up-to-date
    and their naming stayed 100% true to what they originally meant to
    represent.
- Creating custom config types:
  - TODO:
#### What are the pros and cons?
- Cons
  - Can be abused and the `id` may not be accurate after runtime if a developer
    sees fit to modify it.
  - TODO:
- Pros
  - TODO:

## NPC damage and splash outcomes

NPC attacks with `NpcDamageContext` reduce sampled damage before the stored launch
HP cap, prayer and absorption. Ordinary NPC queues still capture the HP limit at
launch; deferred hits retain their impact-time modifier policy. Legacy NPC hits
without a context keep their previous cap and modifier behavior. Do not apply
equipment reductions inside an NPC maximum-hit calculation or a custom boss
expression that also opts into shared mitigation.

`Hit.isMagicSplash` records an explicit splash before modifiers. A false value is
not proof of accuracy, and final zero damage cannot distinguish a splash from an
accurate hit reduced by armour. Copying hits must preserve the marker.

Flat armour is a persistent per-hit stat for positive player melee/ranged damage;
it is not consumed as an absorption pool. Magic (including splashes), typeless and
non-player hits bypass it. Style immunity and incoming zero damage stay zero, so
negative armour cannot revive a blocked hit or a physical miss. Positive armour
can reduce a successful hit to zero. A separate physical accuracy outcome would
be needed to distinguish an accurate zero from a miss after earlier modifiers.

Static NPC armour comes from the server-only `enum.npc_flat_armour` in the generic
NPC pack. `Npc.flatArmour` reads the current `visType` baseline unless
`varn.flat_armour` is present. A stored zero is an override, not an absent value;
`clearFlatArmourOverride()` restores the baseline. Overrides survive a transmog
until cleared, while respawn clears them and resets the visible type. Queued hits
retain the armour calculation performed at queue time.

The table contains 149 main-game NPC definitions, with source revisions and
exclusions in the pack's `flat-armour-sources.json`. Amoxliatl now uses this data
instead of a spawn-time constant. Dusk's first-form definitions have -1; its
second-form definitions keep the default zero. Eclipse Moon's regular baseline is
6, but its eventual clone-phase handler must temporarily override that to 4 on the
actual damage recipient. The clone actor's name alone does not establish that
recipient. Echo/Deadman-specific rows are excluded. These values do not implement
the corresponding encounters or their immunity, phase and world-mode behavior.

Physical PvN XP callers use damage from the returned queued `Hit`, after hit
modifiers, immunity and flat armour. The existing grouped attacks add their queued
damage against each NPC before one XP award, retaining their current HP cap and XP
rounding. Venator retains separate primary/bounce awards. Its bounced hits suppress
only negative flat-armour bonuses; positive armour, immunity and hooks still apply.
This exception follows the current Wiki correction and lacks primary/live
confirmation. Venator's target-selection and XP timing are not independently
verified against the current game. Public prose describes ricochets after a hit,
but does not establish when the server chooses or rolls their targets.
XP is still awarded at launch; a later HP change or impact
floor does not retroactively recalculate it. Modifiers and armour are not run again
for XP. NPC XP bonuses, account/world rates and combat-style splits remain in the
existing XP calculation.

The public integer-based XP APIs retain their signatures and behavior. External
physical-combat plugins must pass queued damage rather than raw rolls, retaining
the verified award boundaries for their attacks. Magic/cast XP and PvP damage bases are separate policies;
the physical PvN correction does not certify their ordering or encounter exceptions.

## Weapon launch state and depletion

Ordinary ranged attacks, Dark bow and Venator resolve damage and capture queued
weapon/ammunition identity before consuming their ammunition. Removing the final
arrow first loses its strength bonus. Removing a final thrown weapon also loses
its accuracy/strength and can incorrectly enable unrelated quiver bonuses.
Misses still spend ammunition; empty-quiver validation still precedes firing.

Venator and Tumeken's Shadow check charges before firing and spend one charge
after their shot/cast is queued. Empty charged variants retain the existing
normalization to the uncharged item. The final paid attack retains charged weapon
identity, and then stops continued attacks when charges reach zero. Shadow's
charged identity is required by its accuracy and damage attribute collectors.
The exact live transformation tick is not established by these launch-state tests.
Magic XP rules and the separate Shadow PvP/ToA multiplier gaps are unchanged.

Venator's original victim must pass the same next-target eligibility predicate as
other candidates: current registered object, plane, availability, attack option,
existing centre-distance limit and attack-validation hooks. A dead origin or
miss does not itself stop onward ricochets. The existing nearest-target ordering,
geometry and synchronous scheduling remain subject to current-game verification.

## Player damage rolls and PvP equipment reduction

Successful damaging player attacks sample their full inclusive range, then convert a
rolled zero to one. A maximum of three has outcomes `1, 1, 2, 3`; sampling uniformly
from `1..3` changes the distribution. Non-damaging attacks and failed accuracy stay
zero. Positive spell minima keep their original range. Later armour, prayer and
immunity can reduce an accurate hit to zero again.

Multi-hit handlers calculate one whole-attack maximum before splitting it. A rounded
zero subhit of a damaging attack remains eligible for the minimum hit. Dual
macuahuitl's second accuracy check depends on the first accuracy result, not on
damage after mitigation. This does not implement missing scythe area targeting or
other weapon-specific effects.

PvP maxima no longer sample Elysian's proc. Standard typed player combat queues set
`pvpDamageReduction` explicitly and apply it to each sampled hit before prayer.
Direct legacy queues default to no equipment policy; external callers must opt in
only when their damage has not already been reduced. Typeless and other special
damage do not acquire eligibility merely from their source. NPC mitigation remains
its separate policy. Existing Elysian residual-floor rounding and prayer ordering
remain provisional pending stronger live evidence.

The original queue overload and modifier constructors, including Kotlin default
signatures, remain available to compiled plugins. External combat plugins that
use the now-raw PvP maximum helpers must adopt the explicit queue policy; retaining
their binary entry points does not migrate their combat logic automatically.

Combat XP and primary PvP hit callbacks still consume their caller's pre-queue
damage. It is now pre-Elysian as well as pre-prayer, unlike the old reduction of
the sampled maximum. Exact OSRS PvP XP ordering has not been established. Do not
replace every callback input with final damage: Corruption uses it as a success
proxy, and a successful minimum hit can become zero through mitigation. A complete
accuracy/XP outcome model and special-hit callback coverage remain open work.

## Player timer phases

Soft timers normally run before interactions. `softTimer(..., afterInteractions = true)`
opts a timer into the pass immediately after player interactions. Foundry work uses this
phase so a tool re-click can affect the work consumed on that same tick. Rescheduling
moves a timer between phases; clearing it removes either registration.

Global effects tied to player work use `StartCycle`, before the map clock advances,
so their animations and sounds share the player's work tick.

## Foundry item safety

`PlayerDeathPreDropHook` runs before carried items are captured for death drops,
including administrator deaths that bypass drops. Foundry stores the preform here,
before a grave or ground stack can claim it. Equipment replacements store displaced
preforms after the equipment transaction commits. Teleport validation does not
store a preform; a successful coordinate change does.

Sword hand-in and ore-pack conversion use all-or-nothing inventory transactions.
Failed payment leaves the sword, commission and reward state unchanged. Coins go
to the bank first, then inventory if necessary; Ultimate Ironmen use inventory only.

[magic-numbers]: https://en.wikipedia.org/wiki/Magic_number_(programming)
