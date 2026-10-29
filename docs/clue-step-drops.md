# Clue-step rewards in monster drop tables

The wiki lists some clue-step outcomes beside ordinary monster drops. An
"Always" rate in those rows is conditional on completing the corresponding
active clue. The result advances that Treasure Trail to its next step, or gives
its final casket; it does not grant both items for each ordinary kill.

The imported Kotlin tables previously emitted a clue behind a placeholder
`condition { true }` and an unconditional reward casket. Those paired entries
are removed from the following tables. The existing random clue drops and other
loot are preserved.

| Table files under `content/drops/.../tables/monsters/` | Required clue target; source |
| --- | --- |
| `AberrantSpectreDropTable.kt`, `DeviantSpectreDropTable.kt` | Elite aberrant spectre kill step: [aberrant spectre](https://oldschool.runescape.wiki/w/Aberrant_spectre), [deviant spectre](https://oldschool.runescape.wiki/w/Deviant_spectre) |
| `BasiliskDropTable.kt`, `BasiliskKnightDropTable.kt` | Elite basilisk kill step: [basilisk](https://oldschool.runescape.wiki/w/Basilisk), [basilisk knight](https://oldschool.runescape.wiki/w/Basilisk_Knight) |
| `BloodveldDropTable.kt`, `MutatedBloodveldDropTable.kt` | Elite bloodveld kill step: [bloodveld](https://oldschool.runescape.wiki/w/Bloodveld), [mutated bloodveld](https://oldschool.runescape.wiki/w/Mutated_Bloodveld) |
| `BrutalGreenDragonDropTable.kt` | Elite green dragon kill step: [brutal green dragon](https://oldschool.runescape.wiki/w/Brutal_green_dragon) |
| `CrocodileDropTable.kt` | Elite crocodile kill step: [crocodile](https://oldschool.runescape.wiki/w/Crocodile) |
| `EarthenNaguaDropTable.kt` | Elite earthen nagua kill step: [earthen nagua](https://oldschool.runescape.wiki/w/Earthen_Nagua) |
| `FerociousBarbarianSpiritDropTable.kt` | Elite ferocious barbarian spirit kill step: [ferocious barbarian spirit](https://oldschool.runescape.wiki/w/Ferocious_barbarian_spirit) |
| `FrostNaguaDropTable.kt`, `AmoxliatlDropTable.kt` | Elite frost nagua kill step: [frost nagua](https://oldschool.runescape.wiki/w/Frost_Nagua), [Amoxliatl](https://oldschool.runescape.wiki/w/Amoxliatl) |
| `RockCrabDropTable.kt` | Elite rock crab kill step: [rock crab](https://oldschool.runescape.wiki/w/Rock_Crab) |
| `ShellbaneGryphonDropTable.kt` | Elite shellbane gryphon kill step: [shellbane gryphon](https://oldschool.runescape.wiki/w/Shellbane_gryphon) |
| `SpiritualMageDropTable.kt` | **Master** spiritual mage kill step: [spiritual mage](https://oldschool.runescape.wiki/w/Spiritual_mage) |
| `WaterfiendDropTable.kt` | Elite waterfiend kill step: [waterfiend](https://oldschool.runescape.wiki/w/Waterfiend) |

Elite/master kill-step progression is not implemented by this correction. It
requires matching the player's active clue, advancing its saved step state, and
atomically replacing it with the next clue or final casket. A possession check
alone is insufficient and would let repeated kills generate rewards. Ordinary
loot tables must not stand in for that stateful progression.

The tables carry the exporter's `Hand-maintained drop table` guard so a wiki
refresh cannot restore these unconditional rewards. `ClueStepDropTest` exercises
the actual tertiary graphs for all sixteen tables at successful and unsuccessful
roll boundaries. It also checks that Amoxliatl and Shellbane gryphon retain their
separate random elite clue roll.

This audit does not establish complete parity for the surrounding drop tables.
Unrelated placeholder conditions and rates require their own evidence review.
