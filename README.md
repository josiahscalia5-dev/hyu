# Island Blast

Android (Kotlin, API 26+) game, all in one app:

**Home → World 1 map → Levels 1–6 → Level 6 Complete → World 1 Complete → World 2 Unlocked → back to the World 1 map**

| Screen | What it is | Approved reference |
|---|---|---|
| Home | Title screen: logo, character, PLAY, top bar, bottom nav | `design/home_reference_upload.png` |
| World 1 map | "Tropical Islands": Levels 1–6 on a chain of islands, then the gate to World 2; tap a level to play it | none (designed for this app) |
| Levels 1–4 | Coming Soon: not built yet (locked, no name, no stars) | none yet |
| Level 5 "Temple Chase" | The colour-shift block level (unchanged gameplay) | `design/level5_fullscreen_reference.png` |
| Level 6 "Storm Dodge" | Jet ski down a stormy river, dodging hazards (unchanged gameplay) | `design/level6_storm_dodge_reference.png` |
| World 1 Complete | After Level 6's Level Complete: every World 1 level and its stars, then "World 2 Unlocked!" | none (designed for this app) |
| Mystic Harvest (not on the map) | Treasure slicing, built but not Level 4 (Jungle Zip); kept in the code | `design/level4_reference.png` |

The app opens on Home. PLAY (or the Levels tile) opens the World 1 map. Only Levels
5 and 6 are playable; Levels 1–4 are locked and marked "Coming Soon" until they are
built.
Finishing a level shows Level Complete with its stars. **Continue** returns to the
map, where the stars are saved and the player marker moves on to the next level.
Level 6 is World 1's last level: the first time it is completed, Continue leads to
**World 1 Complete** and **World 2 Unlocked**, then back to the map, where the gate
to World 2 is now open (World 2 itself is not built yet: its gate says "coming
soon"). Android Back pauses a level (and resumes it), goes from the map to Home,
and from Home closes the app.

This app is the merge of two lines of work (both kept as backup branches
`backup/2026-10-01/ccr-e0caed5f-med9a2` and `backup/2026-10-01/ccr-95596f48-cuc437`):
the Home / World 1 map / Levels 4–5 app was the base, and Level 6 Storm Dodge was
brought in from the other line as a new level on the World 1 map. That line's
simple level-select screen was not kept: Home and the World 1 map replace it.

## Architecture: adding levels and worlds

Every screen lives in one activity (`MainActivity`). `app/GameFlow` moves between
them and `app/Navigator` shows one at a time. Levels are data:

- **`assets/worlds.json`** lists every world and level: number, name, map position
  and, for a playable level, its `kind` (which gameplay) and `dir` (its asset
  folder). A level with no `kind` shows on the map as coming soon. A world's
  `gate` is the way on to the next world, at the end of its map's path.
- **`levels/LevelKinds`** maps each kind to code that builds the level:
  `color-shift` (Level 5), `storm-dodge` (Level 6) and `treasure-slice` (Mystic
  Harvest, not on the map).
- **`levels/LevelPlay`** is what every level gives the host: its view, state,
  score, stars and goal, plus pause and restart.
- **`levels/LevelScreen`** hosts any level. It draws the pause menu (Resume,
  Restart, World Map) and the result card (Replay and Continue, or Map and Try
  Again). It saves the result in `levels/Progress` (best stars and score per
  level) and returns to the map.
- **`app/GameFlow.continueAfter`** decides where Continue goes: after a world's last
  level (the first time), `map/WorldCompleteScreen`, which records the world
  complete and unlocks the next one (`Progress.completeWorld`); otherwise the map.

To add **another level of an existing kind** (no code changes):

1. Put its art and settings in a new asset folder, e.g. `assets/level7/`, in the
   same format as `level4/` (slicing: `layout.json`, sprites, `rules.json`),
   `level5/` (colour shift) or `level6/` (storm dodge).
2. In `worlds.json`, give the level a `"name"`, `"kind"` and `"dir"`.
   An optional `"config"` overrides any setting in its `rules.json`, e.g.
   `"config": {"seconds": 45, "starScores": [4000, 20000, 60000]}`.

To add a **new kind of gameplay**, write a view that extends `StageView` and
implements `LevelPlay`, then register one `LevelKind` for it in `LevelKinds`. To add
**World 2**, add a world to `worlds.json` and its map to
`assets/maps/<world id>/` (`map.json` and the backdrop), then make World 1's gate
open it (`MapScreen`'s `onGate`) once `Progress.worldUnlocked(2)`.

## Home screen

Built from your uploaded home screen. The art is cut from the image itself
(`tools/home`):

| Layer | Source |
|---|---|
| Scene with the logo and character | The reference with the top bar, PLAY and nav painted out (Big-LaMa); more sky painted above for tall phones |
| Top bar (avatar, Lv. 12, coins, gems, settings) | Cut out with Segment Anything masks |
| PLAY button | Cut out; it pulses, a glint sweeps across it, and it sinks when pressed |
| Bottom nav (Levels, Mini-Games, Shop, Characters) | Cut out; its tiles run on to the bottom edge |

**Nothing is cropped on any phone shape.** The top bar is pinned to the top of the
safe area (clear of the camera cutout) and the nav to the bottom. The logo,
character and PLAY are scaled to fill the space between, never wider than the
screen and never under a bar. Taller phones show more sky above the logo.
`ScreensFitTest` checks this on five phone shapes. Mini-Games, Shop, Characters
and the top-bar buttons say "Coming soon!".

## World 1 map

A top-down chain of islands painted by `tools/map/build.py` in the game's palette,
with turquoise shallows, foam, sandy cliffs, grass, palms and plank bridges where
the path crosses water. Level 5's island has a stack of its colour blocks (only
built levels get props: Level 4's island is plain until Jungle Zip exists). Each
level button shows:

- its number;
- the stars earned (empty stars show what is left);
- its name, for built levels.

A lock and "Coming Soon" mark Levels 1–4, which are not built yet: no name, no
stars, and tapping one only says "Level N is coming soon!". The next level to play glows and carries the player's marker;
after Continue the marker walks over to the next level. Level 6 sits on the island
after Level 5, and the path runs on over the bridge to the **World 2 gate**: grey
with a padlock until World 1 is complete, then green and glowing (the path to it
turns gold). The header shows the world, a back button and the stars earned in the
world (out of 6: two playable levels). The map covers the screen edge to edge but is never scaled so far that a
level button leaves the safe area.

## Mystic Harvest: treasure slicing (built, not on the map)

Built as Level 4 from `design/level4_reference.png`, but it is not Level 4 Jungle
Zip, so it is off the World 1 map. Its code (`treasure-slice`), art (`assets/level4/`)
and tests are kept; putting it on a map is one entry in `worlds.json`.

**Swipe across treasure to slice it.** The blade's trail follows your finger: a
crescent of white light edged in blue, like the painted slash. The sword hilt turns
toward it.

- **Cutting:** a swipe cuts every treasure its path crosses, tested against each
  treasure's real outline. The treasure splits **along the line of the swipe**
  into two halves of its own art, which fly apart and spin away.
  - Gems throw crystal shards.
  - Crates and barrels throw wood splinters.
  - Coins fly into the score panel.
- **Chests** take three cuts. Each cut shakes the chest and opens another crack
  in the wood. The third cut bursts it open and throws up five coins and gems,
  which can be sliced too.
- **Barrels** add 2 seconds to the clock.
- **Treasure** first hovers where the approved screen paints it. Once the level
  starts, more is tossed up out of the lagoon all level long, faster and in
  bigger groups as time runs down. Treasure you miss falls back with a splash.
- **Combo:** cuts within 1 s of each other chain the combo, up to ×8. Points are
  the base value × combo:

  | Treasure | Points |
  |---|---|
  | Gem | 15 (big gem 30) |
  | Coin | 20 |
  | Crate | 10 |
  | Barrel | 15 |
  | Chest | 60 (plus 10 for each crack) |

  Letting tossed treasure fall unsliced breaks the combo ("Missed!"). One swipe
  through 3+ treasures earns a bonus and a "Great!", "Awesome!" or "Mystic!".
- **Start:** the level opens on the approved composition with a banner: "Level 4 ·
  Mystic Harvest · Goal: 3,000 points in 60 seconds · Swipe to slice!". A ghost
  blade shows the gesture. The clock starts at the first swipe (or after 4 s).
- **End:** when the 60 s run out, the level is **complete** if the score reached
  the goal, otherwise **Time's Up** (Try Again or Map). Stars come at 3,000,
  18,000 and 50,000. The plaque fills them in live, and the first star shows
  "Goal reached!".
- **Pause:** the painted pause button opens the pause menu.

All numbers are in `assets/level4/rules.json`. `SlicePlaythroughTest` plays the
level three ways to check the balance:

| Player | Result |
|---|---|
| Keen (best line every flick) | Passes with 3 stars |
| Casual (one treasure per flick, pausing) | Passes with 2 stars |
| Beginner (one flick a second) | Passes with 1 star |

**Art** (`tools/level4`, all cut from the approved screen): every treasure,
the sword hilt, the burst's glow and debris, and the lagoon plate.
`tools/level4/lagoon.py` repaints the middle of the lagoon as open water: each row
is coloured like the real water beside it, with painted-style ripples and
sparkles. This replaces the misty patch left where the burst was painted out,
which would otherwise sit mid-screen all level. It also empties the plaque's
painted stars, since stars are earned in play.

## Level 5 "Temple Chase": colour-shift blocks

Unchanged gameplay and art (cut from `design/level5_fullscreen_reference.png`).
The pause menu and Level Complete card are now the shared ones, so Level Complete
leads back to the map.

1. The launcher holds a coloured ball (blue to start). Drag anywhere to aim and
   release to shoot. The aim guide shows exactly where the ball will first hit.
2. If the ball hits a block of **its own colour**, that block and every
   connected block of the same colour burst.
3. Every surviving block that **touched** the cleared group then steps one
   colour along a fixed cycle:
   **blue → purple → pink → red → yellow → blue**.
4. The next ball loads in the colour of the biggest group the ball can actually
   reach. Tap the ball to switch to any other colour still on the board.
5. A ball that hits a different colour bounces off, so bank shots work. If it
   rolls back, the shot is a miss and the combo resets.
6. Clear every block before the timer runs out.

Scoring: each clear adds `10 × blocks × combo` points and 1 coin per block. Stars
are earned at 1,000, 1,500 and 4,000 points. Values are in `Rules` in
`model/Level5Game.kt`.

## Level 6 "Storm Dodge": jet ski storm run

Spec: `design/level6_storm_dodge_reference.png`. Art cut from it by
`tools/level6/build6.py`: sky plate with the HUD and lightning, extended past
every edge; a seamless water tile quilted from open river; the jet ski,
barrels, spiked mine, X-crate, logs, coins, shield and X hazard sprites. Gameplay
and art are as on its own branch (awaiting your review of
`design/renders/level6_preview.png`); in this app its pause menu and Level
Complete / Time's Up card are the shared ones, like Levels 4 and 5, so it leads
back to the map and on to World 1 Complete.

- **Controls:** the arrow buttons move one lane; dragging anywhere steers
  under the finger. Pause button: the pause menu.
- **River:** a perspective river scrolling toward the jet ski, with storm
  waves, rain, lightning strikes and flashes, and spray from the jet ski.
- **Hazards:** barrels, mines, logs, crates and X hazards all collide.
  - A hit costs 150 points and knocks the ski back.
  - The ski slows for a moment and flickers while it can't be hit again.
  - Debris bursts and the screen shakes.
- **Collectibles:** coins in lines, curves and risky clusters beside hazards
  (+10 each). Blue shields (+50) absorb exactly one collision ("BLOCKED!").
- **Sections:** Calm Storm, Stronger Current, Heavy Storm, Storm Escape, then
  Temple Finish. Speed and hazard density rise section by section. Each new
  section is a checkpoint that adds time (the timer starts at 0:36 as
  painted).
- **Fairness:** the open lane moves at most one lane per row, so a clean line
  always exists. Debris drifting along the banks is scenery, out of reach.
- **Finish:** the storm calms and you ride through the torch-lit gate into the
  temple, with a burst of confetti; then Level Complete. The score adds a time
  bonus (+40/s left) and a no-hit bonus (+500). Running out of time gives
  Time's Up (Map or Try Again).
- **Stars:** 3 for at least 70% of coins and at most 1 hit; 2 for up to 4
  hits; otherwise 1.

## World 1 Complete → World 2 Unlocked

The first time Level 6 is completed, Continue on its Level Complete card opens
this screen over the dimmed World 1 map, with sunbeams and confetti:

1. **"World 1 Complete!"**: Tropical Islands, every World 1 level (stars earned on
   the built ones, locks on the ones still to come) and the world's star total.
2. **"World 2 Unlocked!"** pops in, the padlock springing off the World 2 button.
3. **Continue** returns to the World 1 map: Level 6's stars pop in and World 2's
   gate is open. A tap skips straight to Continue; Back does the same as Continue.

Replaying Level 6 later goes straight back to the map.

## Decisions to review

- **Only Levels 5 and 6 are built.** Levels 1–4 (Relic Ricochet, Totem Sequence,
  Coral Current, Jungle Zip in the master spec) are locked "Coming Soon" buttons;
  their planned names are kept in `worlds.json` (`planned`) but not shown.
- **Mystic Harvest is off the map**: it is not Jungle Zip. Its chest and gems were
  taken off Level 4's island in the map art (`tools/map/build.py` paints a level's
  props only when it is built); nothing else in the art changed.
- **Level 5 "Temple Chase" is the colour-shift block level.** The master-spec mockup
  shows Temple Chase as a runner ("Run, jump, and escape the temple guardian!"); no
  such runner exists in this repository. The APK's Level 5 is the colour-shift
  level from the approved Level 5 design, named Temple Chase on the owner's
  instruction (its old name, "Temple of Colours", was a placeholder).
- **World 1 is Levels 1–6.** The map had ten buttons (7–10 coming soon); 7–10 are
  gone and the World 2 gate stands on Level 7's old island. The map art is
  unchanged, so the islands at the top are scenery for now.

- **Both built levels are open from the start** (5 and 6), as on the base
  branch, so either can be played (and previewed) first. The marker points to the
  first one not yet completed, so it leads 5 → 6. World 1 Complete comes when
  Level 6, the last level, is completed.
- **Level 6's own end card** ("STORM SURVIVED!" with the coins/hits tally, "Tap to
  ride again") and its tap-to-resume pause screen are replaced in the app by the
  shared Level Complete / Time's Up card and pause menu, as Level 5's were. Its
  HUD, river and art are unchanged.
- **Mystic Harvest starts fresh** (score 0, 1:00, no stars), so it has a real start
  and goal. The painted "1,240", "0:28" and "Combo x8" were a mid-game snapshot.
  **Level 5 still opens as painted** (score 1,760, 0:36, two stars), as before;
  `Rules` holds those values.
- **Mystic Harvest's level length:** each barrel adds 2 s, so a player who slices every
  barrel plays past 60 s. Set `"barrelSeconds": 0` to keep it at exactly a
  minute.
- **The World 1 map** had no reference, so it was designed for this app.
- **Home's coins, gems and "Lv. 12"** are the painted values.
- **Font:** Fredoka Bold, the closest open-licence match to the painted lettering.

## Build and test

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # everything below
```

**`GameFlowTest` is the whole game end to end**, with real touches through the real
`MainActivity`: Home → PLAY → World 1 (Levels 1–4 each tapped: "coming soon") →
Level 5 played to Level Complete → Continue → World 1 →
Level 6 played to Level Complete → Continue → World 1 Complete → World 2 Unlocked →
Continue → World 1 → Back → Home. On the way it checks:

- **Home:** everything is on screen.
- **Map:** World 1 is Levels 1–6, only 5 (Temple Chase) and 6 (Storm Dodge)
  playable, 1–4 nameless; every level button and the World 2 gate are on screen;
  the marker starts on Level 5. Levels 1–4, and the locked gate, keep you on the map.
- **Mystic Harvest** (`SliceChecklist`, run when the level is on a map), with real swipes:
  - every crossed treasure is cut and its two halves fly;
  - chests lose one hit per swipe and then spill loot;
  - barrels add time;
  - the score equals the sum of every cut, and the stars follow it;
  - the pause button stops the clock and Resume restarts it;
  - time up gives Level Complete.
- **Level 5** (`Level5Checklist`): each shot's clears, one-step colour shifts,
  next ball and HUD, plus on-screen colour checks.
- **Level 6:** its pause button opens the pause menu (the river stops) and Resume
  carries on; then it is ridden to the temple with real taps on the arrow buttons
  (a bot picks the lane), to Level Complete.
- **After each level:** the map shows the saved stars and the marker has moved.
- **World 1 Complete:** World 1 is recorded complete and World 2 unlocked; both
  cards and Continue are on screen; back on the map the gate is open.

Screens go to `app/build/flow/`.

The same checklist runs on a phone or emulator:

```
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.islandblast.game.DeviceEndToEndTest \
    com.islandblast.game.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.islandblast.game/files/e2e   # screenshots + report
```

The harness holds the game clock while it plans a shot and reads screenshots; the
clock runs between moves.

Before the Level 6 merge (version 0.6.0) this was verified on an Android 11
emulator (1080×2400) with real injected touches. One run went through Home → World 1
→ Level 4 to Level Complete → World 1 → Level 5, and a second through Level 5 to
Level Complete → World 1 → Back → Home. `design/renders/device_emulator_flow.png`
shows the emulator's own screenshots. Level 6 had passed its own checks on an
emulator on its branch (`design/renders/level6_on_emulator.png`). The merged app
(0.7.0) is verified by `GameFlowTest` above; the device run of the extended
checklist (now through Level 6 and World 1 Complete) has not been repeated on an
emulator yet.

Other tests:

| Test | What it covers |
|---|---|
| `SliceRulesTest` | Cutting, slow drags, misses, chests and loot, barrels, combo chain and reset, drops, multi-cut bonus, the intro, win/lose, pause |
| `SlicePlaythroughTest` | Mystic Harvest's balance, with the three players above |
| `SliceScreenshotTest` | Mystic Harvest frames to `app/build/level4-shots/` |
| `ScreensFitTest` | Home, the map (with the World 2 gate) and World 1 Complete on five phone shapes (20:9 punch-hole, 19.5:9 notch, 16:9, 21:9 cutout, 720×1600): nothing cut off, all inside the safe area; World 1's levels, names and kinds |
| `FullScreenTest` | The same five shapes for Level 5 and Mystic Harvest |
| `StormRulesTest`, `StormScreenshotTest`, `StormFullScreenTest`, `StormPreviewTest` | Level 6's rules (with a careful bot that finishes with zero hits), a played run's frames to `app/build/level6-shots/`, five phone shapes, and the preview render `design/renders/level6_preview.png` |
| `design/renders/` | `flow.png` (the journey), `level4_slicing.png`, `home_phones.png`, `map_phones.png`, `device_emulator_flow.png` |
| `ColorShiftRulesTest`, `PlaythroughTest`, `ScreenshotTest` | Level 5's rules, a full bot playthrough, and frame-0 fidelity to its design |

## Regenerating the art

```
pip install -r tools/assets/requirements.txt
export LAMA_MODEL=/path/to/big-lama.pt   # github.com/enesmsahin/simple-lama-inpainting releases
python3 tools/home/segment.py && python3 tools/home/build.py /tmp/home_work   # Home (segment.py needs SAM_MODEL)
python3 tools/map/build.py                                                    # World 1 map
python3 tools/level4/build.py /tmp/level4_work                                # Mystic Harvest (runs lagoon.py too)
python3 tools/assets/build_plate.py /tmp/level5_work && python3 tools/assets/build_sprites.py /tmp/level5_work
python3 tools/level6/build6.py                                                # Level 6
```

LaMa redraws a ghost of whatever a tight, object-shaped hole held. The build
scripts therefore grow each hole well past its object, and fill regions that touch
the image edge another way (painted sky, mirrored margins).
