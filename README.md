# ChronoDuo

Dual-screen host app for **Chrono Trigger (Upgrade Ver.)** on dual-screen Android
handhelds like the Ayn Thor: the real game runs full-widescreen on the top screen,
and a companion display (map / party status / inventory, DS-style) runs on the
bottom screen.

ChronoDuo ships **no game code or assets**. It requires the official
[CHRONO TRIGGER (Upgrade Ver.)](https://play.google.com/store/apps/details?id=com.square_enix.android_googleplay.chrono)
app (arm64 build, v2.1.5+) installed on the same device. At startup ChronoDuo
locates that install, extracts its `libchrono.so`/`libc++_shared.so` into
ChronoDuo's private storage, points the engine's asset loading at the game's own
APK, and boots the engine inside ChronoDuo's process — where we control the second
screen via Android's standard `Presentation` API.

## Status

- [x] Phase 1: the game boots and renders full-widescreen inside ChronoDuo
      (verified on Ayn Thor Lite, 2026-09-03: intro scene, music, 1920×1080)
- [x] Second screen Presentation confirmed on the Thor's bottom panel
      (display id 4, 1240×1080) — placeholder UI for now
- [ ] Phase 2: real companion UI on the bottom screen
- [ ] Phase 3: live game state (map, party, inventory) feeding the second screen
- [ ] Input: verify touch/controller in-game; route physical controls if needed

## Build

Requires an Android SDK (see `local.properties`) and a JDK Gradle supports
(`gradle.properties` pins `org.gradle.java.home`).

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## How it works

See `NOTES.md` for the research and design record.
