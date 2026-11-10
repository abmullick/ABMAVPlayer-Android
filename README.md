# ABM AV Player for Android

**Product:** ABM AV Player  
**Product owner:** Abhisek Basu Mullick  
**Copyright:** © 2026 Abhisek Basu Mullick. All rights reserved.

The native Android companion to [ABM AV Player for Windows](https://github.com/abmullick/ABMAVPlayer). The goal is a consistent ABM product identity and feature set, implemented with Android-native playback, storage, background services and casting.

> This repository is being initialized. The first milestone establishes the Android application foundation and playback experience; it does not yet claim complete Windows feature parity. See [FEATURE_PARITY.md](docs/FEATURE_PARITY.md) and [ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Product direction

- Native Kotlin application with Jetpack Compose.
- Premium ABM dark interface: graphite surfaces, cyan/violet accents, cinematic artwork and restrained motion.
- Media3/ExoPlayer as the Android playback and media-session foundation.
- Android Storage Access Framework for user-selected files.
- Room for persistent playlists, queue metadata and playback history.
- Google Cast SDK for native Cast discovery and sessions.
- Android WebView for the separate Streaming Centre.
- Advanced codec support to be evaluated through a separately integrated native engine (FFmpeg/libmpv) where Android platform codecs are insufficient.

## Build

Requirements:
- Android Studio with Android SDK 35
- JDK 17
- Android Gradle Plugin 8.7.3 / Gradle 8.9
- Android device or emulator running Android 8.0 (API 26) or newer

Open the repository in Android Studio and sync Gradle. Run the `app` configuration.

Command line (after installing the Gradle wrapper or using a local Gradle 8.9 installation):

```bash
gradle :app:assembleDebug
```

The debug APK is normally produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Current implementation

- Native Compose application shell and ABM visual identity.
- Local audio/video file selection through Android's document picker.
- Media3 playback, seek, play/pause and player surface.
- Initial project structure and feature-parity roadmap.

## Planned milestones

1. **Foundation:** app shell, local playback, lifecycle handling and visual identity.
2. **Library:** device media indexing, artwork, playlists, M3U import/export, queue, history, shuffle and repeat.
3. **Playback tools:** speed control, audio/subtitle track selection, external subtitles and equalizer.
4. **Casting:** Google Cast discovery, device picker, remote playback state and recovery.
5. **Streaming Centre:** isolated WebView, provider launch cards, browser controls and supported native Cast handoff.
6. **Hardening:** background playback, notification/lock-screen controls, Android Auto consideration, accessibility, tests, signed release and in-app updates.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Windows-to-Android feature parity](docs/FEATURE_PARITY.md)

## Licensing

See [LICENSE](LICENSE). The Windows product is separately documented in its own repository. Do not assume third-party codec, Cast or native-engine dependencies have the same licensing terms as this application.
