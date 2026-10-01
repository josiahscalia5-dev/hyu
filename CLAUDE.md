# Island Blast — working notes

## Owner's standing instructions
- After **every** completed task: commit, push to the working branch on GitHub, and tell the
  owner explicitly that it was saved (with the commit id).
- When a task is finished, send the latest debug APK (`dist/IslandBlast.apk`) so the owner
  can preview it on their phone.
- Approved designs in `design/*_reference.png` are the visual spec: keep artwork, colours,
  HUD and identity unchanged unless asked; changes are to gameplay/framing only.
- Level 6 (Storm Dodge): the owner asked to review the visual preview
  (`design/renders/level6_preview.png`) before further gameplay changes.
- The app is one experience: Home → World 1 map → Levels 1–6 → Level 6 Complete →
  World 1 Complete → World 2 Unlocked. Never replace Home / the World map with a simpler
  level select. Do not start World 2 until the owner confirms World 1 is stable.
- World 1 per the owner's master spec: 1 Relic Ricochet, 2 Totem Sequence, 3 Coral Current,
  4 Jungle Zip, 5 Temple Chase, 6 Storm Dodge. Levels 1–3 and Jungle Zip do not exist in
  this repo yet; slot 4 holds "Mystic Harvest" (its approved design is titled so) until the
  owner decides. Don't swap or rename levels without verifying with the owner.

## Structure
- `assets/worlds.json`: worlds, levels (name, kind, asset dir, map node), World 2 gate.
- `levels/LevelKinds`: gameplay kinds `treasure-slice` (L4), `color-shift` (L5),
  `storm-dodge` (L6). Each level view extends `StageView` and implements `LevelPlay`;
  `levels/LevelScreen` draws the shared pause menu and result card.
- `app/GameFlow`: navigation; `continueAfter` sends the world's last level to
  `map/WorldCompleteScreen` the first time.
- Backups of the two branches merged on 2026-10-01: `backup/2026-10-01/ccr-e0caed5f-med9a2`
  (Home/Map/L4/L5 base) and `backup/2026-10-01/ccr-95596f48-cuc437` (Level 6 line).

## Build and test
- `./gradlew :app:assembleDebug` then copy `app/build/outputs/apk/debug/app-debug.apk` to `dist/IslandBlast.apk`.
- `./gradlew :app:testDebugUnitTest` runs rules, playthroughs, screenshots, phone-shape tests;
  `GameFlowTest` plays the whole app (Home → L4 → L5 → L6 → World 1 Complete → Home).
- Art pipelines: `tools/home`, `tools/map`, `tools/level4`, `tools/assets` (Level 5),
  `tools/level6` (Level 6); need `LAMA_MODEL`.
