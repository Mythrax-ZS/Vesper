# Vesper: baseline and 30 / 60 / 90-day roadmap

Audited 29 October 2026 (Australia/Brisbane), against server `2a4f2869f` and
launcher `c1e23b7`. The original source findings below are a historical baseline;
subsequent build and development updates are called out explicitly. Target protocol
revision **241** matches Jagex's live configuration checked on that date. The base
cache was OpenRS2 **2752**, from 28 October 2026, with OpenRune/Vesper packs applied.
Protocol compatibility does not establish content or encounter parity. Exact evidence
and the 24-skill baseline are in [client validation](../../Vesper-Client/VALIDATION.md).

## Assessment

OpenRune provides a substantial modular foundation. The priority is a verified
progression experience, accurate rewards and a working client distribution before
expanding into raids or claiming near-full parity.

The inspected foundation includes auto-discovered content plugins, combat APIs,
boss encounter DSL, instancing, inventory transactions, cache-backed gamevals,
Central account integration, per-world-type saves and external-plugin hot reload.
CI generates cache sources, compiles, runs tests/integration tests and checks for
`Server ready in`. These are useful foundations; their presence does not establish
production reliability or correct gameplay.

| Area | What exists | Remaining gap / confidence limit |
| --- | --- | --- |
| Skills | Fletching source now covers **131 recipes**. Agility implements Gnome Stronghold and Draynor, Draynor marks, and Grace's exchange. Mining has tier-specific geode side drops and Trahaearn crystal shards. Combat and other production/gathering foundations remain. | Hunter bird snares are partial; its other methods and Sailing still need implementations. Farming and full Construction/POH lack core systems despite tracker positives. Fletching omits newer/specialist systems; Agility timing, Gnome marks, other courses and shortcuts remain incomplete. Mining source rates and some modifier/ground behavior still need live evidence. Existing skills need coverage audits; no full-skill or logged-in parity claim follows from this source scope. |
| Treasure Trails | Twenty native beginner clues: five maps, nine anagrams, four NPC hand-offs, Charlie's eight requests and Reldo's five hot/cold destinations; saved trail state, atomic clue/casket transitions, shared ownership/capacity checks, and case consumption. New casket source covers all 74 published reward outcomes, case milestones, Explore, completion counts and collection-log credit. | Higher tiers remain incomplete. Beginner device dialogue, dynamic-instance behavior and exact clue distribution still need live verification. Accounts below ten played hours currently retain their casket because the live anti-bot replacement distribution is unresolved. Reward UI, exact Entrana boundaries and ground lifetimes remain unverified in a logged-in client. |
| Bosses | 20 boss module directories, including GWD, KBD, Barrows, Scurrius, Muspah, DT2, Doom and Wilderness encounters | Validate mechanics, phases, movement, resets, deaths, ownership and drops. Kraken/Vanguard tracker positives come from pet dialogue, not those encounters. |
| Minigames | Gauntlet and Giants' Foundry, including Foundry transactions and Sleeping Giants | Report misses Foundry. Fight Caves, Pest Control, Guardians of the Rift and other major systems need separate delivery plans. |
| Quests | 17 `QuestScript` implementations under `content/quest`, plus Sleeping Giants | Most quests are missing. The subsequent progression change sets defaults and local configs to `respect-progress`, removing the global bypass. Missing quests remain locked; enforcing their gates does not implement them. |
| Raids | Reusable combat/instance building blocks; some boss drop data | No raid implementation found. CoX, ToB, ToA and The Fractured Archive require party lifecycle, encounters, scaling, death/reconnect handling and reward settlement. |
| Diaries / combat achievements | Interface wiring and scattered references | Complete event tracking, persistence, requirements and rewards are absent. Elite CA eligibility in `SlayerSuperiorManager` is hardcoded false. |
| Ironman | Solo restrictions, UIM bank restrictions, loot restrictions and HCIM death handling | Regression-test all transfer/death paths. GIM identifiers are not a group membership/shared-storage implementation. |
| Economy | Shops, restocking, item transaction infrastructure, per-mode saves | No full player trade or GE implementation found. `DefaultMarketPrices` reads cache item cost; it is not an exchange. Sources/sinks and cross-mode isolation need validation. |
| Ultrawide | Fixed, classic-resizable, modern-resizable and fullscreen gameframes; incoming client dimensions; separate pinned RuneLite 1.13.4 development client now starts | No verified 21:9/32:9 renderer behavior or safe-area HUD. Client source is a separate ownership boundary from server and launcher. |

### Progress reporting needs a second, verified layer

The original [`PROGRESS.md`](../PROGRESS.md) reported 20/23 skills, 25/169 bosses,
0/4 raids and 1/52 minigames. The generator now includes all 24 named skills and
distinguishes runtime code from supporting code/data. Shopkeepers do not establish
Farming or Construction; Daddy's Home remains visible as a bounded furniture tutorial,
without implying POH support. Test fixtures, pet dialogue and pack-only scaffolds no
longer establish implementation. Mechanics checklists remain visible for skills with
runtime code, and `--check` verifies both generated files without modifying them.
The source-presence heuristic and cached wiki categories still are not a revision-241
acceptance specification. The current cache has 24 named skills plus one `unreleased`
stat placeholder, not 25 playable skills.
Jagex's [Sailing launch announcement](https://www.jagex.com/news/set-sail-in-old-school-runescape-with-first-new-skill-sailing-to-land-in-the-beloved-mmo-coming-november-19th)
dates its launch to 19 November 2025. Jagex's [20 October 2026 release](https://www.jagex.com/news/old-school-runescape-challenges-the-toughest-players-to-overcome-the-fractured-archive-raid)
confirms that The Fractured Archive is the fourth live raid. It remains in the full
current-OSRS parity scope alongside CoX, ToB and ToA; its implementation has not started.

Fix classifications in the generator/data and regenerate; never edit generated
`PROGRESS.md` by hand. Maintain a reviewed matrix with: revision, feature scope,
source location, implemented mechanics, missing mechanics, loot audit, transaction
tests, live evidence and verification date. A feature is release-ready only when
its relevant columns pass. Do not turn absent skills green by adding empty modules.

### Release blockers and subsequent status

