# Island Blast — Level 5

Android (Kotlin, API 26+) implementation of the approved Level 5 screen with the
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
4. The next ball loads in the colour of the biggest group on the board. Tap
   the ball to switch to any other colour still on the board.
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
./gradlew :app:testDebugUnitTest    # rules, full playthrough, screenshots
```

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
