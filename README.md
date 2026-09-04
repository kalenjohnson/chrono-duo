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
      sepia-toned overworld map (torn-paper parchment) with a live position
      marker, live location name, gold and play time
- [x] All 8 overworld maps rendered on device, at first launch, straight from
      the game's own map/chip data (no screenshot capture, no ROM needed) --
      see "World maps" below
- [x] Live overworld map: re-composited from the game's own live map data, so
      story changes (bridges, craters) appear on the companion screen and
      persist across launches
- [x] Live battle mode: real-time party HP, named enemy bars (honoring the
      game's hidden-info flags by default, eye-toggle to reveal), fades and
      results window on the bottom screen
- [x] Full battle mirroring: command menu (Attack/Tech/Item) + Tech/Item lists
      on the bottom screen, top screen HUD-free
- [x] DS-style room maps: indoor area floor plans with live position marker
      when the user imports their own Chrono Trigger DS ROM from the settings
      page (.nds or .zip; nothing from the ROM is shipped)
- [x] Original pixel art: a settings toggle restores the unfiltered SNES-style
      character sprites **and field chip sheets**, both rebuilt on-device from
      art the game itself ships (no ROM needed) -- the field chips come back
      from the port's own 1x 4bpp tile banks, so tree roots, chests and
      animated tiles lose their baked-in 2x smoothing
- [ ] Someday: ATB gauges, optional cheats via the game's own ExperiencePlus

## Build

Requires an Android SDK (see `local.properties`) and a JDK Gradle supports
(`gradle.properties` pins `org.gradle.java.home`).

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## World maps

On first launch, ChronoDuo renders all 8 overworld maps (1000 AD, 600 AD,
2300 AD, 65,000,000 BC, and the three 12,000 BC states) straight from the
game's own map and chip-sheet data pulled out of its `resources.bin` -- the
same compositing the game itself does when it draws the overworld, ported to
Java (`WorldMapCompositor`/`WorldMapRenderer`). No screenshot, no map-screen
visit, no ROM required. See `tools/world_map/REPORT.md` for the on-disk
format and `NOTES.md` for the summary.

## How it works

See `NOTES.md` for the research and design record.

## License

ChronoDuo is released under the [MIT License](LICENSE). See
`THIRD_PARTY_NOTICES.md` for bundled third-party code.

The vendored `org.cocos2dx.lib` Java classes are from cocos2d-x 3.14.1
(MIT). No Square Enix assets or code are included: the app loads the
official Chrono Trigger Android game you have installed, and the optional
indoor maps are decoded on your device from your own Chrono Trigger DS ROM.