1. **Loot correctness:** 222 `Drops Need Manual` markers across 173 drop files.
   [`CerberusDropTable.kt`](../content/drops/src/main/kotlin/org/rsmod/content/drops/tables/monsters/CerberusDropTable.kt)
   includes a `1 outOf 1` elite casket and a clue predicate returning `true`.
   [`VorkathDropTable.kt`](../content/drops/src/main/kotlin/org/rsmod/content/drops/tables/monsters/VorkathDropTable.kt)
   also had an unresolved condition. These are historical pre-fix findings; a focused
   Cerberus/Vorkath correction has since been implemented and tested.
   Audit each enabled table against revision
   evidence; existing drop data is not proof of accurate rewards. These source
   defects do not imply those missing encounters are currently reachable. The historical focused
   Cerberus/Vorkath correction passed 10 automated loot tests. Current source also
   contains conditional monster clue rolls using claimed CA state and eligible
   imbued-ring Wilderness modifiers; validate base and modified rates through the
   actual drop engine. Full encounter behavior and live kill/reward paths remain unverified.
2. **Client delivery:** launcher bootstrap points at a Hazy path returning HTTP 404
   during this audit. It launches a separate RuneLite client; no local renderer
   exists in that downloader to modify. The separate `Vesper-Client/` path now builds
   RSProx from source and runs signed/hash-verified RuneLite artifacts. Vesper-owned
   public delivery remains unfinished; preserve verification before release.
3. **Local bootstrap completed:** workspace JDK 21/11, local config, generated cache
   sources, server/client/launcher artifacts and startup smoke checks now exist.
   The previous missing-Java blocker is resolved. Repeatable commands and evidence are
   in [BUILDING.md](../../BUILDING.md) and [build-report.json](../../build-report.json).
4. **Install/runtime:** `install` overwrites `game.yml`; default-config creation
   still differs from the example's embedded-Central setup. Launcher Java fallback
   needs OS/architecture/archive handling reviewed, and HTTP connections need
   bounded timeouts. Exercise failed downloads, retries and rollback.
5. **Persistence/operations:** verify Central reconnect, account/world isolation,
   migrations and restore from backups. Review embedded-Central missing-schema
   recovery before production. Resource-only pack/config changes currently do not
   trigger the main CI workflow; extend its path filters.
6. **Progression rates:** quest requirements now respect progress and level-up popups
   default to enabled, including the existing local configs. The stopped local runtime's
   linked realm has now been explicitly provisioned from **150× to 1× XP** using the
   checked [local maintenance operation](../../tools/vesper/README.md); rerun/rollback
   checks passed and no personal-rate outliers were found. The final packaged server
   startup confirmed `baseXpRate=1.0` and `globalXpRate=1.0`. The upstream fresh-database seed remains 150× until this
   provisioning step runs; YAML defaults alone do not establish XP or economy parity.

## Delivery plan

Planning assumption: **two server/content engineers, one client/tooling engineer,
and shared QA capacity**. These are elapsed project days, not promises tied to
calendar dates. A solo effort must reduce concurrent scope or extend the dates.
Full parity, four finished raids, POH, GIM and GE do not fit a credible 90-day plan.

| Period | Server/content priorities | Client/cache/identity priorities | Exit gate |
| --- | --- | --- | --- |
| Days 1–30 | Establish verified feature matrix and revision baseline. Audit loot of enabled bosses first. Validate the implemented Fletching families, starter Agility courses and beginner clue/casket flow using cache data and atomic inventory transactions. Validate starter supplies, banking, death/reclaim, solo Ironman and a Scurrius → Barrows/GWD progression route. | Restore signed, revision-matched client delivery; baseline 16:9/21:9/32:9 and DPI behavior. Apply Vesper login/world/launcher copy, define brand assets, document settings. | Fresh install → login → save → restart works. Selected progression route has live evidence; enabled reward conditions reviewed; no known item-loss/duplication path in delivered flows. Record display failures rather than claiming support. |
| Days 31–60 | Extend verified Fletching and Agility coverage; deliver one persistent Farming patch lifecycle. Add one bounded boss (Giant Mole or Sarachnis after loot review). Harden Foundry/Gauntlet lifecycle. If player trading is required for beta, replace another content slice with atomic two-party trade and persistence tests. | Optional Vesper HUD plugin with centered configurable HUD span, full scene canvas, reset/fallback behavior. Apply only proven IF3/CS2 fixes in scoped packs. Cohesive launcher/login/icon treatment and signed beta distribution. | Reconnect/restart preserves skill state. Boss phases/reset/death/rewards pass. Bank, chat, inventory, minimap, orbs and sidebars remain clickable through resizing/scaling. |
| Days 61–90 | Harden beta and selected diary/CA tasks for delivered content. Choose one additional encounter OR one Hunter method. Reserve limited capacity for shared raid party/lifecycle work and one room prototype in one selected raid, after core gates pass. | Complete display regression recordings, small-window fallback and performance measurements. Finish release/rollback, platform installation and update failure testing. | Load/soak test with declared population and hardware, backup restore and rollback drill; no release-blocking economy defects; published supported-resolution matrix. Raid room remains explicitly a prototype. |

If a milestone gate fails, carry that work forward and cut the lowest priority new
feature. Avoid fitting more work by weakening reward, persistence or input tests.

After day 90, estimate one raid end-to-end before scheduling the next: lobby/party,
every room and phase, modes/scaling, disconnect/death/rejoin, completion and reward
ownership, collection log, achievements and replay evidence. Sequence CoX, ToB, ToA
and The Fractured Archive after a feasibility study; drop-table presence alone does
not justify the delivery order.
Give full POH, GIM storage/membership and GE matching/escrow separate designs.
Keep initial `world-types: ["main"]`; existing Leagues modes can be evaluated as
separate releases with independently tested saves, plugins and item boundaries.

## Files to open first

All paths below are relative to the server repository unless prefixed `Launcher/`
(the sibling checkout). Read the matching `openwiki/` page, then verify source.
The full display file map and test matrix are in [vesper-ultrawide.md](vesper-ultrawide.md).

