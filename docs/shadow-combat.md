# Tumeken's Shadow combat rules

Vesper separates equipment-panel bonuses from the bonuses used for a particular
attack. Holding a charged Shadow does not give its passive to a spellbook cast.
PvP formulas also use the ordinary equipment bonuses.

For the built-in PvM attack, the equipment magic-accuracy sum is multiplied by
three, or four in the Tombs of Amascut raid area, before the formula adds 64.
Negative equipment accuracy keeps its sign. Effective-level prayer and Void
accuracy effects remain in their usual formula stages.

The equipment magic-damage sum is multiplied by the same factor and capped at
100%. Elite Void's 5%, prayer and eligible Salve/Avarice additions are applied
after that cap. Slayer's multiplier follows the resulting damage calculation.
The base maximum remains `magicLevel / 3 + 1`, with integer division. This change
preserves the existing rounding order outside the corrected passive.

## Evidence and location scope

The [live launch update and August 26 hotfix](https://secure.runescape.com/m=news/tombs-of-amascut?oldschool=1)
establish the equipment cap and restrict the passive to the built-in spell.
The [September 8 update](https://secure.runescape.com/m=news/tombs-of-amascut-ornament-kits--more?oldschool=1)
raises both multipliers to four within the raid. The
[May 2024 combat rebalance](https://secure.runescape.com/m=news/project-rebalance-combat-changes?oldschool=1)
excludes prayer bonuses from the multiplier. Detailed ordering is corroborated by
the [Wiki calculator's equipment calculation](https://github.com/weirdgloop/osrs-dps-calc/blob/c1f7737a7fea01adc9d0c1fe59e88d4584c5bc48/src/lib/Equipment.ts#L345).

The server checks the caster's location against `area.toa_raid`, supplied by the
Desert content pack. `AreaChecker` normalizes copied and rotated instance zones
through `RegionRegistry` before querying the packed area index. The area includes
the twelve raid room map squares, including the Nexus and Tomb, on all four
planes; the external lobby is excluded. Geometry comes from the maintained
[RuneLite ToA room model](https://github.com/LlemonDuck/tombs-of-amascut/blob/831c39b9d60f40965d221fe5ac6b4d19d61c97f4/src/main/java/com/duckblade/osrs/toa/util/RaidRoom.java).

Using that client room classification as a server combat boundary is an inference,
not a disclosure of Jagex's server predicate. Unused tiles and planes have not been
verified through live gameplay. Raid difficulty and party UI variables are not
membership checks: level zero is valid, and a ready party can still be in the
lobby. The target NPC's `amascutnpc` parameter does not control this passive.

## API migration

- Combat accuracy callers use `WornBonuses.offensiveMagicBonusBase`; the old
  `offensiveMagicBonus` retains its equipment-panel result.
- Shadow damage callers use `equipmentMagicDamageBonus` and
  `eliteVoidMagicDamageBonus` separately. `magicDamageBonusBase` still combines
  equipment and Elite Void for ordinary spell/PvP callers.
- The six-argument `MagicMaxHitOperations.modifyStaffBaseDamage` overload accepts
  those separate values. The old five-argument overload remains binary compatible
  but cannot recover a set bonus from a combined number and is deprecated.
- Production obtains `CombatMagicAttributeCollector` through dependency injection
  or constructs it with `AreaChecker`. The retained zero-argument constructor has
  no area context and cannot activate the raid bonus. The collector adds
  `TumekensShadowInAmascut` only for a charged Shadow inside the packed area.

## PvP permission

Shadow's built-in player attack now needs permission from a
`MinigameStaffPvPPermission` provider. With no provider authorizing the opponent,
the normal PvP dispatcher stops the interaction before changing the attack clock,
special selection or energy, charges, launch effects, hit queues, XP or PK state.
This applies to the charged and uncharged registered staff variants. A manual
spellbook cast while holding Shadow follows the ordinary spell path.

The [current powered-staff rule](https://oldschool.runescape.wiki/w/Powered_staff?oldid=15385782)
allows Shadow's built-in spell in Castle Wars, Fight Pit, Soul Wars and Clan Wars
with the leaders' enabling rule. Jagex's
[Soul Wars Shadow fix](https://secure.runescape.com/m=news/more-leagues-iv-changes?oldschool=1)
corroborates an allowed context; a permanent ban on all PvP would be incorrect.
The current server has none of those minigames' active match/rule implementations,
so its permission-provider set is empty and unsupported player casts are denied.

A provider must consult its real match lifecycle for both participants, the combat
phase, opponent eligibility and accepted weapon/magic rules on every attempt.
Client setup variables, team capes or teleporting into an arena are insufficient.
These are server authorization requirements, not a claim to know Jagex's internal
predicate. Providers never bypass the existing general PvP validation hooks.
The shared [PvP context check](pvp-authorization.md) now requires a separate
positive area or match permission before a staff-specific grant can be used.

`MagicWeaponPvPValidator` is an optional interface; existing weapon interfaces and
the PvP dispatcher's constructor are unchanged. Custom code that directly invokes
a registered weapon handler must honor this validator first. The low-level
handler's Boolean still means "handled", and cannot represent a denied attack.
The preserved one-argument `TumekensShadowWeapons` constructor uses no permission
providers; dependency injection supplies the current set to the new constructor.

The denial message is server-authored. Exact live wording and ordering relative
to an empty charge counter or a prior attack delay have not been established.
See the [permission evidence](../../.tools/parity-validation/shadow-pvp-permission/assessment.json).

## Remaining work

The equipment and bank bonus panels still use the inherited display calculation;
its raid context, cap and Elite Void presentation need separate validation and
changes. Real permitted PvP minigame sessions and their permission providers remain
unfinished. The default denial and a synthetic provider test do not establish the
positive OSRS minigame behavior.
No full ToA encounter or raid-session implementation is supplied by the area pack.

Regression coverage includes native equipment formula oracles, actual registered
casts and rune/charge accounting, plus packed-area and copied-zone context checks.
Build receipts record which checks were executed. These tests do not replace
live encounter or client acceptance.
