# Island Blast — working notes

## Owner's standing instructions
- After **every** completed task: commit, push to the working branch on GitHub, and tell the
  owner explicitly that it was saved (with the commit id).
- When a task is finished, send the latest debug APK (`dist/IslandBlast.apk`) so the owner
  can preview it on their phone.
- Approved designs in `design/*_reference.png` are the visual spec: keep artwork, colours,
  HUD and identity unchanged unless asked; changes are to gameplay/framing only.
- Level 5 is **Temple Chase** (`design/level5_temple_chase_reference.png`). The owner does
  not want the Color Shift block-shooter as Level 5; it is out of the menu (code kept,
  `level=50`).
- Level 6 is Storm Dodge. The owner asked to review the visual preview
  (`design/renders/level6_preview.png`) before further gameplay changes.

## Build and test
- `./gradlew :app:assembleDebug` then copy `app/build/outputs/apk/debug/app-debug.apk` to `dist/IslandBlast.apk`.
- `./gradlew :app:testDebugUnitTest` runs rules, playthroughs, screenshots, phone-shape tests.
- Art pipelines: `tools/temple` (Level 5 Temple Chase), `tools/level6` (Level 6),
  `tools/assets` (Color Shift prototype); need `LAMA_MODEL` (big-lama.pt).