| Work | First files / modules |
| --- | --- |
| Resolution packets / UI state | `api/net/src/main/kotlin/org/rsmod/api/net/rsprot/handlers/WindowStatusHandler.kt`; `engine/game/src/main/kotlin/org/rsmod/game/ui/UserInterfaceMap.kt` |
| Gameframe lifecycle | `content/interfaces/gameframe/src/main/kotlin/org/rsmod/content/interfaces/gameframe/script/GameframeScript.kt`; `GameframeLoader.kt` in the same module's base package |
| Cache layout / interface authoring | `or-cache/src/main/kotlin/dev/openrune/impl/GameframeTable.kt`; `content/interfaces/gameframe/pack/`; `content/other/spawn/pack/src/main/resources/pack/interfaces/spawn_menu.if3` and sibling CS2 |
| Client delivery | `Launcher/src/main/resources/net/runelite/launcher/launcher.properties`; `Launcher/src/main/java/net/runelite/launcher/Launcher.java`; sibling `Vesper-Client/README.md`, pinned RSProx source and authenticated RuneLite artifacts |
| First missing skill | `content/skills/smithing/src/main/kotlin/org/rsmod/content/skills/smithing/smelting/SmeltingScript.kt`; `content/skills/utils/src/main/kotlin/org/rsmod/content/skills/SkillingMenu.kt`; `content/skills/crafting/pack/src/main/resources/pack/configs/crafting_enums.toml` |
| Item conservation | `api/invtx/`; `content/minigames/giants-foundry/src/test/kotlin/org/rsmod/content/minigames/giantsfoundry/FoundryRewardTransactionsTest.kt` |
| First new encounter | `content/bosses/kbd/src/main/kotlin/org/rsmod/content/bosses/kbd/KingBlackDragon.kt`; `content/bosses/scurrius/src/main/kotlin/org/rsmod/content/bosses/scurrius/Scurrius.kt`; `api/bosses/`; `api/instances/`; `docs/boss-dsl.md` |
| Quest/diary/CA foundations | `content/quest/src/main/kotlin/org/rsmod/content/quest/manager/QuestRequirementPolicy.kt`; `content/interfaces/journal-tab/`; `content/skills/slayer/` |
| Ironman / world modes | `content/other/ironman/`; `api/player/src/main/kotlin/org/rsmod/api/player/ironman/`; `docs/ironman.md`; `docs/world-types.md`; `docs/world-type-plugins.md` |
| Server identity | `game.example.yml`; `api/server-config/src/main/kotlin/org/rsmod/api/server/config/ServerConfigLoader.kt`; `content/other/login/src/main/kotlin/org/rsmod/content/other/login/LoginScript.kt` |
| World/friend labels | `api/net/src/main/kotlin/org/rsmod/api/net/central/CentralSocialClientPackets.kt`; Central realm/world configuration and its database login message |
| Launcher identity/assets | `Launcher/gradle.properties`; `Launcher/src/main/resources/net/runelite/launcher/runelite_128.png`; `runelite_splash.png`; native/installer/macOS/AppImage assets |
| Central / operations | `openwiki/networking/protocol-and-central.md`; `openwiki/operations/configuration.md`; `api/net/src/main/kotlin/org/rsmod/api/net/central/embed/CentralEmbeddedLifecycle.kt`; `.github/workflows/ci.yml` |

AGENTS' revision 240.2 and `SpawnInterface.kt` example are stale; the example config
uses 241 and the actual spawn interface uses `.if3` resources. Do not invent code
against nonexistent examples or an unpinned latest RuneLite API.

## First implementation in this working tree

- `game.example.yml` now names Vesper. Fallback config creation also uses Vesper
  and revision 241. Existing local configs have since been updated for quest progress
  and level-up popups; arbitrary production configurations are not migrated.
- Launcher defaults now use Vesper / `vesper`, with soft cyan `#88D9E6`. The lower
  name selects a separate `~/.vesper` launcher directory instead of `~/.hazy`;
  this does not migrate existing data or necessarily change client profile paths.
- New [`content/other/vesper`](../content/other/vesper) plugin exposes `::vesper`
  and `::ultrawide` as read-only player help. It follows classpath discovery and
  the existing command DSL, uses configured server name, adds no state or item
  transfers, and requires no custom IDs, cache pack or protocol change.
- This help plugin is the first Vesper content scaffold, not a missing skill or
  an ultrawide renderer implementation. It does not alter player display settings.

The login message still comes from realm configuration; existing code substitutes
only `RS Mod` with the configured name. Initial friend snapshots still contain an
OpenRune label. Launcher endpoints, support website, certificate and existing art
remain upstream. Complete those with real Vesper-owned values, preserving upstream
licenses, protocol/package keys and signing checks; do not invent hosting links.
The existing Discord module links accounts, not RuneLite rich presence.

Suggested visual direction: deep navy `#0B1020`, indigo `#252B55`, soft cyan
`#88D9E6`, silver `#CBD5E1`; a simple crescent/rune mark with a restrained glow.
Use a flat recognizable silhouette for small icons, and a wordmark plus optional
`Beyond the Old School` tagline for the splash/Discord banner. These are proposed
art directions, not delivered image assets. Keep game interface readability and
native widget behavior while applying premium styling primarily to product chrome.

### Historical verification and current source checkpoint

The first historical missing-Java failure was superseded by a completed build recorded in
[build-report.json](../../build-report.json): server/cache compilation and packaging,
402 generated cache-backed Kotlin files, four passing server configuration tests,
two passing launcher tests, and a passing Vesper help-plugin formatting check.
The packaged server reached `Server ready` in 19.17 seconds with PostgreSQL and
embedded Central. The source-built RSProx adapter launched the pinned RuneLite client,
connected to the local cache and rendered the first-use terms screen. Terms were not
accepted; account login, help commands and gameplay have not been verified. All smoke
processes were stopped. Those startup checks precede the new Fletching/drop/progression changes.

At the next historical checkpoint, the combined automated suite passed **279 tests with zero failures or
skips**: 13 Fletching, 10 loot, 220 quest, 4 configuration and 32 wiki-tool tests.
That Fletching slice implemented **58 recipes**. Its updated server/cache packaged
successfully and reached `Server ready` in **22.854 seconds**, loading both realm XP
rates at **1.0**. A fresh JS5 handshake and an established loopback socket proved the
client's cache connection to that runtime; passive MCP returned `LOGIN_SCREEN` and
captured the untouched first-use terms screen. Live gameplay remains unverified.
The read-only client inspection workflow and legal-terms
boundary are documented in [client validation](../../Vesper-Client/VALIDATION.md).

