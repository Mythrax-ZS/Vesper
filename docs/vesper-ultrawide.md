# Vesper ultrawide implementation plan

Audit date: 29 October 2026 (Australia/Brisbane). Server baseline: `2a4f2869f`;
launcher baseline: `c1e23b7`, plus the initial Vesper branding changes.

**Status: the revision-241 development client now loads the local server cache and
renders the first-use terms screen. No ultrawide resolution or in-game session has
been validated.** This document records the implementation boundary,
baseline configuration and release tests. It does not certify FOV behavior, rendering
quality, unlimited draw distance or a functioning Vesper launcher download.

## What the source establishes

- The server targets revision **241** in `game.example.yml` and
  `gradle/libs.versions.toml`. The 240.2 references in `AGENTS.md` are stale.
- `WindowStatusHandler` forwards frame width, height and resizable mode to
  `UserInterfaceMap`. The inspected server code stores the dimensions without an
  aspect-ratio clamp; no further use of `frameWidth` or `frameHeight` was found.
- `GameframeTable` defines fixed, resizable classic, resizable side-panel and fullscreen
  layouts. `GameframeScript` selects the login layout, handles transitions, moves
  subinterfaces and redraws some HUD elements. These are useful foundations, not
  evidence that an ultrawide viewport renders correctly.
- No dedicated ultrawide safe area or configurable HUD width was found in these repositories.
- The sibling `Launcher/` repository contains the downloader and process launcher, not
  the RuneLite renderer or client plugin source. It starts
  `net.runelite.client.RuneLite` from downloaded artifacts.
