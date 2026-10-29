# Monster clue-rate migration

The broader audit changes **317 ordinary tertiary rolls in 273 files**: 180 TOML
rolls and 137 Kotlin rolls. Each now retains the explicit base fraction from a
current OSRS Wiki source revision and applies the existing runtime clue modifiers.
The audit did not calculate base rates by reversing the old 5% adjustment.

The machine-readable [source receipt](src/test/resources/parity/monster-clue-rates.json)
contains the original and corrected fractions, exact gameval symbols, source
revision URLs, revision timestamps, retrieval timestamps, source SHA-256 hashes,
template line numbers, file hashes, and all 89 unresolved rows. It covers 272 source
revisions. Local full-text snapshots and the bounded fetch/comparison scripts are
retained in the workspace at `.tools/parity-validation/clue-broad-audit/`.

Only matching ordinary tertiary declarations were changed. Item quantities, NPC
and area lists, quest hooks, other loot stages, and existing scroll-box transforms
remain as they were. Kotlin files carry the hand-maintained marker understood by
the wiki exporter. Existing Jelly NPC filters remain unchanged. The two TOML
entries without a scroll-box transform (Guard (Shayzien) and Mind Golem) retain
that behavior and still require separate transformation review.

`MonsterClueMigrationTest` reads the receipt and the actual shipped tables. It
uses the production TOML text fixer, strict Jackson decoding, and content resolver;
loads Kotlin tables by their declared fields; checks claimed/unclaimed combat
rewards and equipped ring/location behavior; and exercises successful and failed
rolls plus quest-dependent item transforms. It includes real probability-threshold
tests for ordinary, odd-denominator, and tenfold superior-monster rolls. These
tests require the generated revision-241 cache.

The unresolved set includes Leagues-only monsters, conditional source footnotes,
different NPC variants on one source page, custom Kotlin expressions, missing title
mappings, and Skotizo's legitimate guaranteed hard clue. These entries were not
silently assigned guessed rates. Some footnotes are citations rather than gameplay
conditions, but require a separate review before inclusion. The God Wars Dungeon
goblin discrepancy and paired legacy ring rolls also remain listed explicitly.

This is a clue-rate correction, not certification of the surrounding boss
encounters, world availability, shared loot tables, or the complete Treasure Trails
lifecycle.
