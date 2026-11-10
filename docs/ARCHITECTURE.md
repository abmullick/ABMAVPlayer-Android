# Android architecture

## Principles

1. Native Android UX and platform integration; do not port Win32 or Direct2D code.
2. Keep playback state independent of Compose screens so playback survives navigation and activity recreation.
3. Keep local playback, Google Cast sessions and Streaming Centre browser state as separate concerns.
4. Persist user-owned library metadata and playlists locally.
5. Do not claim a codec is supported until verified on real devices and documented test media.

## Target modules

- `:app`: application entry point, navigation and dependency wiring.
- `:core:design`: ABM colors, typography, components, motion and responsive layouts.
- `:core:media`: Media3 player abstraction, queue, track selection and playback state.
- `:core:data`: Room entities/DAOs, M3U parser/exporter, history and DataStore preferences.
- `:feature:library`: device media browsing, search, playlists and recent media.
- `:feature:player`: audio/video player, transport controls, subtitles, speed and equalizer UI.
- `:feature:casting`: Google Cast discovery, route selection, session lifecycle and remote state.
- `:feature:streaming`: isolated WebView experience and provider launch/navigation.
- `:feature:settings`: appearance, playback, network and about/help settings.

The initial project keeps the app in one Gradle module for straightforward Android Studio import. Room currently stores imported media references, playlists and playlist membership. The media library uses Storage Access Framework URIs; it does not silently scan the entire device.

## Playback

Media3/ExoPlayer is the initial playback engine. A production release should move the player into a `MediaSessionService`, expose a `MediaController` to the UI, and implement notification/lock-screen transport controls. Audio/video format support depends on Android platform decoders and the Media3 extension set.

Playback is hosted by `PlayerService`, a `MediaSessionService`; the Compose activity connects through a `MediaController`. This keeps the player and system media session outside the screen lifecycle. The current queue is created from the imported library when a track is selected; queue restoration and full queue editing remain future work. An optional FFmpeg or libmpv engine may be added for formats that need software decoding or advanced subtitle handling. Before selecting or bundling a native engine, verify Android ABI coverage, binary size, maintenance status and all LGPL/GPL/other licensing obligations.

## Casting

Use Google Cast SDK's supported sender APIs and Cast session lifecycle. The initial sender uses Google's Default Media Receiver, a native MediaRouter route button, Cast session callbacks and a remote media client. Direct HTTP(S) URLs are loaded directly. User-selected SAF documents are served through an ephemeral, tokenized HTTP server bound to the phone's LAN interface; the server supports GET/HEAD and byte-range reads and is stopped when the Cast session ends or the activity is destroyed. The receiver must be able to reach the phone over the same LAN. The initial implementation sends one item at a time. The initial implementation casts one item and supports remote play/pause/seek; queue transfer, remote track/subtitle selection, background-server hardening and device/codec validation remain. Do not emulate Windows VLC renderer discovery or claim that generic DLNA, Miracast and Google Cast are interchangeable.

## Streaming Centre

Use a dedicated Android WebView with provider navigation, safe browsing controls and external-app handoff. Provider authentication remains with the provider. Do not intercept passwords, bypass DRM, or promise that protected browser playback can be transferred to another device. Use official Cast SDK routes or provider-supported intents when available.

## Security and privacy

- Use the Storage Access Framework and persist only user-granted URI permissions.
- Avoid broad storage permissions when scoped document access is sufficient.
- Use HTTPS for network media by default.
- Never collect provider credentials.
- Do not store cookies outside the WebView profile or expose them to casting code.
