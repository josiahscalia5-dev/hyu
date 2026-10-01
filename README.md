# Island Blast — Levels 5 and 6

The app opens on a level select screen: **Level 5 (Temple Chase)** and
**Level 6 (Storm Dodge)**. Back returns to it.

## Level 5 — Temple Chase

Spec: `design/level5_temple_chase_reference.png`. Everything on screen is cut
from that painting by `tools/temple/build_temple.py`. The opening frame is the
painting itself (`design/renders/temple_frame0_vs_reference.png`). From there:

- **The world moves, the painting stays recognisable.**
  - The temple, the golem, the lavafalls, the far towers and the top HUD are a static
    backdrop.
  - The stone path scrolls toward the runner. It is a sharp, seamless texture
    unprojected from the painted path near the camera.
  - The side scenery is separate 3D props cut from the painting: block stacks with the
    lavafall, the chevron blocks, the rope fence, platforms and palms. They pass by at
    their own depth and keep their shape; nothing is stretched with the ground.
  - The lava is molten cells coloured from the painted lava's own colours, with a hot rim
    along the path, a slow shimmer, and embers.
- **Controls:**
  - The arrow buttons move one lane; dragging anywhere steers under the finger.
  - The lightning, shield and magnet buttons use a power-up (3 each, as painted).
  - Pause button: pause.
- **Hazards:**
  - Lava boulders thrown by the golem roll down the path.
  - Stone chevron blocks stand in lanes.
  - A hit costs a heart (2 hearts, as painted), slows the runner, and gives a moment
    without hits. Losing both hearts means CAUGHT.
- **Pickups:** coins (+10, coin counter), gems (+50), and lightning/shield/magnet tokens
  (+1 to that button).
- **Power-ups:**
  - **Lightning:** 3.5 s sprint at 1.55x speed that smashes through hazards (+30 each).
  - **Shield:** absorbs one hit (10 s).
  - **Magnet:** pulls in coins and gems from all lanes (8 s).
- **Sections:** Lava Path, Boulder Alley, Golem's Wrath, Temple Escape.
  - Speed and hazards rise section by section.
  - Each new section is a checkpoint that adds time. The timer starts at 0:35, as painted.
  - Every hazard row leaves an open lane, and it moves at most one lane per row.
- **Score and stars:** the level opens as painted (1,960 points, 124 coins, 2 of 3 stars).
  - Stars light up at 1,000, 1,500 and 4,500 points.
  - Escaping adds +20 per second left and +250 per heart left.
- **End:**
  - **TEMPLE ESCAPED!** shows the stars and a tally. Tap to run again.
  - **CAUGHT!** and **OUT OF TIME!** offer a retry.

Tests:

- `TempleRulesTest`: the rules. A careful bot escapes with 0 hits and 3 stars, and a bot
  without power-ups still escapes.
- `TempleScreenshotTest`: plays the whole level through `TempleView` with real taps on
  the arrows and every power-up button, including one deliberate crash. Frames are written
  to `app/build/temple-shots/`.
- `TempleFullScreenTest`: five phone shapes, with the HUD in the safe area and the runner
  clear of the buttons.
- `TempleAppTest`: the real app. The level select's Level 5 card opens Temple Chase, it
  runs, the arrows steer, and Back returns.

Art: `LAMA_MODEL=/path/big-lama.pt python3 tools/temple/build_temple.py`. Add `--reuse` to
keep the inpainted plates. `tools/temple/preview.py` renders scrolled frames for quick
checks.

The earlier Level 5 prototype (Color Shift, documented below) is no longer in the menu.
Its code and tests remain; launch it with the intent extra `level=50`.

---

## Level 6 — Storm Dodge

Spec: `design/level6_storm_dodge_reference.png`. Art cut from it by
`tools/level6/build6.py`: sky plate with the HUD and lightning, extended past
every edge; a seamless water tile quilted from open river; the jet ski,
barrels, spiked mine, X-crate, logs, coins, shield and X hazard sprites.

- **Controls:** the arrow buttons move one lane; dragging anywhere steers
  under the finger. Pause button: pause.
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
  temple. The tally adds a time bonus (+40/s left) and a no-hit bonus
  (+500).
- **Stars:** 3 for at least 70% of coins and at most 1 hit; 2 for up to 4
  hits; otherwise 1.

Tests: `StormRulesTest` covers the rules. It includes a careful bot that
finishes with zero hits. `StormScreenshotTest` plays to the finish through
`StormView`, with one deliberate crash and a shield pickup.
`StormFullScreenTest` covers five phone shapes.

---

# Color Shift (earlier Level 5 prototype, not in the menu)

Android (Kotlin, API 26+) implementation of the first approved Level 5 screen with the
colour-shift shooting mechanic.

- `design/level5_reference.png`: the approved screen, which is the visual spec.
- `design/level5_color_shift_reference.png`: the six-stage colour-shift storyboard.
- `design/renders/`: what the app actually draws, rendered by the test suite
  (`frame0_vs_reference.png`, `color_shift_storyboard.png`, `phone_20x9.png`).

## How it looks like the reference

The art is **cut from the approved image itself**, not redrawn:

