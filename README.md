# Island Blast — Level 5

Android (Kotlin, API 26+) implementation of the approved full-screen Level 5 design
with the colour-shift shooting mechanic.

- `design/level5_fullscreen_reference.png`: the approved full-screen design
  (840×1871), which is the visual spec.
- `design/level5_reference.png`: the earlier approved screen (1024×1536), kept for
  reference.
- `design/level5_color_shift_reference.png`: the six-stage colour-shift storyboard.
- `design/renders/`: what the app actually draws, rendered by the test suite
  (`frame0_vs_reference.png`, `color_shift_storyboard.png`, `phones.png`).

## How it looks like the reference

The art is **cut from the approved image itself**, not redrawn:

| Layer | Source |
|---|---|
| Temple, stairs, pillars, torches, waterfalls, foliage, sign, stars, pause button, Goal board, timer and score panels, coin, launcher cup and beams | `background.png`: the reference with the moving parts removed (Big-LaMa inpainting) |
| Scenery past the design's edges | `background_ext.png`: the plate outpainted 180 px left and right and 120 px top and bottom (Big-LaMa), brightness-matched to the scene edge |
| Blocks | Each block's own pixels from the reference, plus the same block recoloured into the other four colours using shading ramps learned from the painted blocks |
| Ball | The painted blue ball; the other colours are hue-shifted from it |
| Hit flash, gem shards | The painted explosion's star flash and gems |
| Aim chevrons | Traced from the painted chevrons, at the painted spacing and sizes |
| Live text ("0:36", "1,760", "+30", "Combo x9", the Goal instructions) | Fredoka Bold, size and position fitted to the painted text |

The first frame is the approved screen. Its explosion is the painted one: over
the first second its gems and sparks fly apart, while the light it casts on the
stone, the block rims and the foliage fades where it is. `frame0_vs_reference.png`
shows the design and frame 0 side by side.

The layout is the design's 840×1871 grid, scaled uniformly so it never
stretches. `GameView` scales it to cover the screen edge to edge, but never so far
that a HUD element (pause, sign, timer, Goal, combo, score, coins) leaves the
safe area (camera cutout, system bars, rounded corners). Anything the design does
not cover shows the painted margins of `background_ext.png`, so no phone shape
gets bars. `phones.png` shows five shapes.

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
- **In-between hues.** Seven painted blocks are halfway between two colours
  (five orchid, two crimson-pink). They show as painted on the first frame, then
  settle to purple or red about a second in, so the colour rule is never
  ambiguous.
- **Goal board.** The approved design paints four swatches and a doubled
  "all! al!". The app writes the instructions without the typo, and the swatches
  show the whole five-colour cycle in order: the loaded colour is outlined, and
  colours no longer on the board fade out.
- **Font.** Fredoka Bold is the closest open-licence match. The painted
  "Combo" lettering is slightly narrower, so it is condensed to 80 % width.

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
- `ScreenshotTest`: writes frame 0, the storyboard stages, pause and time-up
  to `app/build/level5-shots/`. Frame 0 must match the approved design: under
  6 % of its pixels may differ by more than 30 (RGB sum).
- `FullScreenTest`: draws the real `GameView` on five phone shapes (20:9
  punch-hole, 19.5:9 notch, 16:9, 21:9 with cutout, small 720×1600). On each it
  checks that every screen edge is painted scenery, every HUD element is inside
  the safe area, the Goal board and combo stay clear of the blocks, and the game
  spans at least 90 % of the width. Frames and a size report go to
  `app/build/level5-shots/phones/`.

## Regenerating the art

```
pip install -r tools/assets/requirements.txt
export LAMA_MODEL=/path/to/big-lama.pt   # from github.com/enesmsahin/simple-lama-inpainting releases
python3 tools/assets/build_plate.py  /tmp/level5_work
python3 tools/assets/build_sprites.py /tmp/level5_work
```

Block rectangles, touch-ups and the painted gems to remove from the scenery
(`STRAY_SHARDS`, `KEEP_EXCEPT`) live in `tools/assets/layout.py`.
`fit_text.py` re-fits the HUD text to the reference.
