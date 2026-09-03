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

## Status (2026-09-03 — one very long day)

- [x] Game boots and plays full-widescreen inside ChronoDuo (Ayn Thor Lite)
- [x] DS-style second screen: real portraits in the game's own window chrome,
      HD sepia world map (torn-paper parchment) with a live position marker,
      live location name, gold and play time
- [x] Live battle mode: real-time party HP, named enemy bars (honoring the
      game's hidden-info flags by default, eye-toggle to reveal), fades
- [x] Bottom-screen battle CONTROLS: Attack/Tech/Item buttons synced to the
      real command menu, tap-injected into the game; targeting and submenu
      navigation rows; selection highlight mirrors the game's cursor, so
      controller and touch drive one shared menu
- [x] Top-screen cleanup: field/world menu buttons parked off-screen every
      frame; battle command UI faded to zero opacity (inputs unaffected)
- [ ] Someday: tech names on the list buttons, ATB gauges, battle MP,
      per-era DS maps, optional cheats via the game's own ExperiencePlus

## Build

Requires an Android SDK (see `local.properties`) and a JDK Gradle supports
(`gradle.properties` pins `org.gradle.java.home`).

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## How it works

See `NOTES.md` for the research and design record.

## License

ChronoDuo is released under the [MIT License](LICENSE).

The vendored `org.cocos2dx.lib` Java classes are from cocos2d-x 3.14.1
(MIT). No Square Enix assets or code are included: the app loads the
official Chrono Trigger Android game you have installed, and the optional
indoor maps are decoded on your device from your own Chrono Trigger DS ROM.
