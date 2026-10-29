# World types

A **world type** is a gameplay mode — `main`, the leagues, `gridmaster`. One account system, one
database, one set of queries; what changes per mode is the player's **save**.

For scoping content to a mode, see [world-type-plugins.md](world-type-plugins.md).

## The model in one table

An account holds up to three **characters** (named personas). A character spans every mode — same
name, same friends list, whichever mode it is playing. Only the save differs.

| | Scope | Examples |
|---|---|---|
| **Account** | one per login | password, email, 2FA, rights, punishments |
| **Character** | up to 3 per account, **spans all modes** | display name, friends, ignores, chat filters, membership |
| **Save** | one per character **per mode** | position, levels, xp, items, varps, attrs, run energy |

So `Mark1` has one friends list whether he is playing main or leagues, and other players see him
online either way. Logging into leagues loads a *different save* for the *same* character. A season
can be wiped without touching accounts, names or social.

> **World types are not realms.** A realm is a *world's* settings (xp rate, spawn coord, login
> message, dev mode) and a world has exactly one. A world type is which *save* a character is
> playing, and a world can serve several. Nothing here touches the `realms` table.

## The modes

`WorldType` (`engine/game`, `org.rsmod.game.world`) is the complete, closed set — a mode needs code
behind it, so there is no "add a row" path.

| `key` | Label |
|---|---|
| `main` | Main — normal, permanent play |
| `twisted_league` | Twisted League |
| `trailblazer_league` | Trailblazer League |
| `shattered_relics_league` | Shattered Relics League |
| `trailblazer_reloaded_league` | Trailblazer Reloaded League |
| `raging_echoes_league` | Raging Echoes League |
| `gridmaster` | Gridmaster |

`key` is the identifier everywhere: the `game.yml` entry, the Postgres schema name, and the value in
`accounts.active_world_type`. It is lower snake case because it has to work as a SQL identifier.

`WorldType.isLeague` and `WorldType.isSeasonalEvent` let content branch without matching strings.

## Configuring a world

`game.yml` lists **every** mode the world serves. Nothing is implied:

```yaml
world: 255
world-types: ["main", "raging_echoes_league"]
```

| Config | Result |
|---|---|
| omitted, or `["main"]` | a normal world |
| `["main", "raging_echoes_league"]` | main and leagues players side by side |
| `["raging_echoes_league"]` | dedicated leagues world — no normal play |
| `["gridmaster", "raging_echoes_league"]` | two events, no main |

**A login that cannot have the mode it asked for falls back to `main`** — whether the account has no
stored preference, or stored a mode this world has since stopped serving. Landing on their normal
save is predictable; landing on an event they never chose is not.

A world that does not serve `main` at all has no such option, so it uses its **first listed mode**
instead. That is what puts a main player somewhere sensible on a leagues-only world.

An unknown key fails startup rather than silently dropping a mode (`WorldType.supportedFrom`).