| Layer | Source |
|---|---|
| Temple, stairs, pillars, torches, foliage, sign, stars, pause button, timer and score panels, coin, launcher beams | `background.png`: the reference with the moving parts removed (Big-LaMa inpainting) |
| Blocks | Each block's own pixels from the reference, plus the same block recoloured into the other four colours using shading ramps learned from the painted blocks |
| Ball | The painted blue ball; the other colours are hue-shifted from it |
| Hit flash, gem shards | The painted explosion's star flash and gems |
| Aim chevrons | Traced from the painted chevrons, at the painted spacing and sizes |
| Live numbers ("0:36", "1,760", "+30", "Combo x9") | Fredoka Bold, size and position fitted to the painted text |

The first frame is the approved screen. Its explosion is the painted one, which
then flies apart. `frame0_vs_reference.png` shows both side by side.

The layout is the reference's 1024×1536 grid scaled uniformly, so it never
stretches. On taller phones the extra height shows a mirrored blur of the scene
edges.

## Colour-shift mechanic

1. The launcher holds a coloured ball (blue to start). Drag anywhere to aim and
   release to shoot. The aim guide shows exactly where the ball will first hit.
2. If the ball hits a block of **its own colour**, that block and every
   connected block of the same colour burst.
3. Every surviving block that **touched** the cleared group then steps one
   colour along a fixed cycle:
   **blue → purple → pink → red → yellow → blue**. Changed blocks spin into
   their new colour and keep a swirl badge until the next shift.
4. The next ball loads in the colour of the biggest group the ball can
   actually reach from the launcher, straight or off a wall. Tap the ball to
   switch to any other colour still on the board.
5. A ball that hits a different colour bounces off, so bank shots work. If it
   rolls back to the launcher the shot is a miss and the combo resets.
6. Clear every block before the timer runs out.

The rule is deterministic, so players can plan ahead. While aiming at a
matching group, the group is outlined and each block that will shift shows a
dot in its next colour. The Goal panel lists the cycle left to right and
highlights the loaded colour.

Scoring: each clear adds `10 × blocks × combo` points and 1 coin per block.
Stars are earned at 1,000, 1,500 and 4,000 points. Clearing the board adds
20 points per second left. All values are in `Rules` in
`model/Level5Game.kt`.

## Decisions to review

- **Starting values.** The level opens exactly as painted: score 1,760, 0:36,
  Combo x9, +30, two stars. `Rules` holds these values if you want a fresh
  start instead.
- **Time bonus.** 36 seconds is very tight for 31 blocks, so each cleared
  block adds 0.5 s. The timer briefly flashes green when this happens. Set
  `timeBonusPerBlock = 0f` to turn it off.
- **In-between hues.** Four painted blocks are halfway between two colours
  (two orchid, one crimson, one hot pink). They show as painted on the first
  frame, then settle to purple or red about a second in, so the colour rule is
  never ambiguous.
- **Goal panel.** It is not in the single approved screen. It was added from
  the colour-shift storyboard, under the pause button.
- **Font.** Fredoka Bold is the closest open-licence match. The painted
  "Combo" lettering is slightly narrower, so it is condensed to 82 % width.

## Build and test

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # rules, playthroughs, screenshots, frame-0 fidelity
python3 tools/assets/check_assets.py  # art QA: colours, fragments, holes, overlaps
```

### End-to-end checklist (real app, real touches)

`app/src/sharedTest/.../e2e/Level5Checklist.kt` plays the whole level through the
real `MainActivity` and `GameView`: it drags to aim, releases to shoot, and taps
the ball to switch colour. After every shot it checks:

- **Clears:** the matching group is cleared.
- **Shifts:** every touching block stepped exactly one colour, and every other
  block kept its colour.
- **Next ball:** it is reachable and correctly chosen.
- **HUD:** score, combo, coins, stars and timer match the rules exactly.
- **The screen:** screenshots are read back. Every live block and the ball must
  show their colour, and every cleared block's area must match the temple
  background (no leftover fragments).

The run ends on Level Complete and taps to restart.

It runs two ways:

- `AppEndToEndTest` (Robolectric, runs in `testDebugUnitTest`).
- `DeviceEndToEndTest` on a phone or emulator:

  ```
  ./gradlew :app:installDebug :app:installDebugAndroidTest
  adb shell am instrument -w -e class com.islandblast.game.DeviceEndToEndTest \
      com.islandblast.game.test/androidx.test.runner.AndroidJUnitRunner
  adb pull /sdcard/Android/data/com.islandblast.game/files/e2e   # screenshots + report
  ```

The harness holds the game clock while it computes shots and reads screenshots
(on a slow emulator that takes far longer than a player would). The clock runs
for the aiming pause, the ball's flight, the clear and its effects.

The tests run the real game code headless (Robolectric native graphics):

- `ColorShiftRulesTest`: starting state, groups, one-step shifts, bounces and
  misses, tap-to-switch.
- `PlaythroughTest`: a bot aims with the aim guide and clears the whole level
  with 1.2 s of aiming per shot. It checks every group clear and every
  neighbour's colour after every shift.
- `ScreenshotTest`: writes frame 0, the storyboard stages, pause, time-up and
  a 1080×2400 phone frame to `app/build/level5-shots/`.

## Regenerating the art

```
pip install -r tools/assets/requirements.txt
export LAMA_MODEL=/path/to/big-lama.pt   # from github.com/enesmsahin/simple-lama-inpainting releases
python3 tools/assets/build_plate.py  /tmp/level5_work
python3 tools/assets/build_sprites.py /tmp/level5_work
```

Block rectangles and touch-ups live in `tools/assets/layout.py`.
`fit_text.py` re-fits the HUD text to the reference.
