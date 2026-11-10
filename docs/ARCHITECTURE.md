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

Use Google Cast SDK's supported sender APIs and Cast session lifecycle. The initial sender uses Google's Default Media Receiver, a native MediaRouter route button, Cast session callbacks and a remote media client. Direct HTTP(S) URLs are loaded directly. User-selected SAF documents are served through an ephemeral, tokenized HTTP server bound to the phone's LAN interface; the server supports GET/HEAD and byte-range reads and is owned by `CastStreamService`, not the Activity. The receiver must be able to reach the phone over the same LAN. Library and saved-playlist playback can transfer a multi-item queue using Cast queue items, preserving the selected start position and providing remote previous/next navigation. The sender polls `MediaStatus` and resolves `currentItemId` against the receiver's live `queueItems`; the receiver's current item metadata drives the displayed title and the sender shows the live one-based queue position/total. This also means next/previous changes initiated on the TV are reflected by the phone rather than leaving the sender on its previous title. Local SAF queue entries are exposed through a single temporary LAN server with tokenized per-item endpoints and byte-range reads; direct HTTP(S) items retain their remote URLs. The phone must remain reachable while local items play. Shuffle reorders upcoming queue items without moving the currently playing item and restores the original upcoming order when toggled off; repeat-off/all/one uses the receiver queue repeat mode. Remote track/subtitle selection, background-server hardening and device/codec validation remain. Do not emulate Windows VLC renderer discovery or claim that generic DLNA, Miracast and Google Cast are interchangeable.

## Streaming Centre

Provider metadata contains official app package allowlists for YouTube (including the Android TV package), Netflix (including the Android TV package), Prime Video (mobile and TV package variants), JioHotstar, Sony LIV and ZEE5. The Compose launcher exposes separate WEB and APP actions. APP attempts an explicit HTTPS VIEW intent scoped to those provider packages; if no supported app activity resolves, it falls back to the provider website inside the ABM WebView. Provider `intent://` redirects are parsed but only launched when their package is on this allowlist, with an HTTPS-only validated browser fallback. Play Store `market://` handoff is likewise restricted to the same package allowlist. This is an app-launch integration, not a claim that ABM can control another provider's protected playback session. The provider's own app remains responsible for authentication, DRM and its own Cast sender.

`StreamingCentreActivity` hosts a dedicated Android WebView and Compose service launcher, separate from the local Media3 player and its queue. It provides official launch cards for YouTube, Netflix, Prime Video, JioHotstar, Sony LIV and ZEE5; browser history controls; HTML5 fullscreen; and external-browser handoff. Android WebView's app-private cookie store retains supported provider sessions between launches. JavaScript and DOM storage are enabled for modern provider pages, third-party cookies are accepted for embedded authentication flows, mixed content and file/content access are disabled, SSL errors are cancelled, and navigation is restricted to HTTPS destinations while local/private hosts and clear-text HTTP are blocked. The Cast route button uses the Android Cast SDK; the sender can hand off only a directly addressable HTTPS HTML5 video URL. This is not tab mirroring and does not extract blob/MSE, DRM, encrypted or segmented streams. Provider authentication remains with the provider. Do not intercept passwords or bypass DRM. `StreamingReceiverDiscovery` sends SSDP M-SEARCH requests for the UPnP MediaRenderer and DIAL service types while holding a Wi-Fi multicast lock. It fetches device descriptions, extracts friendly names, AVTransport control endpoints and DIAL Application-URL, and merges protocol capabilities for the same receiver. DLNA handoff sends SOAP `SetAVTransportURI` followed by `Play`; YouTube DIAL starts the TV's YouTube app, reads its `screenId`, obtains a YouTube Lounge token, binds a remote-control session and sends the selected video through the Lounge `setPlaylist` command. Subsequent app-specific Play/Pause/Stop commands use that paired session. The Lounge API is undocumented and may change; no provider credentials or DRM media segments are transferred. The app's native HTTP control client rejects non-private/non-literal IPv4 destinations and refuses redirects. The WebView itself remains HTTPS-only; cleartext is enabled at the application network policy only to support the local-device control protocols, and the app's receiver client constrains those calls to private LAN addresses. Google Cast remains the official Cast SDK route picker. The Streaming Centre also polls an active Cast session's media status and exposes remote Play/Pause, Stop and relative seek controls with title and position. The activity polls DLNA `GetTransportInfo` and `GetPositionInfo` (position is optional because some renderers reject it) or the DIAL YouTube application status endpoint. It distinguishes network connection from app/playback state, provides manual reconnect, and periodically re-runs SSDP discovery after status failures to refresh the selected receiver endpoint. DLNA controls include Play/Resume, Pause, Stop and relative-time seek. DIAL itself provides app launch and application status, not generic media pause/resume. For YouTube specifically, the app pairs a YouTube Lounge remote session after DIAL launch and sends app-specific Play/Pause/Stop commands. These endpoints are undocumented and can change; other providers must be controlled by their own official apps. A DIAL status HTTP 404 is treated as a reachable TV with the app stopped/not installed, not as a disconnected TV. This is a first implementation; SSDP behaviour, device-specific SOAP/DIAL variations and playback must be validated against physical TVs.

## Security and privacy

- Use the Storage Access Framework and persist only user-granted URI permissions.
- Avoid broad storage permissions when scoped document access is sufficient.
- Use HTTPS for network media by default.
- Never collect provider credentials.
- Do not store cookies outside the WebView profile or expose them to casting code.


## Background Cast streaming lifecycle

- Imported media and sidecar subtitle HTTP endpoints are owned by `CastStreamService`, a dedicated `mediaPlayback` foreground service. They are no longer closed by `MainActivity.onDestroy()`, so screen changes and Activity teardown do not immediately interrupt a receiver's stream.
- The service displays an ongoing Cast notification and acquires a partial CPU wake lock plus a high-performance Wi-Fi lock only while a local LAN endpoint is active. These locks are released when the endpoint is cleared or the Cast session ends; continuous casting therefore has a battery cost.
- The service is non-sticky. If Android terminates the app process, the in-memory HTTP server and its random endpoint are lost, so the current receiver stream may stop. A private recovery snapshot stores the selected local source or supported queue metadata; when the Cast SDK resumes the session and the app reconnects, it checks whether the old endpoint still exists and rebuilds/reloads the source or queue if it does not. Recovery requires persisted SAF URI read permission. The sender checkpoints remote playback position periodically and restores the selected item/queue position when rebuilding a lost endpoint; the checkpoint is approximate (up to roughly two seconds behind) and recovery is not guaranteed if the receiver has already abandoned the queue. Device-vendor battery policies and physical screen-off playback still require testing.
