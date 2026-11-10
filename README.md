# ABM AV Player for Android

**Product:** ABM AV Player  
**Product owner:** Abhisek Basu Mullick  
**Copyright:** © 2026 Abhisek Basu Mullick. All rights reserved.

The native Android companion to [ABM AV Player for Windows](https://github.com/abmullick/ABMAVPlayer). The goal is a consistent ABM product identity and feature set, implemented with Android-native playback, storage, background services and casting.

> The Android foundation, media library, advanced playback and initial Google Cast queue support are implemented. The first Android Streaming Centre milestone now provides a dedicated WebView, six official provider launch cards, browser navigation, persistent WebView cookies, fullscreen and direct-URL Cast handoff. Windows receiver-discovery parity (DLNA/UPnP and DIAL), YouTube DIAL app launch and broader provider/device validation remain in progress. Google Cast now supports multi-item library and playlist queue transfer with remote previous/next; device and format validation remain. Initial ReplayGain support is implemented for supported embedded tags; broader format coverage and device validation remain. See [FEATURE_PARITY.md](docs/FEATURE_PARITY.md) and [ARCHITECTURE.md](docs/ARCHITECTURE.md).

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
- Google Cast SDK route picker, session lifecycle and remote play/pause/seek controls using the Default Media Receiver.
- Casting imported SAF media through a temporary LAN HTTP server with byte-range responses; direct HTTP/HTTPS media URLs can also be sent to a Cast receiver.

## Streaming Centre

Open **Streaming services** from the main player. The Android Streaming Centre is a separate activity and WebView, independent of local media playback and the main player's media queue. It provides launch cards for YouTube, Netflix, Prime Video, JioHotstar, Sony LIV and ZEE5; Back/Forward/Reload/Home navigation; HTML5 video fullscreen; and Open in browser. WebView cookies and supported login sessions are retained in the app's private WebView profile between launches. Sign in directly with each provider using your own account. ABM AV Player does not collect credentials, proxy provider content or provide subscriptions.

The toolbar's Cast route picker connects to a Google Cast receiver. **Cast video** can hand off an active HTML5 video only when the page exposes a directly addressable HTTPS media URL. It is not browser-tab mirroring. Blob/MSE, encrypted, DRM-protected, segmented or authorization-dependent streams generally cannot be handed off; use the provider's own Cast control or official TV app in those cases. Android WebView playback compatibility depends on the device's WebView/DRM certification and provider policy. The first Android milestone currently offers Google Cast route selection only; the Windows Streaming Centre's combined DLNA/UPnP and DIAL discovery panel and YouTube DIAL app launch are not yet implemented on Android.

## Google Cast

The player uses Google's Default Media Receiver and the Android Cast route picker. A Cast-compatible Chromecast, Google TV or Android TV receiver with Chromecast built-in must be available on the same reachable Wi-Fi/LAN as the phone.

- Tap the Cast icon in the player header (also available in fullscreen) and choose a receiver.
- Select an imported library item to send it to the connected receiver.
- Use the player controls for remote play/pause and seeking. The local player is paused while remote playback is active.
- Imported SAF files are served from the phone through a temporary, tokenized HTTP endpoint with byte-range support. The endpoints are owned by a dedicated media-playback foreground service rather than the Activity, so they remain available when the app is backgrounded or the player screen is destroyed. A persistent Cast notification is shown, and short CPU/Wi-Fi locks keep the phone reachable during receiver playback. The service releases its servers and locks when the Cast session ends. Local Cast queue/source metadata is saved privately so a resumed Cast session can rebuild a lost endpoint and reload the selected item/queue after service or process recreation; this recovery depends on persisted SAF URI permissions. The selected item and its last checkpointed playback position are restored where supported.
- Selecting a library item transfers the Cast-compatible library queue and starts at the selected item. Playing a saved playlist transfers that playlist in its saved order.
- Remote previous/next navigation is supported through the receiver queue. The phone polls the receiver's live media status and synchronises the displayed title and queue position (current item / total items) from the receiver's current queue item, so TV-side track changes are reflected in the sender UI. The recovery checkpoint follows the receiver's current queue index when shuffle is off. Queue shuffle and repeat controls are synchronised with the Cast receiver; shuffle reorders upcoming items while keeping the current item in place, and repeat supports off/all/one. Remote WebVTT subtitle on/off is supported for the currently selected queue item when an external WebVTT sidecar is attached before casting. The phone serves the subtitle over the same temporary LAN endpoint with CORS support. The Default Media Receiver does not support sender-controlled audio-track switching; Google documents that this requires a Custom Receiver. Embedded subtitles, SRT/SSA/ASS sidecars, and per-item subtitle mapping across the full queue are not yet supported.
- Google's Default Media Receiver supports a narrower set of containers/codecs than the Windows player. MP4/WebM and common audio formats are the intended starting point; MKV and unknown containers are rejected. Codec support inside a supported container still depends on the receiver.

Avoid guest Wi-Fi or access-point isolation: the receiver must be able to reach the phone's temporary LAN stream. No media is uploaded to an ABM server.

## Planned milestones

1. **Foundation:** app shell, local playback, lifecycle handling and visual identity.
2. **Library (in progress):** broader device media indexing, artwork, M3U import/export and persistent queue restoration. Current release supports SAF-imported files, Room library records, basic playlists, playback history and Media3 shuffle/repeat.
3. **Playback tools:** initial advanced controls implemented; Additional ReplayGain container/tag coverage, subtitle timing/offset controls and DSP/device performance testing remain.
4. **Casting (initial sender integration):** Google Cast discovery, device picker, session lifecycle, remote play/pause/seek, multi-item queue transfer, live receiver-to-sender title/queue-position synchronisation and LAN streaming for imported files. Shuffle/repeat and approximate reconnect-position recovery are implemented; richer queue editing, reconnect recovery tests and broad codec/device validation remain.
5. **Streaming Centre (initial Android milestone implemented):** separate WebView, six official provider launch cards, persistent cookie sessions, browser navigation, HTML5 fullscreen, external-browser handoff and direct HTTPS video URL Cast. Integrated DLNA/UPnP + DIAL discovery, YouTube DIAL handoff and broad provider/DRM device validation remain.
6. **Hardening:** background playback, notification/lock-screen controls, Android Auto consideration, accessibility, Cast lifecycle/format tests (including OEM battery-management behaviour), signed release and in-app updates.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Windows-to-Android feature parity](docs/FEATURE_PARITY.md)

## Licensing

See [LICENSE](LICENSE). The Windows product is separately documented in its own repository. Do not assume third-party codec, Cast or native-engine dependencies have the same licensing terms as this application.