Use [BUILDING.md](../../BUILDING.md) and the workspace `build-server.sh`,
`build-client.sh`, `build-launcher.sh`, `start-server.sh` and `start-client.sh` scripts.
They use local toolchains; the server build preserves existing local config and runtime
data. **Do not repeat the raw Gradle `install` over an existing `game.yml` without
preserving it:** that upstream task overwrites the file.

For the help/config changes, the focused checks remain:

```bash
bash gradlew :content:other:vesper:compileKotlin :content:other:vesper:spotlessCheck
bash gradlew :api:server-config:test
```

Confirm `::vesper` reports the configured world name and `::ultrawide` produces
help without modifying settings or inventories. Build/test the launcher separately with its provisioned local toolchain; verify the
new name, accent and data directory.

Current source has moved beyond those dated receipts:

- [`content/skills/fletching/`](../content/skills/fletching/README.md) contains 131 recipes, including crossbows, shields and broad ammunition. The module documents its missing methods and timing assumptions.
- [`content/skills/agility/`](../content/skills/agility/README.md) contains two courses, saved personal Draynor marks and Grace's standard shop exchange. Traversal timing, full course coverage, Gnome mark rolls and several achievement/pet systems remain unfinished.
- [`content/other/treasure-trails/`](../content/other/treasure-trails/README.md) contains 20 supported beginner clues, persistent Charlie/Reldo state and full-pool casket source. The saved delivery journal preserves undelivered rewards; the native reward screen uses an isolated snapshot to avoid Barrows's logout transfer. The under-ten-hour path intentionally retains the casket until its actual reward restriction is established; this is not live parity for new accounts.
- [`content/skills/mining/`](../content/skills/mining/README.md) contains geode rate, ownership, capacity, quest-box and delivery integration, with claimed CA and eligible imbued-ring rules. Source uncertainty about some rocks, exact rounding, tertiary delivery and modern modifier behavior is retained explicitly.
- [`ClueScrollRoll.kt`](../content/drops/src/main/kotlin/org/rsmod/content/drops/ClueScrollRoll.kt) applies conditional monster clue denominators at roll time. The [monster clue audit](../content/drops/CLUE_RATE_AUDIT.md) records 317 additional source-verified ordinary rolls and 89 held rows. Saved reward-tier eligibility is separate from implementing the full Combat Achievement task and reward engine.

