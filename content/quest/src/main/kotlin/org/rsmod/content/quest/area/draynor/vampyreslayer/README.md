# Vampyre Slayer

This plugin adds the missing quest progression and playable Count encounter. It is **not yet a
complete 1:1 recreation**: the unresolved details below must remain visible in parity reports.

## Implemented

- Native `varp.vampire` stages 0–3, with a server varbit view. The cache quest row supplies the
  three quest points and endstate; the reward is 4,825 Attack XP.
- Morgan start/decline, progress and optional Count-history dialogue, the combat-level-20 advisory,
  and the existing Draynor postquest dialogue.
- Harlow's first request for beer, an atomic beer/stake exchange, native `harlow_given_beer` and
  `harlow_given_stake` flags, and free replacement stakes. A full inventory can exchange the beer
  because its slot is freed within the transaction. Dialogue cancellation cannot split payment
  from reward. Refusal, reminder, Count-history, career and goodbye choices are implemented.
  Follow-up menus omit the subject just discussed; restarting the conversation restores all topics.
  The current stake Destroy option has a confirmation handler.
- Harlow's native `npc.dr_harlow_myq6` form has its post-Blood-Moon conversation. Native morphs
  determine which Harlow form is visible. This does not add his missing relocated map spawn.
- Garlic cupboard open/search/close interactions. Garlic is optional and is not consumed.
- A Count in the ordinary basement, summoned from the native coffin. The quest does not allocate
  a private map or modify the map cache. Summons do not ordinarily respawn. Logout and leaving the
  map square remove the opener's surviving summon; another attempt starts fresh.
- Count's base cache combat stats, four-tick crush attacks, 2.5% ordinary combat XP, and stat/HP
  regeneration every five ticks. The processor's existing countdown semantics require `regenRate=4`.
- Garlic independently drains full Attack/Strength by 10, full Defence by 40 (clamped at zero),
  and full HP by 10 on auto-attack attempts, including attack-delay ticks. Manual spell-on-NPC
  interactions do not apply these debuffs. Melee max hit gains one before damage is sampled.
- At a lethal hit, a carried stake plus an ordinary or repaired Imcando hammer allow completion.
  Both repaired Imcando forms work carried or equipped; broken hammers do not qualify. Missing either restores
  Count's HP and clears the death queue. The stake is consumed once; the hammer and garlic remain.
  NPC death has no generic remains or loot. Nightmare Zone NPCs are excluded.
- The committed finish writes a saved pending-completion varp before any suspension. A soft timer
  waits for protected access to grant the standard Quest reward, and login restores this timer.
  Repeated settlement does not duplicate XP or quest points.

## Evidence and unresolved behavior

Current native data: revision 241, `dbrow.quest_vampyreslayer`, `npc.count_draynor`,
`loc.vampcoffin`, `loc.vampcoffin_animated`, `obj.stake`, `obj.hammer`, and `obj.garlic`.
The reserved server varp, varbit, timer and hammer enum use ID 63560 in their separate namespaces.
Source revision metadata is recorded in `sources.json` beside this file.

The [quest guide](https://oldschool.runescape.wiki/w/Vampyre_Slayer),
[current transcript](https://oldschool.runescape.wiki/w/Transcript:Vampyre_Slayer), and
[coffin page](https://oldschool.runescape.wiki/w/Coffin_(Vampyre_Slayer)) establish progression and
item requirements. The native stages are also reflected in the
[Quest Helper implementation](https://github.com/Zoinkwiz/quest-helper/blob/master/src/main/java/com/questhelper/helpers/quests/vampyreslayer/VampyreSlayer.java).
The guide notes a return-to-coffin timeout but does not supply its duration. **No guessed timeout
has been added.** Current cleanup on exit/logout is a recovery policy, not a verified exact idle
lifetime. Multiplayer kill eligibility and reclaim timing need a current live-game observation;
existing combat ownership/single-way rules still apply.

[Count Draynor](https://oldschool.runescape.wiki/w/Count_Draynor) cites Mod Ash's
[2025 explanation](https://discord.com/channels/324132423636090880/431834766325841920/1405120776255836261)
for independent stat checks and five-tick restoration, and his
[2020 max-hit confirmation](https://twitter.com/JagexAsh/status/1328824389815635973) for garlic's bonus.
The evidence does not establish all style/special-attack ordering; the added formula applies only
to melee. Ranged, manual magic and powered-staff bonus behavior remain unverified.

The [Imcando hammer documentation](https://oldschool.runescape.wiki/w/Imcando_hammer) confirms
that repaired forms work as ordinary hammers whether equipped or carried. Quest Helper's
Vampyre Slayer requirement uses its [three-item hammer collection](https://github.com/Zoinkwiz/quest-helper/blob/master/src/main/java/com/questhelper/collections/ItemCollections.java).
Native inspection found no shared category or small native enum covering those three tools;
`enum.vampyre_slayer_hammers` records the eligible symbols without accepting broken forms.

Further parity work: exact transcript wording (current dialogue is condensed), coffin
emergence/finishing animations and camera timing, exact cupboard reclose timing and first-search
wording, and Harlow's post-Blood-Moon map spawn. The cupboard source distinguishes the first search
after opening, but does not establish whether that state is shared or per player; no controller
scope or reclose duration has been inferred. Native animation names alone do not establish their
server-side sequencing or camera coordinates. The relocated NPC has no current map spawn in this
server's source. These are not silently treated as complete.

## Validation

`VampyreSlayerInteractionTest` covers native progression, declined starts, atomic hand-in and
cancellation, replacement stakes, independent garlic drains, 5/50/150-tick regeneration boundaries,
auto-attack versus manual spell behavior, missing finishing items, once-only completion, persisted
reward recovery, coffin start/completed guards, stale dialogue/animation inputs, and all five
carried/equipped hammer cases. `VampyreSlayerNativeMapTest` checks the actual coffin origin
(3077,9775), its orientation, the production emergence tile (3077,9773), walkable approach, and
the native three-tick opening duration. `VampyreSlayerMaxHitTest` covers the melee
ceiling and absence of bonuses without both required attributes.

Dialogue regressions also inspect actual menu packets for all three topic exclusions, cancellation
and restart, and the native Harlow visibility swap at the Blood Moon quest row's completion state.
Repeated relocated conversations cannot grant items, XP or quest points. Run with the generated cache:

```sh
./gradlew :content:quest:test --tests '*VampyreSlayer*'
./gradlew :api:combat:combat-formulas:test --tests '*VampyreSlayerMaxHitTest'
```