A world listing one mode is locked to it: `::worldtype` has nothing to offer. A world listing
several is a *mixed world* — see
[the mixed-world limit](world-type-plugins.md#the-mixed-world-limit).

## Switching mode

`::worldtype` opens a menu of the modes this world serves, current one marked. Picking one swaps the
save **in place** — the player stays connected.

The choice is also written to `accounts.active_world_type`, so the next login lands on the same mode
without asking. The client never expresses a mode on connect, so none of this needs a protocol or
client change.

### What happens

| Step | Thread | What |
|---|---|---|
| 1. Guard | game | Reject combat/logout restrictions, delayed actions, required queues, invalid sessions and concurrent switches |
| 2. Prepare | game | Hold gameplay, settle interface/area exits in the outgoing mode, then capture an immutable save checkpoint |
| 3. Save | saver service | Write that checkpoint to its captured mode schema |
| 4. Load | loader service | Read the requested target mode; enqueue a completion without mutating the player |
| 5. Validate and swap | game | Verify the exact session/attempt and unchanged persistent checkpoint; install the target, rebuild its scene and publish lifecycle events |

The existing combat/logout restrictions remain shared through `Player.isLogoutBlocked()`. A switch
also refuses delayed or non-discardable queued work. Preparation happens after player processing,
so the command's current script can finish before its checkpoint is taken. Interface-close and
area-exit handlers execute against the outgoing save. If cleanup suspends or leaves required work,
preparation aborts before saving; that work continues in the outgoing mode and its rewards remain.

`Player.worldTypeSwitching` holds input dispatch, movement, protected scripts, player queues,
timers, combat targeting, direct damage, run updates and post-tick gameplay hooks. Network input
is drained and discarded while the flag is set. Background content that mutates a player outside
those paths must also honor `Player.canProcess`; Motherlode batches do so and detach/rebuild their
mode-specific state on `WorldTypeChangedEvent`.

Every save now contains detached inventory, stat, persistent variable and serialized attribute
values, with its account, character and world type. The saver never derives database values or the
schema from the live `Player`. This also protects timeout recovery from a late asynchronous save.
The saver orders retries per character/mode, and separately orders shared identity writes, so an
older retry cannot overwrite a newer committed checkpoint.

A completion can apply only to its original `Player`, slot/UID, connection, character and current
attempt. Save/load callbacks only enqueue messages; validation and all gameplay mutations occur
on the game thread. Before installation, the service recaptures persistent state and requires
value equality with the outgoing checkpoint. Unexpected external changes cancel the switch and
retain the current outgoing state, including those changes.

### Commit, failure and timeout

On successful target installation, the service clears outgoing queues/timers, telejumps through
the engine, and forces both the client scene rebuild and server zone resend. Target initialization
runs in order: `PrepareLogin` → `Initialize` → `Login` → `EngineLogin`, then `WorldTypeChangedEvent`.
The reconnect preference is written only after an actual mode change. The wire protocol and client
packet layouts are unchanged.

An attempt times out after 60 seconds. Save/load failures or session termination cancel it without
installing the target. Late or duplicate callbacks cannot apply to a replacement connection or
release the guard of a newer attempt. An immutable queued save may still finish after cancellation;
its captured mode and ordered writes keep it from reading a resumed or replacement save.

If a target data transform fails, the service restores the persistent outgoing checkpoint and
forces disconnect. It does not resume partially reconstructed transient effects such as potion
runtime state; ordinary login reconstructs them. If restoration itself fails,
`Player.persistenceInvalidated` prevents another database write. Teardown bypasses gameplay logout
hooks on that invalid state, but still emits `SessionStateEvent.Delete` for external-owner cleanup.
The previous durable checkpoint remains authoritative.

After target installation, a failing login or change hook forces disconnect in the target mode;
rolling back already emitted target rewards could duplicate items. Each change listener still
receives the committed transition even when an earlier listener fails. Outgoing global activity
must detach before a later cycle can access the newly installed save. Normal logout remains
unchanged; global owners should also make Delete cleanup idempotent for failed-session teardown.

> Login handlers run again on a switch. One-time rewards must be gated by saved progress, and
> global runtime owners must handle `WorldTypeChangedEvent` explicitly. The switch boundary is not
> a substitute for idempotent content or the broader isolation requirements of a mixed world.

### Persistence extension compatibility

External `CharacterDataStage.Pipeline` implementations must implement `capture(player): SaveData`,
`save(connection, data: SaveData, characterId)` and `restore(player, data: SaveData)`. Capture on the
game thread and detach nested collections and mutable attributes; save must not read live gameplay
objects. `restore` restores persisted state for failure recovery, not a playable transient session.
Rebuild external pipeline plugins against this API. `AccountManager.save(player, callback)` remains
available and captures a snapshot; the explicit snapshot overload rejects a stale checkpoint before
enqueueing. This is a server extension API change, with no client or wire-protocol change.

## Where data lives

**Shared, in `public`** — identity and anything describing the person:

| Table | Holds |
|---|---|
| `accounts` | name, password, email, 2FA, rights, discord id, `active_world_type` |
| `account_characters` | the characters: display name, rename history, membership, mute/ban |
| `character_friends`, `character_ignores`, `character_chat_filters` | social graph, per character |
| `sessions`, `punishments` | live sessions; bans/mutes/kicks |
| `activity_logs`, `activity_log_items`, `online_samples` | audit and analytics |
| `realms`, `worlds` | world definitions — unrelated to world types |

**Per mode, in a schema named after the `key`** — created from one migration (`db/world_type/V1`),
so every mode has an identical set:

```
<key>.character_progress   position, level, last login/logout, run energy, xp rate, online markers
<key>.character_varps
<key>.character_attrs
<key>.stats
<key>.inventories
<key>.inventory_objs
```

A character has one `character_progress` row per mode it has played, created on first entry. Every
per-mode table references `public.account_characters(id)`, so deleting a character still cascades
its saves away.

### The one rule

> **Any query touching a per-mode table must run through `withSchemaTransaction`, or the `schema`
> overload of `withTransactionBlocking`.**

The server issues `SET LOCAL search_path TO "<key>", public` per transaction, so one query can read
identity from `public` and the save from the mode's schema without being rewritten — see
`characters_select_metadata_by_login.sql`. There is one copy of each query and one save path for all
modes.

`SET LOCAL` is deliberate: it reverts on commit or rollback, so a scoped transaction cannot leak its
search path into the next one on the shared connection.

Plain `withTransaction` leaves the path on `public`, where the per-mode tables no longer exist.
`GameDatabase` verifies the effective schema and **throws** on a mismatch — a mis-scoped transaction
silently reads or writes another mode's save, which is far worse than a refused login. The two ways
it can fail:

| Symptom | Cause |
|---|---|
| `schema '<key>' does not exist` | schema dropped while the server was running — restart recreates it |
| `connection is in autocommit mode` | `SET LOCAL` did not stick |

The `GameDbManager` gateway is public-only. Every gateway request today touches `accounts` or the
social tables, so it is fine — a per-mode request would need its own scoped path.

Work that must span modes loops the served types and runs once per schema — see
`RealmConfigService.clearGhostOnlineSessions` and Central's
`WorldPresenceService.clearCharacterOnlineMarkers`.

### Saving and first entry

`AccountManager.capture` creates a `CharacterSaveSnapshot` on the game thread. The saver pins
its transaction to `request.snapshot.worldType.key` and writes only the captured main and pipeline
data. The live `request.player` is retained for completion notification, not persistence reads.
Identity and mode progress are written in one schema-scoped transaction; an older mode retry can
still save that mode when necessary, while skipping shared identity if a newer request already
committed it. Ordered retries also skip a checkpoint superseded by a newer save of the same mode.

The login query `LEFT JOIN`s `character_progress`, so a character with no save for a mode still
resolves. The repository creates a missing row. A missing row **or a null saved `last_login`**
reports `AccountLoadResponse.Ok.firstVisitToWorldType`, starting the player at spawn on the base xp
rate. Creating the row during a load does not initialize the mode: its first successful character
save supplies `last_login`. An aborted or timed-out first load therefore remains a first visit on
retry, instead of loading empty stats as an established character.

### Cross-mode views

Only the *save* needs unioning; character rows are already in `public`.

- `public.progress_all` — online markers across modes, for Central's presence cross-check.
- `public.stats_all` — levels across modes, for the world login gate.

Both carry a `world_type` column and are rebuilt whenever `FlywayMigrator` runs. The schema set is
**discovered** (any schema holding a `character_progress` table) rather than passed in, because
Central must see every mode while only the game server knows which ones this world serves.

## Runbook

| Task | How |
|---|---|
| **Add a mode** | Add its `key` to `world-types`, restart. Schema created and migrated at boot. |
| **Close a mode** | Remove it from `world-types`. Saves are untouched; re-adding restores them. Players stored on it fall back to `main`. |
| **Wipe a season** | `DROP SCHEMA <key> CASCADE`, restart. Accounts, names, social, punishments and logs survive. |

Two cautions, both learned the hard way:

- **Drop the whole schema, not just its tables.** Flyway's `flyway_schema_history` lives inside it;
  if that survives, Flyway considers V1 applied and will not recreate the tables.
- **Restart after dropping.** Schemas are only created at boot. Dropping one on a running server
  leaves every query against it failing.

**Migrations.** `db/migration` runs against `public` and creates the `main` schema. `db/world_type`
runs once per mode schema, each with its own `flyway_schema_history`. Central migrates `public` and
`main`; the game server migrates the modes it serves, because Central never reads `game.yml`.

## Reference

**Identity.** The display name lives on the character row in `public.account_characters` — globally
unique, the same in every mode. A rename applies to that character everywhere at once and leaves the
account's other characters alone. Keeping identity in `public` is what makes the split work: a
mode's schema never needs a constraint or lookup spanning modes.

**Punishments.** Account-wide, as always: `punishments.account_id`, optionally narrowed to a
character. Nothing is mode-aware — a ban is a ban in every mode, which is the point of one shared
account system.

**Logs.** `activity_logs.world_type` records the mode (`world_id` cannot stand in — one world serves
several), filled by `CentralActivityLogWriter` from `player.worldType`. It also outlives a season
wipe: after `DROP SCHEMA ... CASCADE` the log rows remain and `world_type` is the only record of the
mode. `activity_log_items` inherits from its parent row.

**Social.** Friends, ignores and chat filters belong to the character and are shared by every mode,
because the row they key on is in `public`. Nothing about social is mode-aware:

- One friends list and one ignore list per character, identical whichever mode it plays.
- Private messages cross modes and account types — an ironman and a normal account can friend each
  other, and a main player can message someone in leagues.
- Presence is per character, so others see `Mark1` online whatever he is playing.
- An account's characters are separate people socially: `Mark1`'s friends are not `Mark2`'s.

**Characters per account.** Up to three (`CharacterAccountRepository.maxCharactersPerAccount`), each
with its own name and social graph. Login takes the lowest character id; a character-select screen
would choose here instead.

**One mode at a time.** Central deletes an account's other sessions on login, and the game's own
duplicate-login guard then checks the character's progress row inside its own schema.