- Weight handling now converts native signed item weights correctly and excludes stackable items from carried weight, following the [OSRS weight rules](https://oldschool.runescape.wiki/w/Weight). The cache packer must fingerprint codec changes so existing item definitions actually rebuild; source-only codec fixes do not establish correct runtime weights. Worn Graceful weights passed the equipment-path regression. Automated tests now cover current-level energy drain, stamina priority, all Graceful subsets and obstacle recovery suspension. Client display and live movement timing remain separate validation boundaries.

- [Ring of endurance](../content/other/consumables/README.md) supports atomic charging and potion consumption, the passive charge threshold, enhanced duration, persisted potion effects and immediate equipment-change detection. Bulk/noted charging and exact container behavior remain open.
- Successful quest completion refills run energy and clears the implemented poison, venom and disease state. Tests cover failed reward transactions, ordinary stage changes and repeated completion without granting these benefits again.

Vampyre Slayer now has a [partial quest and encounter checkpoint](../content/quest/src/main/kotlin/org/rsmod/content/quest/area/draynor/vampyreslayer/README.md), with progression, inventory and combat tests, expanded dialogue and native hammer eligibility. Remaining cutscene and encounter details remain explicit. [Hunter bird snares](../content/skills/hunter/README.md) now have a runtime for five birds, real NPC routing, trap lifecycle and transactional rewards. Exact live timing, modifier systems and other Hunter methods remain open; this is a partial implementation.

The coordinated build on 30 October 2026 passed **795 selected tests with zero
failures, errors or skips**, including 36 focused melee, death and inventory integration
cases. The packaged server loaded 646 scripts, all 556 TOML
and 235 annotated drop tables, reached ready in 15.89 seconds, and confirmed realm
XP rates at 1×/1×.
The rebuilt client adapter connected through a fresh JS5 handshake and rendered the
untouched first-use terms screen. No account login, gameplay or ultrawide acceptance
was performed. See the [build report](../../build-report.json) and
[validation receipt](../../.build-logs/vesper-requested-build-validation.json), which retain
per-suite timestamps and the earlier corrected build/startup attempts.

Keep expansion tied to verified gamevals, cache data and inventory transactions.
Continue testing full inventories, integer overflow, repeated actions, vanished inputs,
reconnects and exact item conservation. Confirm XP, action timing, cancellation and
reward presentation in-game before claiming live parity. The broad missing quests,
raids, Farming/POH, Hunter, Sailing, GIM, economy and ultrawide work remains open.

### Subsequent combat, pet and Farming work

KBD's breath protection now has its own cache table and content-side damage resolver.
Its normal and special damage limits follow the
[KBD protection matrix](https://oldschool.runescape.wiki/w/Dragonfire/King_Black_Dragon).
Special breaths retain damage through antifire potions, and the resolved damage no
longer passes through the generic Magic prayer block a second time. This is a
correction to protection limits, not a full encounter parity claim. The existing
uniform damage sampling remains provisional: KBD-specific potion subtraction order,
the unprotected Magic-resistance branch and equipment sampling time need stronger
evidence. The existing launch-time special effects, their odds and shock stat scope
also remain incorrect or unverified. See the
[source audit](../../.tools/parity-validation/kbd/audit.json).

KBD's melee ability now uses the existing NPC accuracy formula with an explicit
Stab defence type. Previously it sampled damage without checking accuracy, so the
player's Defence, armour and defensive prayers could not cause a miss. The change
also makes a drained NPC Attack level affect its next melee accuracy roll. The
subsequent mitigation change replaces the fixed 25 maximum with the shared raw NPC
maximum, so current Strength affects damage too. The generic formula agrees with
KBD's published base maximum; its exact response to live Strength drains still
needs encounter-specific verification.
The [maximum-hit review](../../.tools/parity-validation/kbd/melee-review.json) records
the per-hit reduction evidence and the conflict between current Wiki stacking prose
and Jagex's original Justiciar example; neither should be silently treated as a
complete current mechanics specification.
The [special-effect review](../../.tools/parity-validation/kbd/effects-review.json)
also records conflicting shock-drain descriptions and the unverified successful-hit
condition; the existing breath debuffs are still unfinished.

The pet fallback table contained 22 NPC rows whose active TOML tables already rolled
the same pet at the same rate. Those duplicate rows are removed; the original drop
tables still deliver through the pet reward hook. Five NPC fallback rows remain.
The rebuilt cache contains exactly those five rows. Tests exercise the canonical
Mole kill/reward path, failed rolls, repeat ownership, full inventory insurance and
the remaining fallback rolls.

The next Farming implementation should use independent permanent state per patch
and project it into the native regional display slots. Ten herb patches reuse those
slots; saving one native display varbit per patch would mix their crops. Native
varp 1057's misleading `prayer_last_drained` name is original cache data and must not
be relocated. The server's actual Prayer drain variable is separate. The
[Farming audit receipt](../../.tools/parity-validation/farming/audit-receipt.json)
records native morphs, crop XP, source revisions and remaining evidence gaps.

[Jagex's timer rewrite](https://secure.runescape.com/m=news/farming-timer-rework--skilling-qol?oldschool=1)
requires shared growth windows and offline catch-up. The
[June 2026 update](https://secure.runescape.com/m=news/new-regional-worlds-launch-today?oldschool=1)
also changes the planting-XP settlement and clearing flows that the implementation
must cover. Disease probabilities, persistent offset initialization, diseased growth
windows and Huasca's yield constants remain unresolved. No Farming runtime or
completion credit has been added by this audit.

This subsequent batch passed 211 focused regressions (93 KBD, 7 pet reward, 64 boss
runtime and 47 drop-system cases), rebuilt and packaged the cache/server, and reached
`Server ready` in 16.28 seconds. A fresh client JS5 connection rendered the untouched
first-use terms screen; no gameplay or ultrawide input was tested. See the
[batch receipt](../../.build-logs/parity-kbd-pets-build-validation.json). The earlier
795-test broad validation remains a separate historical receipt.

The following melee correction passed all 102 KBD tests: the 93 protection cases
plus 9 regressions using real accuracy formulas, native armour, current Defence and
Attack levels, prayers and the production hit queue. Formatting and progress checks
passed; the rebuilt packaged server started in 15.58 seconds. Cache, client artifacts,
configuration and keys are unchanged. This batch did not repeat client inspection or
establish gameplay acceptance. See its
[validation receipt](../../.build-logs/parity-kbd-melee-build-validation.json).

The [Giant Mole investigation](../../.tools/parity-validation/mole/mole-investigation.json)
identified published burrow map pins and native access scenery, but exact NPC
destination origins, burrow timing and darkness damage cadence remain unverified.
The hit model now retains explicit magic splashes separately from other zero-damage
hits through modifiers and impact events. This supplies a prerequisite for a faithful
burrow reaction. No Mole encounter implementation is credited by this audit.

### Shared incoming damage mitigation

NPC maximum calculations now return raw damage bounds without equipment proc rolls.
Ordinary NPC attacks and eligible boss hits apply Elysian, Dinh, Justiciar and Ward
to the sampled damage in one shared modifier. Attack contexts preserve the NPC type,
defence subtype and independent equipment/Ward eligibility through deferred hits.
Unknown melee defence does not silently become Stab; Justiciar remains inactive
until that attack supplies a subtype. Typeless, environmental and dragonfire hits
require an explicit policy; external raw queues must supply their own context.
Typed non-hazard boss hits inherit the ordinary style policy unless overridden.

Contextual queued hits apply mitigation, then the stored launch HP cap, prayer and
absorption. Uncontexted legacy hits retain their old cap/modifier behavior; impact
hits retain their existing deferred policy. PvP and outgoing player maximum-hit
formulas are unchanged by this migration. Duke's blanket Ward hook is removed:
direct attacks carry eligibility, while gaze and gas damage no longer inherit Ward
merely because their source is Duke. Gas Prayer drain remains its separate rule.

The [incoming-reduction evidence](../../.tools/parity-validation/kbd/incoming-reductions-review.json)
and [boss policy audit](../../.tools/parity-validation/kbd/boss-hit-policy-review.json)
separate implementation contracts from live verification. Elysian/Dinh integer
rounding retains the existing residual-floor convention; Justiciar follows the
published residual-damage example. Current stacking prose, small-hit rounding,
cap ordering and individual special-attack eligibility remain verification gaps.
Passing regression tests does not establish full combat or encounter parity.

Validation passed 255 selected tests: 38 formula/reducer, 11 splash queue/impact,
10 ordinary NPC mitigation, 112 KBD, 8 Duke, 64 boss runtime, 6 player unit and
6 PvP formula integration cases. Selected formatting and progress freshness checks
passed. The rebuilt packaged server started in 15.33 seconds with 646 active scripts
and both realm XP rates at 1×. Cache, symbols, client artifacts, configuration and
keys are unchanged; no new client gameplay test was performed. See the
[incoming-combat receipt](../../.build-logs/parity-incoming-build-validation.json).

The [lifesteal review](../../.tools/parity-validation/lifesteal/review.json) corrects
the earlier claim about an NPC Guthan exception: the cited Elysian interaction
describes player equipment and does not establish the NPC's healing order. NPC
Guthan's landed-damage healing is unchanged. Player Guthan/onyx lifesteal systems and
their exact interaction with mitigation remain separate unimplemented work.

Flat armour now persists across hits and affects only positive player melee/ranged
damage. Magic, explicit splashes, typeless and non-player hits bypass it. Immunity
and zero damage cannot become positive through negative armour; positive armour
can reduce a hit to zero. The existing Amoxliatl value therefore applies repeatedly
instead of being consumed after one hit. Physical accurate-zero versus miss
metadata and live encounter acceptance remain unverified.

The subsequent server/client rebuild passed 82 selected tests (18 attack
queue/impact and 64 boss runtime), formatting and progress freshness checks. The
server started in 15.77 seconds with 646 scripts and both realm XP rates at 1×.
The rebuilt adapter connected to this packaged server through JS5 and rendered
the first-use terms screen; no terms were accepted or account gameplay exercised.
Cache and symbols are unchanged. See the
[rebuild receipt](../../.build-logs/vesper-server-client-rebuild-validation.json)
and [flat-armour evidence](../../.tools/parity-validation/flat-armour/flat-armour-evidence.json).

### Player damage distributions and multi-hit weapons

The four shared player damage samplers now include zero in the random range and
promote only an accurate, eligible zero to one. Ordinary melee, ranged, powered
staves and damaging spells therefore use the published `1, 1, 2, 3` distribution
when their maximum is three. Zero-damage attacks, positive spell minimums, missed
accuracy, admin maxima and later armour/prayer zeros retain distinct handling.
The [source audit](../../.tools/parity-validation/player-damage-rolls/audit.json)
records the official minimum-hit rule and the narrower calculator-supported
zero-maximum and multi-hit cases.

Scythe, the paired melee weapon family and dual macuahuitl calculate a whole maximum
once, then split it into their separate hit ranges. Rounded-zero subhits retain
eligibility from a damaging whole attack. Dual macuahuitl requires the first
accuracy check to succeed before attempting the second; a later armour zero does
not cancel it. Scythe area targeting, weapon procs and encounter-specific damage
transforms still need separate coverage.

PvP Elysian rolls move from maximum-hit calculation to each eligible queued hit,
preventing one shield roll from being shared by several hits. Ordinary typed combat
queues opt in explicitly; direct legacy and unrelated damage keep their prior
policy. Original binary API signatures are retained, but external combat plugins
using the now-raw maxima must opt into the new queue policy. NPC reduction remains
separate. Current residual-floor rounding and
shield/prayer order are preserved implementation contracts, not new live-parity
claims. Combat XP now receives pre-Elysian damage; exact XP ordering remains
unverified. The [PvP hook audit](../../.tools/parity-validation/pvp-hit-hooks/review.json)
explains why callbacks cannot all be changed to final damage.

The [NPC cache-data audit](../../.tools/parity-validation/flat-armour/cache-data-audit.json)
found no native NPC armour field or usable parameter. A current Wiki table supplies
114 nonzero entries across 58 pages and resolves 147 numeric NPC IDs to gamevals,
but shared IDs can have different phase values: Eclipse Moon is one example.
At that checkpoint these were source evidence, not installed NPC armour data.
The subsequent cache-backed import below uses an enum and explicit phase overrides.

Validation passed 298 selected tests: 38 manager/queue/impact, 40 combat formula,
14 registered weapon-handler, 10 ordinary NPC mitigation, 112 KBD, 8 Duke,
64 boss runtime, 6 player and 6 PvP integration cases. Formatting, generated
progress freshness and preservation of the old compiled-plugin API descriptors
passed. The packaged server started in 15.47 seconds with 646 scripts and both
realm rates at 1×. Configuration, keys, cache, symbols and client artifacts are
unchanged; the previous connected-client smoke remains linked to its original
server checksum. See the
[player-damage build receipt](../../.build-logs/parity-player-damage-build-validation.json).

### NPC flat-armour baselines

The generic NPC pack now provides a server-only `enum.npc_flat_armour`, containing
145 deduplicated main-game Wiki aliases plus four native combat actors linked to
their documented visual morphs. The
[pack source record](../content/generic/generic-npcs/pack/src/main/resources/flat-armour-sources.json)
retains page revisions and decisions for all 114 queried rows. A refreshed query
matched the preceding snapshot. Values remain symbolic cache data rather than a
Kotlin lookup table or a replacement for existing NPC combat definitions.

The shared hit modifier reads the current visible type's baseline. A present
`varn.flat_armour` overrides it, including an explicit zero; clearing the override
restores the current type's baseline. Respawn clears overrides and transmogs.
Amoxliatl's duplicate spawn-time constant is removed. Existing eligibility and
queue-time application remain unchanged: positive player melee/ranged damage,
with immunity and misses protected from negative armour bonuses.

Eclipse Moon has its normal baseline of 6. Its clone-phase value of 4 remains an
encounter requirement, not a second static value for the same NPC. Echo/Deadman
rows are excluded; Dusk's second form retains zero. The data includes Veiled
kraken's documented physical armour, without claiming Sailing combat is complete.
Missing encounter phases, modes, native morph selection and live gameplay still
require implementation and verification.

The [full source assessment](../../.tools/parity-validation/flat-armour/main-mode-complete-assessment.json)
also records five unlisted Defender of Varrock variants and four historical Jagex
proposals whose current infoboxes omit armour. An omitted field does not verify
zero, so those entries remain unresolved instead of being inferred from a name.
At that checkpoint combat XP still used callers' sampled damage before armour in
the ordinary PvN and multi-hit paths. The following correction addresses physical
callers; damage tests alone do not establish XP parity.

This batch passed 309 selected tests, including 11 new cache-backed hit/impact,
override, transmog and respawn cases. All 16,805 existing NPC definitions retained
identical semantic hashes; all 149 enum entries and exclusions matched the reviewed
mapping. The LIVE comparison checked 120,109 indexed archives and found only a
spawn-menu archive/index revision increment, with identical interface content.
The packaged server started in 15.65 seconds with 646 scripts and 1× realm rates.
A fresh client JS5 handshake and terms-screen observation passed; no terms were
accepted or gameplay tested. See the
[NPC armour build receipt](../../.build-logs/parity-npc-armour-build-validation.json).

### Physical NPC combat XP

Ordinary melee/ranged attacks and the physical weapon/special handlers now use
the damage returned by their queued hits for XP. This includes Scythe, paired
melee weapons, dual macuahuitl, Dark bow attacks and specials, Venator bounces,
dragon longsword and halberd specials, and the Gauntlet bow. Each hit receives
armour once. Existing grouped multi-hit damage remains summed per NPC for one XP
award capped to that NPC's current HP. Venator retains separate primary/bounce
awards; a proposed shared revisit cap was removed because current source evidence
does not establish it. Its synchronous bounce scheduling is unverified against the
current game; the earlier description of impact-dependent selection was an
unsupported inference from descriptive source wording.

Venator's second and third hits suppress negative flat-armour bonuses while keeping
positive armour, immunity and hooks. The primary hit retains normal armour behavior.
This narrow exception follows the [current Wiki correction](https://oldschool.runescape.wiki/w/Venator_bow?oldid=15345156);
it has no primary citation or live trace. The [revision review](../../.tools/parity-validation/npc-armour-xp/venator-bounce-negative-armour-review.json)
separates that evidence from the unrelated Seeking-arrow minimum and bounce timing.
The subsequent [targeting/timing review](../../.tools/parity-validation/venator-timing/assessment.json)
found no current packet trace establishing the selection or XP tick. Jagex changed
the bow's targeting engine in August 2026, so older diagrams and predictors do not
validate its exact current geometry or priority. A callback rewrite must wait for
evidence of the behavior it is intended to reproduce.

The existing XP rates, NPC bonus, style splits and tenths precision are retained.
XP remains a launch-time award, not a later impact callback. PvP retains its raw
damage basis, and magic/cast XP is unchanged. Existing compiled APIs remain
available, but external physical-combat plugins need the same caller migration,
with verified award boundaries for their particular attacks.
The [source assessment](../../.tools/parity-validation/npc-armour-xp/assessment.json)
records the damage-based XP evidence and encounter exceptions. Exact concurrent
projectile/HP-floor behavior, multi-hit rounding, magic exceptions and live gameplay remain separate
verification work; this change does not establish full combat parity.

The completed batch passed **361 selected tests**, including 41 new physical XP
and armour-policy cases plus 11 existing Gauntlet layout cases. The layout cases
do not validate the Gauntlet bow; its XP call was source-audited and shares the
tested queue/XP path. All 197 old public signatures across eight checked hit/combat
classes remain available. The packaged server started in **15.574 seconds** with
646 active scripts and both realm rates at 1×. Cache, symbols, configuration, keys
and client artifacts are unchanged. The earlier client JS5/terms-screen smoke is
retained as historical evidence, with its original server hash; no gameplay or
ultrawide check was performed. See the
[physical XP build receipt](../../.build-logs/parity-npc-armour-xp-build-validation.json).

### Weapon launch state and depletion

Ordinary PvN/PvP ranged attacks, Dark bow and Venator now calculate damage and
capture queued weapon/ammunition metadata before removing the spent ammunition.
Previously the final arrow lost its strength bonus; the final thrown weapon lost
its own bonuses and could incorrectly admit unrelated quiver bonuses. Misses
still consume ammunition, and empty-quiver checks still prevent firing.

Venator and Tumeken's Shadow validate positive charges before firing and consume
one charge after the paid shot/cast has been queued. The final charge therefore
retains charged weapon identity; Shadow also retains its charged accuracy/damage
multipliers. Zero-charge normalization and stopping continued combat after
depletion are preserved. Magic XP policy is unchanged. The exact live transformation
tick remains unresolved. The subsequent Shadow passive work is described below.

Venator's returning primary target now passes the same eligibility checks as other
next victims, including current registry identity, plane, availability, attack
option, existing distance limit and validation hooks. A dead origin or accuracy
miss does not prevent onward ricochets. The existing target priority, geometry,
multicombat handling and scheduling have not been certified against the 2026 engine.

The [ranged launch-state assessment](../../.tools/parity-validation/ranged-launch-state/assessment.json),
[Shadow assessment](../../.tools/parity-validation/ranged-launch-state/shadow-assessment.json)
and [Venator timing review](../../.tools/parity-validation/venator-timing/assessment.json)
separate demonstrated equipment-state defects from unverified timing assumptions.
New regressions use actual inventory/charge mutation, native equipment formulas,
registered NPCs and hit queues. They compare depleted and stocked attacks, exercise
accuracy boundaries, retain source metadata and reject invalid return victims.

Validation passed **384 selected tests**, including 23 new methods. Unchanged
tasks can be up-to-date; exact suite timestamps are retained. The packaged server
started in **15.360 seconds** with 646 active scripts and both realm rates at 1×.
No public API, cache, symbol, configuration, key or client artifact changed in this
batch. The historical client smoke remains tied to its earlier server hash; no
client gameplay or ultrawide acceptance was performed. See the
[launch-state build receipt](../../.build-logs/parity-combat-launch-build-validation.json).

### Shadow passive scope and raid location — 30 October 2026

Magic combat now reads raw equipment accuracy separately from the equipment-panel
display. Shadow's built-in PvM attack multiplies that equipment sum before adding
the formula's fixed accuracy offset. Spellbook casts and PvP calculations use raw
bonuses. Equipment damage is capped at 100% after multiplication; Elite Void,
prayer and eligible Salve/Avarice additions follow the cap.

The fourfold raid bonus uses the caster's normalized location in `area.toa_raid`.
The Desert content pack supplies twelve room polygons, including the Nexus and
Tomb and excluding the external lobby. A small cache-packer extension allows
content packs to own map-area TOML alongside their other cache resources.
Copied and rotated instance zones use the existing `RegionRegistry` normalization.
Raid difficulty, party UI state and target NPC identity do not activate the bonus.

The room boundary is a reasoned inference from the maintained RuneLite ToA room
model and Jagex's in-raid bonus rule. It is not a recovered server predicate or a
complete raid implementation. Exact unused-tile semantics, the equipment/bank bonus
display, and permitted PvP minigame checks remain unfinished.

Validation passed **426 selected tests**, including 42 new methods, with no failures
or skips; unchanged tasks can be up-to-date. The packaged server started in **16.12
seconds** with 646 active scripts and 1× realm rates. A fresh client cache connection
reached the untouched terms screen. All smoke processes were stopped. The cache
audit preserves existing areas, and all 153 old public members in twelve checked
classes remain available. No account gameplay or ultrawide acceptance was performed.

See [Shadow combat rules and API migration](shadow-combat.md), the
[source assessment](../../.tools/parity-validation/shadow-passive/assessment.json),
[packed-cache comparison](../../.tools/parity-validation/shadow-passive/cache-audit.json)
and [build validation](../../.build-logs/parity-shadow-passive-build-validation.json).

### Shadow PvP permission — 30 October 2026

The normal PvP dispatcher now checks a registered staff's optional validator before
changing its attack clock or executing specials, resource consumption, effects,
hits, XP or PK hooks. Shadow requires a minigame permission provider for its
built-in player attack; ordinary spellbook casts while holding it use the spell
path. Existing weapon interfaces and the PvP dispatcher's constructor are retained.

Current sources permit Shadow in Castle Wars, Fight Pit, Soul Wars and enabled-rule
Clan Wars. Those four game runtimes and their membership/rule writers are missing,
so there is currently no production provider granting permission. Unsupported
player casts are denied. Providers must eventually use actual match lifecycle and
accepted rules; client setup fields or arena coordinates cannot stand in for them.
Synthetic provider tests validate the extension contract, not a playable minigame.

This is an unfinished part of the full permission work. Exact denial wording and
native rejection ordering are also unverified. Direct weapon-handler callers must
honor the validator; its protection is enforced by normal combat dispatch. A
separate general PvP authorization gap was traced through both player-op and
spell-target packet paths; the subsequent work is described below.

Validation passed **437 selected tests**, including eleven new methods, with no
failures or skips; unchanged tasks can be up-to-date. The packaged server started
in **16.13 seconds** with 646 scripts and 1× realm rates. All smoke processes were
stopped. Cache, symbols, configuration, keys and client artifacts are unchanged.
The previous client smoke retains its original tested server hash; client startup,
account gameplay and ultrawide acceptance were not performed in this batch.

See [Shadow rules and extension contracts](shadow-combat.md), the
[current permission evidence](../../.tools/parity-validation/shadow-pvp-permission/assessment.json),
[production review](../../.tools/parity-validation/shadow-pvp-permission/production-review.json)
the [build receipt](../../.build-logs/parity-shadow-pvp-build-validation.json)
and [general PvP follow-up](../../.tools/parity-validation/shadow-pvp-permission/general-pvp-followup.json).

### Shared PvP context authorization — 30 October 2026

Player combat now requires a positive server-owned context through the existing
validation-hook set. This covers ordinary attacks, manual combat spells and
Ancient secondary player targets, including NPC-primary casts. Empty providers
deny combat; a legacy `Pass` result cannot grant it. Rune preflight and the other
combat vetoes retain their existing paths.

The Wilderness plugin supplies permission from both players' current packed-area
locations, with opt-outs respected and copied maps requiring the same live region.
Cached menu flags cannot authorize an attack or suppress a legitimate first-tick
skull. Wilderness depth now uses normalized source coordinates in copied maps.
Self-targeting, invalid/switching participants, different physical planes and
different save modes are rejected by the shared check. Mode isolation and the
legacy opt-out are Vesper rules, not native OSRS world configuration.

This does not complete all PvP rules. The local Ferox polygon remains excluded;
its exact interior/apron split and teleblock exception need stronger evidence and
cache work. Dedicated PvP worlds, real minigame lifecycles, seasonal differences,
unequal-depth bracket details, exact rejection wording and singles-plus/PJ rules
remain unfinished. Existing launched-hit processing is unchanged.

See [authorization and plugin migration](pvp-authorization.md), the
[source assessment](../../.tools/parity-validation/pvp-authorization/assessment.json)
and [native-cache probe](../../.tools/parity-validation/pvp-authorization/packed-areas.json).

### Ferox safe-area correction — 30 October 2026

The Wilderness pack now extends the town area to reachable upper floors and
defines a separate safety marker for the isolated Ferox dungeon. Its first-floor shoulder includes 17
occupiable native tiles missing from the ground outline. The separate Wilderness
tower and neighboring cave squares retain their combat eligibility. The existing
ground-floor polygon is preserved. Town supply permission includes the upper
floors, and blighted food gains the town exception already used by potions;
basement supply eligibility remains unverified.

The area packer now combines repeated definitions and metadata-only plugin
extensions before rasterization. Include/exclude relationships are preserved for
partial areas as well as full map squares, making archive registration order
irrelevant. Three regression cases reproduced the previous failures before the
fix; the seven selected packer tests pass afterward.

This corrects geography used by the existing shared combat admission and area
systems. It does not implement Ferox's exterior teleblock exceptions, barriers,
PvP worlds or minigames. See [geometry, evidence and limits](ferox-safe-areas.md).


### Blighted rune sacks and standard control spells — 30 October 2026

The central casting path now selects one eligible sack from a native 19-spell
cache map, preserving other spell requirements and ordinary-rune fallback.
Permission uses current packed Wilderness, Ferox town and Mage Bank areas;
the native client flag is only a derived display. Mage Bank food and potion
permission uses the same new bank area. Supply eligibility does not grant PvP
permission, and Ferox basement eligibility remains unverified.

Bind, Snare, Entangle and Tele Block now have registered handlers, outcome-specific
XP and session lifecycle handling. The new integration tests exercise actual
player-target packets, payments and status boundaries. Spell-handler coverage,
exact presentation/timing, sack priority and saving effects, Tome of Water charge
handling, Vengeance, target teleport and real world/minigame contexts remain
explicit follow-up work. This checkpoint is source and local-test progress;
validation results belong to the build report and do not establish live OSRS
acceptance. See [behavior, evidence and limits](rune-sacks.md).

### Vengeance and Vengeance Other — 30 October 2026

The new `content/skills/magic/lunar-spells` module registers Vengeance and Vengeance
Other with a persisted active bit, a 50-tick cooldown deadline, aid-acceptance and
mode/instance target checks, Wilderness-area restriction handling, and a reflection
handler that returns three quarters of qualifying damage to player and NPC sources.
NPCs listed by the Wiki as immune to recoil (108 native IDs, evidence under
`.tools/parity-validation/vengeance/`) are held in the server-only
`enum.vengeance_recoil_immune`. Session login, logout, deletion and save-mode changes
clear the active and cooldown state; whether the cooldown should survive logout is
not established. The cooldown buff-bar uses native clientscript 5931 and struct 3084
as constants because the gameval merger has no struct table.

All 13 module tests pass after fixing compile errors, the missing enum data and a
missing session-delete cleanup handler; the formatting check was run afterwards.
The NPC reflection path uses the internal `launchBeside` API under an explicit opt-in.
The full packaged-server build, client smoke and live gameplay were not repeated for
this change, and exact range, timing, rounding and presentation remain unverified.
