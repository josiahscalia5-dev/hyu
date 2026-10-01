# Island Blast

Android (Kotlin, API 26+) game with three screens in one app:

**Home → World 1 map → Level 4 or Level 5 → Level Complete → back to the World 1 map**

| Screen | What it is | Approved reference |
|---|---|---|
| Home | Title screen: logo, character, PLAY, top bar, bottom nav | `design/home_reference_upload.png` |
| World 1 map | "Tropical Islands": levels 1–10 on a chain of islands; tap a level to play it | none (designed for this app) |
| Level 4 "Mystic Harvest" | Treasure slicing: swipe to cut gems, coins, crates, barrels and chests | `design/level4_reference.png` |
| Level 5 "Temple of Colours" | Colour-shift block shooting (unchanged gameplay) | `design/level5_fullscreen_reference.png` |

The app opens on Home. PLAY (or the Levels tile) opens the World 1 map. Levels 4
and 5 are playable; the other buttons are the rest of World 1, shown as "coming
soon" until they are built. Finishing a level shows Level Complete with its stars.
**Continue** returns to the map, where the stars are saved and the player marker
moves on. Android Back pauses a level (and resumes it), goes from the map to Home,
and from Home closes the app.

## Architecture: adding Level 6, 7, 8…

Every screen lives in one activity (`MainActivity`). `app/GameFlow` moves between
them and `app/Navigator` shows one at a time. Levels are data:

- **`assets/worlds.json`** lists every world and level: number, name, map position
  and, for a playable level, its `kind` (which gameplay) and `dir` (its asset
  folder). A level with no `kind` shows on the map as coming soon.
- **`levels/LevelKinds`** maps each kind to code that builds the level:
  `treasure-slice` (Level 4) and `color-shift` (Level 5).
- **`levels/LevelPlay`** is what every level gives the host: its view, state,
  score, stars and goal, plus pause and restart.
- **`levels/LevelScreen`** hosts any level. It draws the pause menu (Resume,
  Restart, World Map) and the result card (Replay and Continue, or Map and Try
  Again). It saves the result in `levels/Progress` (best stars and score per
  level) and returns to the map.

To add **another level of an existing kind** (no code changes):

1. Put its art and settings in a new asset folder, e.g. `assets/level6/`, in the
   same format as `level4/` (slicing: `layout.json`, sprites, `rules.json`) or
   `level5/` (colour shift).
2. In `worlds.json`, give level 6 a `"name"`, `"kind"` and `"dir": "level6"`.
   An optional `"config"` overrides any setting in its `rules.json`, e.g.
   `"config": {"seconds": 45, "starScores": [4000, 20000, 60000]}`.

To add a **new kind of gameplay**, write a view that extends `StageView` and
implements `LevelPlay`, then register one `LevelKind` for it in `LevelKinds`. To add
**World 2**, add a world to `worlds.json` and its map to
`assets/maps/<world id>/` (`map.json` and the backdrop).

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
the path crosses water. Level 4's island has its treasure chest and gems; Level
5's has a stack of its colour blocks. Each level button shows:

- its number;
- the stars earned (empty stars show what is left);
- its name, for built levels.

A lock and "Soon" mark levels not built yet. The next level to play glows and
carries the player's marker; after Continue the marker walks over to the next
level. The header shows the world, a back button and the stars earned in the
world. The map covers the screen edge to edge but is never scaled so far that a
level button leaves the safe area.

## Level 4 "Mystic Harvest": treasure slicing

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

## Level 5 "Temple of Colours": colour-shift blocks

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

## Decisions to review

- **Levels 1–3 and 6–10** are on the map as "coming soon", since only Levels 4 and
  5 have designs. Both built levels are open from the start, so either can be
  played first. The marker points to the first one not yet completed.
- **Level 4 starts fresh** (score 0, 1:00, no stars), so it has a real start and
  goal. The painted "1,240", "0:28" and "Combo x8" were a mid-game snapshot.
  **Level 5 still opens as painted** (score 1,760, 0:36, two stars), as before;
  `Rules` holds those values.
- **Level 4's level length:** each barrel adds 2 s, so a player who slices every
  barrel plays past 60 s. Set `"barrelSeconds": 0` to keep it at exactly a
  minute.
- **Level 5's name**, "Temple of Colours", is a placeholder (the design has no
  title); change `name` in `worlds.json`.
- **The World 1 map** had no reference, so it was designed for this app.
- **Home's coins, gems and "Lv. 12"** are the painted values.
- **Font:** Fredoka Bold, the closest open-licence match to the painted lettering.

## Build and test

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # everything below
```

**`GameFlowTest` is the whole game end to end**, with real touches through the real
`MainActivity`: Home → PLAY → World 1 → Level 4 played to Level Complete →
Continue → World 1 → Level 5 played to Level Complete → Continue → World 1 →
Back → Home. On the way it checks:

- **Home:** everything is on screen.
- **Map:** every level button is on screen. A coming-soon level keeps you on the
  map.
- **Level 4** (`SliceChecklist`), with real swipes:
  - every crossed treasure is cut and its two halves fly;
  - chests lose one hit per swipe and then spill loot;
  - barrels add time;
  - the score equals the sum of every cut, and the stars follow it;
  - the pause button stops the clock and Resume restarts it;
  - time up gives Level Complete.
- **Level 5** (`Level5Checklist`): each shot's clears, one-step colour shifts,
  next ball and HUD, plus on-screen colour checks.
- **After each level:** the map shows the saved stars and the marker has moved.

Screens go to `app/build/flow/`.

The same checklist runs on a phone or emulator:

```
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.islandblast.game.DeviceEndToEndTest \
    com.islandblast.game.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.islandblast.game/files/e2e   # screenshots + report
```

The harness holds the game clock while it plans a swipe or shot and reads
screenshots; the clock runs between moves.

Other tests:

| Test | What it covers |
|---|---|
| `SliceRulesTest` | Cutting, slow drags, misses, chests and loot, barrels, combo chain and reset, drops, multi-cut bonus, the intro, win/lose, pause |
| `SlicePlaythroughTest` | Level 4's balance, with the three players above |
| `SliceScreenshotTest` | Level 4 frames to `app/build/level4-shots/` |
| `ScreensFitTest` | Home and the map on five phone shapes (20:9 punch-hole, 19.5:9 notch, 16:9, 21:9 cutout, 720×1600): nothing cut off, all inside the safe area |
| `FullScreenTest` | The same five shapes for Levels 4 and 5 |
| `ColorShiftRulesTest`, `PlaythroughTest`, `ScreenshotTest` | Level 5's rules, a full bot playthrough, and frame-0 fidelity to its design |

## Regenerating the art

```
pip install -r tools/assets/requirements.txt
export LAMA_MODEL=/path/to/big-lama.pt   # github.com/enesmsahin/simple-lama-inpainting releases
python3 tools/home/segment.py && python3 tools/home/build.py /tmp/home_work   # Home (segment.py needs SAM_MODEL)
python3 tools/map/build.py                                                    # World 1 map
python3 tools/level4/build.py /tmp/level4_work                                # Level 4 (runs lagoon.py too)
python3 tools/assets/build_plate.py /tmp/level5_work && python3 tools/assets/build_sprites.py /tmp/level5_work
```

LaMa redraws a ghost of whatever a tight, object-shaped hole held. The build
scripts therefore grow each hole well past its object, and fill regions that touch
the image edge another way (painted sky, mirrored margins).
