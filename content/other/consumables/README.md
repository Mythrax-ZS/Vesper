# Consumables parity notes

The Ring of endurance now uses the native current charged and empty item types,
`varobj.stamina_charges`, and the existing potion cache rows. Standard stamina
potions and mixes add or consume one ring charge per dose; extended stamina uses
two. The maximum is 1,000 charges, with the passive reduction enabled at 500.
Drinking an eligible potion with enough worn-ring charges doubles its energy
restore and duration. Potion replacement and ring-charge consumption commit in
one inventory transaction. Manual uncharging returns the empty ring and discards
its charges; it does not return potions.

The enhancement flag is a permanent varp paired with the existing saved stamina
timer. Unequipping the ring halves the remaining enhanced duration once. A later
ordinary dose or expiry clears that flag. Re-equipping alone does not extend an
active effect. A worn ring that used its last charge still counts as equipped for
the existing effect. Login reconstructs the remaining effect using the existing
potion lifecycle.

Equipment removal is observed immediately after committed inventory changes,
including bank deposits, death, and direct inventory clears. Same-tick removal
and re-equipping cannot preserve the enhanced duration. Physical swaps between
two endurance rings also remove the enhancement through the equipment event;
updating charges or consuming the last charge on the worn ring preserves it.
Failed transactions and prepared but uncommitted changes do not reduce duration.

Charging currently operates on the selected unnoted bottle and supports all ten
native standard, mix, and extended dose forms. The amount dialog, bulk/noted
charging, and whether OSRS retains empty vials when charging need direct gameplay
verification. The implementation preserves the native potion row's residual
dose or empty container; this conservation behavior is tested, but is not claimed
as verified OSRS container handling. The obsolete Activate-only ring is excluded.

The Agility cache pack also supplies the verified graceful recovery parameter
and worn transforms for the two Agility capes and the recovery parameter for the
plain max cape. Combat/cosmetic max capes receive no graceful bonus. This metadata
does not implement the capes' separate daily stamina or other skillcape actions.

Tests exercise real cache definitions and inventory transactions, charge limits,
full inventories, stale and repeated requests, passive threshold changes,
standard/extended/mix effects, unequip/re-equip, redosing, expiration, and the
existing logout/login timer methods. Equipment lifecycle tests exercise the real
equip/unequip operations, bank equipment deposit, death inventory handling, and
direct inventory clear; transaction tests cover notification order, observer
failures, partial transfers, and deferred commits. They do not simulate a database save/load
round trip or assert exact dialogue presentation.

Sources checked 30 October 2026:

- [Ring of endurance](https://oldschool.runescape.wiki/w/Ring_of_endurance)
- [Hallowed Sepulchre reward description](https://oldschool.runescape.wiki/w/Hallowed_Sepulchre#Coffin_rewards)
- [Agility cape](https://oldschool.runescape.wiki/w/Agility_cape)
- [Max cape variants](https://oldschool.runescape.wiki/w/Max_cape#Variants)
- [RuneLite run-energy implementation](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/runenergy/RunEnergyPlugin.java)
