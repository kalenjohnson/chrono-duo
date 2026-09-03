# ChronoDuo

Dual-screen host app for **Chrono Trigger (Upgrade Ver.)** on dual-screen Android
handhelds like the Ayn Thor: the real game runs full-widescreen on the top screen,
and a companion display (map / party status / inventory, DS-style) runs on the
bottom screen.

ChronoDuo ships **no game code or assets**. It requires the official
[CHRONO TRIGGER (Upgrade Ver.)](https://play.google.com/store/apps/details?id=com.square_enix.android_googleplay.chrono)
app (arm64 build, v2.1.5+) installed on the same device. The DS ROM is optional —
import it from the settings page to enable indoor area maps on the companion screen.
At startup ChronoDuo locates the game install, extracts its `libchrono.so`/`libc++_shared.so`
into ChronoDuo's private storage, points the engine's asset loading at the game's own
APK, and boots the engine inside ChronoDuo's process — where we control the second
screen via Android's standard `Presentation` API.

## Status (2026-09-04)

- [x] Game boots and plays full-widescreen inside ChronoDuo (Ayn Thor Lite)
- [x] DS-style second screen: real portraits in the game's own window chrome,
      HD sepia world map (torn-paper parchment) with a live position marker,
      live location name, gold and play time
- [x] Live battle mode: real-time party HP, named enemy bars (honoring the
      game's hidden-info flags by default, eye-toggle to reveal), fades
- [x] Full battle mirroring: command menu (Attack/Tech/Item) + Tech/Item lists
      on the bottom screen, top screen HUD-free
- [x] DS-style room maps: indoor area floor plans with live position marker
      when the user imports their own Chrono Trigger DS ROM from the settings
      page (.nds or .zip; nothing from the ROM is shipped)
- [ ] Someday: ATB gauges, overworld era maps, optional cheats via the
      game's own ExperiencePlus

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

ChronoDuo is released under the [MIT License](LICENSE). See
`THIRD_PARTY_NOTICES.md` for bundled third-party code.

The vendored `org.cocos2dx.lib` Java classes are from cocos2d-x 3.14.1
(MIT). No Square Enix assets or code are included: the app loads the
official Chrono Trigger Android game you have installed, and the optional
indoor maps are decoded on your device from your own Chrono Trigger DS ROM.
