# ChronoDuo

[![Build](https://github.com/kalenjohnson/chrono-duo/actions/workflows/build.yml/badge.svg)](https://github.com/kalenjohnson/chrono-duo/actions/workflows/build.yml)
[![Latest APK](https://img.shields.io/github/v/release/kalenjohnson/chrono-duo?include_prereleases&label=download)](https://github.com/kalenjohnson/chrono-duo/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A dual-screen host for **Chrono Trigger (Upgrade Ver.)** on dual-screen Android
handhelds such as the Ayn Thor. The real game runs full-widescreen on the top
screen while the bottom screen becomes a DS-style companion display: a live
world map, party status, and the whole battle command menu.

ChronoDuo ships **no game code or assets**. It loads the official Chrono
Trigger Android app you already own, inside its own process, and drives the
second screen through Android's standard `Presentation` API.

<p align="center">
  <img src="docs/screenshots/overworld.webp" width="640" alt="Overworld on the top screen, the parchment world map with a live position marker on the bottom">
</p>

## Requirements

- A dual-screen Android device (developed on the Ayn Thor Lite). Any device
  that exposes a second display to `Presentation` should work.
- Android 8.0+ with an arm64 CPU.
- The official [CHRONO TRIGGER (Upgrade Ver.)](https://play.google.com/store/apps/details?id=com.square_enix.android_googleplay.chrono)
  from Google Play, version 2.1.5 or newer, installed on the same device.
- Optional: your own Chrono Trigger DS ROM (`.nds` or `.zip`) to enable indoor
  area maps. Nothing from the ROM is shipped or uploaded; it is decoded on
  your device.

## Install

1. Install Chrono Trigger from Google Play and launch it once.
2. Download `ChronoDuo-<version>.apk` from the
   [Releases page](https://github.com/kalenjohnson/chrono-duo/releases) and
   install it (allow installs from unknown sources, or `adb install`).
3. Launch **ChronoDuo** instead of Chrono Trigger. The game boots on the top
   screen and the companion display appears on the bottom.

## What the bottom screen does

- **Overworld map** in a sepia, torn-parchment style with a live position
  marker, the current location name, gold, and play time. All eight era maps
  are rendered on first launch from the game's own map data, and story
  changes such as bridges and craters show up as they happen.
- **Party status** with real portraits in the game's own window chrome and
  live HP and MP.
- **Battle mirroring**: the Attack/Tech/Item menu and the Tech and Item
  lists move to the bottom screen, driven by the d-pad, so the top screen
  stays HUD-free. Enemy HP bars honor the game's hidden-info design by
  default, with an eye toggle to reveal them.
- **Indoor area maps** with a live marker once you import your DS ROM.
- **Original pixel art**: a settings toggle rebuilds the unfiltered SNES-style
  character sprites, field chip sheets, and overworld tiles from the 1x art
  the game itself ships.

<p align="center">
  <img src="docs/screenshots/battle.webp" width="420" alt="Battle with the Attack, Tech and Item menu mirrored to the bottom screen">
  <img src="docs/screenshots/forest.webp" width="420" alt="Guardia Forest with the DS area map on the bottom screen">
</p>

### Settings

Tap the gear in the top-left corner of the bottom screen (outside battle) to
open settings. There you can import a DS ROM through the system file picker,
build the original pixel-art sheets, and see how many world maps have been
rendered.

## Build from source

You need an Android SDK with NDK and CMake 3.22+, and a JDK 17 through 21.

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

`gradle.properties` pins `org.gradle.java.home` to a local JDK 21 path.
Adjust it, or override on the command line:

```
./gradlew -Dorg.gradle.java.home=/path/to/jdk21 assembleDebug
```

### Releases

GitHub Actions builds every push. Pushes to `main` refresh the rolling
**Latest build** pre-release; a `v*` tag creates a versioned release. See
`NOTES.md` for signing setup.

## How it works

At startup ChronoDuo locates the game install, extracts its `libchrono.so`
and `libc++_shared.so` into private storage, points the engine's asset
loading at the game's own APK, and boots the engine inside ChronoDuo's
process. A small native helper reads the live game state (party, map
position, battle) and the Java side draws the companion screen.

`NOTES.md` is the full research and design record: the memory layout, the
battle UI work, the DS map decoder, and the on-device art rebuilds. The
`tools/` directory holds the Python and Java verification scripts and their
reports.

## License

ChronoDuo is released under the [MIT License](LICENSE).
`THIRD_PARTY_NOTICES.md` covers bundled third-party code: the vendored
`org.cocos2dx.lib` Java classes from cocos2d-x 3.14.1 (MIT) and
android-async-http (Apache 2.0).

Chrono Trigger is the property of Square Enix. No Square Enix assets or code
are included. The app loads the official Android game you have installed,
and the optional indoor maps are decoded on your device from your own DS ROM.
