# Lumora

Offline liquid-glass photo gallery for Android 13+ (Java, no libraries).

## Structure
- `GlassPanel.java`  live blur + refraction glass (reuse it for every new glass control)
- `Aurora.java`      animated colour backdrop used by the welcome screen
- `MainActivity.java` tabs, grid, viewer, data loading
- `res/`             icon (adaptive + themed), splash, theme
- `app/lumora.keystore` fixed signing key, so updates install over the old app

## Build
Push to GitHub. Actions builds the APK (artifact `Lumora-apk`).

## Roadmap
Videos, search, multi-select, trash + restore, locked hidden folder, photo info sheet,
editor, slideshow, duplicate finder, memories, settings screen.
