# ABM AV Player for Android

**Product:** ABM AV Player  
**Product owner:** Abhisek Basu Mullick  
**Copyright:** © 2026 Abhisek Basu Mullick. All rights reserved.

The native Android companion to [ABM AV Player for Windows](https://github.com/abmullick/ABMAVPlayer). The goal is a consistent ABM product identity and feature set, implemented with Android-native playback, storage, background services and casting.

> The Android foundation, media-library milestone and initial advanced playback tools are implemented. Windows feature parity remains an active roadmap; casting, the Streaming Centre, M3U import/export and ReplayGain metadata normalisation are not yet included. See [FEATURE_PARITY.md](docs/FEATURE_PARITY.md) and [ARCHITECTURE.md](docs/ARCHITECTURE.md).

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
- Local audio/video import through Android's Storage Access Framework with persisted URI access.
- Room-backed media library and playlists.
- Media3 playback controlled through a MediaSessionService, with a persistent playback notification/session.
- Queue playback, previous/next, seek, shuffle and repeat modes.
- Playback history timestamps and media removal.
- Compose library screen with Media and Playlists views.
- Playback speed presets from 0.5x to 2.0x.
- Embedded audio/subtitle track selection and subtitle-off control.
- External SRT, WebVTT and SSA/ASS subtitle attachment.
- Immersive fullscreen video layout.
- App-owned ten-band peaking-EQ DSP in Media3's decoded PCM pipeline, with persistent gains and seven presets.
- Output preamp control and automatic headroom compensation against the highest positive EQ band.
- Configurable peak-detection dynamic-range compressor with threshold, ratio, attack/release timing and make-up gain; settings persist between sessions.
- ReplayGain normalisation from supported embedded Vorbis comments and ID3 user-text/comment frames, including track/album gain and peak tags, plus R128 gain tags. Track or album mode is selectable and peak metadata constrains positive gain.

## Planned milestones

1. **Foundation:** app shell, local playback, lifecycle handling and visual identity.
2. **Library (in progress):** broader device media indexing, artwork, M3U import/export and persistent queue restoration. Current release supports SAF-imported files, Room library records, basic playlists, playback history and Media3 shuffle/repeat.
3. **Playback tools:** initial advanced controls implemented; Additional ReplayGain container/tag coverage, subtitle timing/offset controls and DSP/device performance testing remain.
4. **Casting:** Google Cast discovery, device picker, remote playback state and recovery.
5. **Streaming Centre:** isolated WebView, provider launch cards, browser controls and supported native Cast handoff.
6. **Hardening:** background playback, notification/lock-screen controls, Android Auto consideration, accessibility, tests, signed release and in-app updates.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Windows-to-Android feature parity](docs/FEATURE_PARITY.md)

## Licensing

See [LICENSE](LICENSE). The Windows product is separately documented in its own repository. Do not assume third-party codec, Cast or native-engine dependencies have the same licensing terms as this application.