- Launcher product defaults now say Vesper and use cyan `#88D9E6`. Its bootstrap still
  targets the old Hazy distribution. The configured
  [bootstrap URL](https://raw.githubusercontent.com/Mark7625/hosting/refs/heads/master/hazy/client/live/bootstrap.json)
  returned HTTP 404 during this audit. This legacy launcher endpoint remains unresolved.
- A separate [Vesper development client](../../Vesper-Client/README.md) now uses
  source-built RSProx `c0c6c760cfdef99b213002c32723a9a217ec97c9` and the authenticated
  RuneLite **1.13.4** distribution pinned by RSProx for revision 241. It completed a
  local JS5 handshake and reached the terms screen. Terms were not accepted and no
  account was created. This is client startup evidence, not gameplay certification.
- Bootstrap verification uses `SHA256withRSA` and the bundled `runelite.crt`; downloaded
  artifacts are also hash checked. Preserve those checks when establishing Vesper
  hosting. The endpoint ending in `.sha256` is consumed as signature bytes.

The relevant generated architecture guidance is
[OpenWiki quickstart](../openwiki/quickstart.md),
[interfaces and clientscripts](../openwiki/interfaces/interfaces-and-clientscripts.md)
and [networking](../openwiki/networking/protocol-and-central.md). Source takes precedence
over stale examples: the current custom-interface reference is the spawn pack's `.if3`
and CS2 files, rather than the nonexistent `SpawnInterface.kt` mentioned in AGENTS.md.

## Ownership and first files

All paths below are relative to this repository unless prefixed `../../Launcher/`.

| Layer | Open first | Responsibility |
|---|---|---|
| Server | [`WindowStatusHandler.kt`](../api/net/src/main/kotlin/org/rsmod/api/net/rsprot/handlers/WindowStatusHandler.kt) | Incoming resolution and window mode |
| Server | [`UserInterfaceMap.kt`](../engine/game/src/main/kotlin/org/rsmod/game/ui/UserInterfaceMap.kt) | Stored frame dimensions and interface state |
| Server | [`AccountLoadResponseHook.kt`](../api/net/src/main/kotlin/org/rsmod/api/net/rsprot/player/AccountLoadResponseHook.kt) | Initial dimensions supplied during login |
| Server | [`GameframeScript.kt`](../content/interfaces/gameframe/src/main/kotlin/org/rsmod/content/interfaces/gameframe/script/GameframeScript.kt) | Login layout and layout transitions |
| Server | [`GameframeLoader.kt`](../content/interfaces/gameframe/src/main/kotlin/org/rsmod/content/interfaces/gameframe/GameframeLoader.kt), [`StandardOverlays.kt`](../content/interfaces/gameframe/src/main/kotlin/org/rsmod/content/interfaces/gameframe/StandardOverlays.kt), [`PlayerExtensions.kt`](../content/interfaces/gameframe/src/main/kotlin/org/rsmod/content/interfaces/gameframe/PlayerExtensions.kt) | HUD parents, redirect mappings and subinterface movement |
| Server | [`DisplaySettingsScript.kt`](../content/interfaces/settings/src/main/kotlin/org/rsmod/content/interfaces/settings/scripts/tab/impl/DisplaySettingsScript.kt) | Fixed/classic/modern display selection |
| Server | [`BankOpenScript.kt`](../content/interfaces/bank/src/main/kotlin/org/rsmod/content/interfaces/bank/scripts/BankOpenScript.kt), [`WorldMapScript.kt`](../content/interfaces/worldmap/src/main/kotlin/org/rsmod/content/interfaces/worldmap/WorldMapScript.kt) | Modal/inventory pairing and fullscreen transitions |
| Cache | [`GameframeTable.kt`](../or-cache/src/main/kotlin/dev/openrune/impl/GameframeTable.kt) | Existing gameframe definitions and redirect enums |
| Cache | [`gameframe_enums.toml`](../content/interfaces/gameframe/pack/src/main/resources/pack/configs/gameframe_enums.toml) | Existing gameframe pack; currently stone operations |
| Cache | [`spawn_menu.if3`](../content/other/spawn/pack/src/main/resources/pack/interfaces/spawn_menu.if3) and sibling `cs2/script/` | Working custom-interface resource pattern |
| Launcher | [`launcher.properties`](../../Launcher/src/main/resources/net/runelite/launcher/launcher.properties), [`Launcher.java`](../../Launcher/src/main/java/net/runelite/launcher/Launcher.java) | Client delivery, signature checks and argument forwarding |
| Launcher | [`gradle.properties`](../../Launcher/gradle.properties) | Product identity and accent color |
| Client | [`Vesper-Client/README.md`](../../Vesper-Client/README.md), [`RSProx/RuneliteLauncher.kt`](../../RSProx/proxy/src/main/kotlin/net/rsprox/proxy/runelite/RuneliteLauncher.kt) | Pinned client artifacts, launch profile and adapter; renderer/API artifacts are upstream RuneLite 1.13.4 |

Keep normal resolution handling on the existing protocol. A client plugin should own
local HUD placement and preferences. Add cache IF3/CS2 overrides only after a reproduced
layout problem demonstrates they are needed; keep new pack work under the appropriate
`content/` module and rebuild with `./gradlew buildCache`. Use gameval symbols for every
game entity, interface and component. Do not introduce new raw IDs.

## Baseline client configuration

These steps use existing RuneLite controls, not a shipped Vesper preset. Use
`./start-client.sh` from the workspace with the server running and select the Vesper
target. The pinned development client has completed a local cache connection; the
settings and ultrawide matrix below still need validation after the user handles the
first-use terms. The separate legacy launcher cannot complete its audited download.
A different current public RuneLite build is not automatically compatible.

1. In the game's display settings, choose the modern resizable layout. The local handler
   maps the third display choice to `interface.toplevel_pre_eoc`; confirm the visible
   label in the pinned client's cache. Also retain classic resizable as a comparison.
2. In RuneLite **Window settings**, turn **Lock window size** off. Set **Game size** for
   a windowed test or maximize the window. Use **Resize type → Keep window size** for a
   stable outer window while toggling panels; separately test **Keep game size**. Record
   the actual canvas dimensions, because decorations, DPI and sidebar width affect
   usable space. These labels are verified in
   [RuneLiteConfig](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/config/RuneLiteConfig.java)
   and [ExpandResizeType](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/config/ExpandResizeType.java).
3. Start with **Stretched Mode disabled** to establish native resizable rendering.
   If larger UI is needed, evaluate **Resizable scaling** in resizable mode as a separate
   profile. It renders a smaller logical game surface and scales it; it is not proof of
   native output resolution. **Keep aspect ratio** and **Integer scaling** are separate
   controls, not a substitute for a resizable viewport. Check pointer and drag alignment
   after every scaling change. See the
   [configuration source](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/stretchedmode/StretchedModeConfig.java)
   and [implementation](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/stretchedmode/StretchedModePlugin.java).
4. Enable **GPU** on supported hardware, then compare with the software baseline.
   **Draw Distance** affects visible scene range; **UI Scaling mode** affects sampling
   when stretching. Neither certifies FOV behavior. Tune only against measured results
   from the chosen hardware and client version. See [GPU settings](https://github.com/runelite/runelite/wiki/GPU).
5. Leave camera defaults for the first comparison. **Expand Outer Zoom Limit** and
   **Vertical Camera** can later be tested as optional preferences. Zoom/pitch changes
   are not an independent FOV correction. See [Camera settings](https://github.com/runelite/runelite/wiki/Camera).

Do not enable a setting named “Vesper HUD” yet: that plugin is planned, not implemented.
When it exists, this section must name its exact release and supported settings.

## Proposed Vesper HUD plugin

Create a separate client-plugin project targeting the pinned RuneLite 1.13.4 API.
Do not add renderer code to the launcher or generate an uncompiled plugin against an
unversioned latest dependency. The first implementation should be small and optional:

| Proposed component | Contract |
|---|---|
| `VesperHudConfig` | Enable/disable, automatic or custom HUD span, inset, preview, reset |
| `HudLayout` | Pure geometry: logical canvas bounds plus requested span/insets produce bounded anchor rectangles |
| `VesperHudPlugin` | Lifecycle, version/component checks, client-thread scheduling and restoration |
| `GameframeAdapter` | Resolve symbol-based widget groups for the pinned client; reject unknown layouts |
| `HudPreviewOverlay` | Show proposed safe bounds before changing interactive widgets |

Keep the scene across the full canvas. For a usable logical canvas rectangle
`(x, y, width, height)`, clamp the requested HUD span to the available width and center it:
`left = x + floor((width - span) / 2)`. Apply nonnegative insets within that rectangle.
The proposed automatic span is `min(width, floor(height * 16 / 9))`, subject to measured
minimum space required by the widget groups. This is a starting design choice, not a
fixed OSRS rule. If the groups cannot fit without overlap, fall back to the stock layout.

Use one coordinate space throughout layout calculations. Obtain actual canvas/viewport
bounds from the pinned client's API, and establish how those coordinates relate to
Stretched Mode and operating-system DPI before applying changes. Do not multiply widget
positions by the OS scale factor by assumption. Do not subtract the RuneLite sidebar
twice if it already reduces the canvas.

Initial scope is coherent chat, inventory/tab, minimap/orb and informational HUD groups.
Treat clickable children, backgrounds, clip bounds and menu anchors as a group. Bank and
other modals initially retain the stock layout. Re-parenting widgets requires separate
evidence that click handling, drag behavior and CS2 redraws survive the change.

Schedule widget access on the client thread. Recalculate after canvas size, layout,
configuration or widget lifecycle changes; avoid repeatedly forcing dimensions when
nothing changed. Reacquire widgets after login, world changes and interface rebuilds.
Restore stock layout when disabled, when entering fixed mode, on unsupported layouts
and after errors. Detect competing interface/overlay plugins instead of silently
overwriting their changes every frame.

Use version-matched RuneLite `gameval.InterfaceID` symbols where available. Verify their
names and child structure against the pinned revision; do not copy packed widget IDs
from a different release. The public
[Client API](https://github.com/runelite/runelite/blob/master/runelite-api/src/main/java/net/runelite/api/Client.java)
exposes canvas/viewport dimensions, and
[Interface Styles](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/interfacestyles/InterfaceStylesPlugin.java)
demonstrates widget overrides and restoration. These upstream examples establish a
possible mechanism; the proposed HUD plugin has not yet been built against the pinned API.

Geometry tests should cover odd widths, zero/minimized canvas sizes, tiny windows,
insets larger than available space, custom spans beyond the canvas, scaling conversions,
sidebar changes and deterministic fallback. Assert anchors remain in bounds and groups
do not overlap. Live tests must establish actual hit boxes and lifecycle behavior.

## Acceptance matrix

Every row is **not run**. Record both monitor resolution and observed game canvas size.
The 2560×1080 and 3440×1440 modes are commonly marketed as 21:9 but have different exact
ratios; use the measured dimensions rather than forcing a nominal ratio.

| Display resolution | Role | Required result | Status |
|---|---|---|---|
| 1920×1080 | 16:9 regression baseline | Stock layout and optional HUD both usable | Not run |
| 2560×1080 | Ultrawide, lower height | Full scene width; all critical controls reachable | Not run |
| 3440×1440 | Ultrawide, higher density | Crisp UI at native and selected scaled profiles | Not run |
| 3840×1080 | 32:9, lower height | Centered optional HUD; modals and input remain usable | Not run |
| 5120×1440 | 32:9, higher density | Correct bounds, camera behavior and measured performance | Not run |

For every resolution, test Windows and Linux on the supported client build, both
resizable layouts, GPU and software rendering, sidebar hidden and visible, and 100%,
125%, 150% and 200% OS scaling where available. Record compositor/display mode and any
unsupported combination explicitly. Add macOS only once the chosen connection path
and client are established there. Test native rendering first, then each proposed
Resizable scaling preset. Include a small-window and fixed-mode fallback check.

For each combination, capture the following evidence:

| Area | Exercise | Pass condition |
|---|---|---|
| Viewport | Compare the same location, camera pitch/yaw and zoom across widths | Scene fills its intended bounds; no unexplained bars, distortion or clipping; actual FOV behavior documented |
| Camera | Rotate, zoom, move across map/instance boundaries, trigger supported cinematics | No sudden framing jumps or input regressions; limitations recorded |
| HUD | Chat, inventory/tabs, minimap/orbs, XP drops, boss HP and tooltips | Readable, reachable groups without overlap or edge clipping |
| Bank | Open, search, switch tabs, scroll, deposit/withdraw, drag items | Visual slots match input; no concealed controls; server transaction behavior unchanged |
| Other interfaces | Settings, spellbook, equipment, dialogue, collection log, shops and fullscreen map | Correct positioning and focus before/during/after resize |
| GE | Test its interface when a working implementation is available | Must not be marked passed merely because a cache interface exists |
| Mouse/input | Left/right click, item-on-item, drag to slot, wheel, tooltip/menu at all edges | Pointer, hit box and resulting action agree at every scale |
| Panels | Toggle sidebar and plugin panels with both resize-type settings | Geometry follows actual canvas; no accumulated position drift |
| Lifecycle | Reconnect, logout/login, change layout, minimize/restore, switch monitor/DPI | No stale widget references, stuck overlays or lost settings |
| Plugin lifecycle | Enable/disable, reset, reload where supported, conflict tests | Stock layout restored; no repeated errors or competing layout writes |
| Performance | Same busy scene and encounter, fixed hardware/settings, repeatable duration | Record frame-time percentiles, memory and FPS; set release thresholds from baseline rather than claiming universal FPS |

Attach screenshots and widget bounds from the OpenRune Developer Tools where available,
plus client/cache/server versions, OS/GPU/driver, dimensions and reproduction steps.
Screenshots alone do not prove click/drag accuracy. An untested cell remains untested.

## Delivery gates

- **Days 1–30:** establish signed Vesper client delivery, record compatible source/API
  versions, run baseline matrix and document failures; ship launcher identity changes.
- **Days 31–60:** implement and test the optional HUD geometry/preview, then narrowly
  scoped widget groups; add cache fixes only for reproduced failures.
- **Days 61–90:** complete lifecycle, input and performance regression evidence;
  publish exact supported configurations and a signed release/rollback procedure.

Native resizing and presentation-only plugins can preserve the existing server protocol.
Custom cache layouts still require clients to receive the matching cache. A renderer
fork, new client hooks or new packets would add compatibility requirements and must be
identified explicitly before implementation. None is required by this initial plan.
