# Agility: Gnome Stronghold and Draynor Village

This module implements both seven-obstacle circuits using native cache objects,
collision-aware approaches, animated traversal, and ordered lap credit. Both
courses require level 1 and have no random obstacle failures under the May 2024
rules. Full laps award 110.5 base XP at Gnome Stronghold and 120 at Draynor.
The server's configured experience multipliers still apply.

Course and obstacle data live in the pack's `agility_courses` and
`agility_obstacles` tables. Runtime code reads generated typed rows. Alternate
Gnome nets, branches and both pipes share the corresponding lap step. Traversal
awards XP only after its animation wait completes. Cancellation restores an
unfinished crossing's entry and invalidates its lap credit; recovery coordinates
persist for reconnect/crash recovery before the initial map rebuild.

## Draynor marks of grace

`agility_mark_spawns` contains four native rooftop locations. Completed eligible
laps roll 2/6, with an 80% reduction when the unboosted level is at least 20 above
the course requirement. Successful rolls set a three-minute cooldown at minute
granularity. Spawn locations are selected uniformly; their relative OSRS weights
have not been independently captured.

Marks use personal ground objects, including for Group Ironman players. Each
location's count and remaining online lifetime persist in permanent vars. Ground
stacks last 1,000 game ticks, refresh when another mark joins them, and pause while
logged out. The cooldown uses absolute minutes and continues while offline.
Collection-log credit occurs when the mark spawns. Standard ground-item pickup
validates the live claim, commits the inventory transfer, then clears its saved
count and lifetime through the successful-pickup hook.

## Grace's exchange

Use **Trade** on Grace in the Rogues' Den to spend marks through the standard
shop interface. The existing native stock inventory supplies all six graceful
pieces and amylase packs; the item definitions supply their prices. A complete
outfit costs 260 marks and sells back for 208. An amylase pack costs 10 marks and
sells back for 8. Only the shop's original stock items are accepted, so recolours,
worn-only forms and unrelated items cannot be exchanged.

Grace uses the shared atomic shop transactions with a stock-specific sale rule.
Other shops retain their existing tradeability checks. The existing generic pack
handler opens each amylase pack into 100 crystals and rechecks inputs and capacity
for subsequent queued openings; no second pack-opening handler is registered.

## Verification and remaining parity work

`AgilityCourseTest` exercises native object approaches, landing tiles, ordinary
walking between obstacles, both complete laps, alternate obstacles, skipped and
repeated steps, course switching, delayed XP, interruption and logout recovery.
`AgilityMarksTest` exercises probability boundaries, cooldowns, collection-log
timing, stacking, expiry, reconnects, full inventories, stack overflow, repeated
pickup, and inventory/source changes during delayed pickup. Tests use base XP
at 1× and the actual cache; passing them does not establish live-client parity.
`GraceShopTest` covers complete-set and pack exchanges, full inventories, depleted
stock, maximum stacks, repeat requests, restricted sale items, unchanged generic
shop rejection, and the real pack handler's conservation and queued revalidation.

Known limitations:

- Traversal timings and animation choices are provisional. Balance/crawl duration
  uses tile distance at one tile per tick; jumps use the selected cache sequence's
  duration; climbing repeats that duration per floor. These rules need comparison
  with current OSRS captures, including rope interpolation and narrow-wall paths.
- Draynor's final 79 XP is currently all gated on completion. The independently
  earned crate XP versus lap-bonus split has not been established. The evidenced
  full-lap total is preserved, but standalone crate behavior remains unverified.
- Lap progress currently resets on logout. The exact OSRS persistence semantics
  need verification; interrupted traversals never receive completion credit.
- Gnome marks are not enabled: its current spawn probability is unverified, and
  the wiki's map pin falls on a blocked native rooftop tile. No substitute spawn
  tile or probability is invented.
- Grace's dialogue and lap-counter interface, recolouring, diary completion,
  squirrel rolls, other courses, shortcuts, and live-client presentation checks
  remain outside this checkpoint.

Rebuild the cache after pack edits, then run
`:content:skills:agility:test`. The implementation uses existing locs, sequences
and the standard mark item; it introduces no custom client protocol or interface.

## Evidence

- [Gnome Stronghold course](https://oldschool.runescape.wiki/w/Gnome_Stronghold_Agility_Course)
- [Draynor Village course](https://oldschool.runescape.wiki/w/Draynor_Village_Rooftop_Course)
- [May 2024 skilling changes](https://oldschool.runescape.wiki/w/Update:Project_Rebalance:_Skilling_%26_Poll_81_MTA_Changes)
- [Mark spawning and expiry](https://oldschool.runescape.wiki/w/Mark_of_Grace)
- [Mark spawn locations](https://oldschool.runescape.wiki/w/Template:Mark_of_grace_spawns)
- [Grace's stock and prices](https://oldschool.runescape.wiki/w/Grace%27s_Graceful_Clothing)
- [Graceful sale-back restrictions](https://oldschool.runescape.wiki/w/Graceful_outfit)
- [Amylase pack contents and sale-back](https://oldschool.runescape.wiki/w/Amylase_pack)

Native geometry comes from revision 241 server-map decoding, including bridge
floor adjustment in `GameMapDecoder`; sequence durations come from that cache's
`SequenceServerType`. External descriptions establish mechanics and full-lap XP,
not exact server-script timings.
