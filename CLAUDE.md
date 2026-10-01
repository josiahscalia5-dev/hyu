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

## Build and test
- `./gradlew :app:assembleDebug` then copy `app/build/outputs/apk/debug/app-debug.apk` to `dist/IslandBlast.apk`.
- `./gradlew :app:testDebugUnitTest` runs rules, playthroughs, screenshots, phone-shape tests.
- Art pipelines: `tools/assets` (Level 5), `tools/level6` (Level 6); need `LAMA_MODEL`.
