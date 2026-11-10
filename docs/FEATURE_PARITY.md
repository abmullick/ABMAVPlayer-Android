# Windows-to-Android feature parity

The Windows reference is documented in [ABMAVPlayer](https://github.com/abmullick/ABMAVPlayer). This matrix distinguishes target parity from implemented functionality.

| Windows capability | Android implementation strategy | Status |
|---|---|---|
| Local audio/video playback | Media3 first; optional FFmpeg/libmpv fallback after validation | Implemented (Media3/platform codecs) |
| Media library import and browsing | Storage Access Framework + Room | Implemented for user-imported files; automatic full-device indexing remains planned |
| Playlist creation and membership | Room + Compose library UI | Basic implementation; M3U import/export and richer editing remain planned |
| M3U playlist load/save | Kotlin parser/exporter + SAF document access | Planned |
| Queue add/remove/reorder | Media3 queue; Room-backed queue persistence to follow | Partial: Media3 queue playback |
| Shuffle and repeat | Media3 Player repeat/shuffle modes | Implemented in player UI |
| Recent playback history | Room | Partial: last-played timestamps persisted |
| Playback speed 0.5x–2.0x | Media3 PlaybackParameters | Planned |
| Full-screen video | Compose/player orientation and immersive mode | Planned |
| Audio track selection | Media3 TrackSelectionParameters | Planned |
| Subtitle track selection | Media3 text tracks | Planned |
| External subtitle files | SAF + MediaItem subtitle configuration | Planned |
| 10-band EQ and presets | Android audio session effects or app-owned DSP; device support varies | Planned |
| ReplayGain / compressor | App-owned DSP or verified audio-processing extension | Planned |
| Animated audio visualizer | Audio-session/PCM analysis where legally and technically supported | Planned |
| Google Cast | Google Cast SDK sender and remote media client | Planned |
| Windows network receiver discovery | Android Cast discovery; do not promise arbitrary DLNA/Miracast parity | Platform-specific |
| Streaming Centre provider cards | Android WebView and provider official apps/sites | Planned |
| Streaming Centre browser controls | Android WebView navigation, reload, fullscreen and open externally | Planned |
| YouTube/provider TV handoff | Official Cast SDK/provider-supported routes | Planned |
| In-app update checker | GitHub Releases API + SHA-256 verification + Android package installer flow | Planned |

## Important parity notes

- Android codec support varies by OS version, chipset and vendor. A file that plays through VLC on Windows is not automatically guaranteed to play on every Android phone.
- Android does not expose the same global audio-loopback path as the Windows visualizer. A visualizer should analyse audio rendered by this app, not claim to capture other apps.
- Android's system audio effects are device-dependent. A reliable 10-band equalizer may require an app-owned DSP implementation.
- Google Cast SDK is the appropriate Android sender path. It is not a drop-in replacement for Windows LibVLC renderer discovery or the Windows DLNA/DIAL implementation.
- Protected streaming content is subject to provider DRM and device certification. The app must not attempt DRM circumvention.
- Windows keyboard shortcuts and desktop drag-and-drop need touch-first equivalents on phones, with keyboard/mouse support on tablets and ChromeOS where practical.

A feature is considered complete only after implementation, automated tests where possible, and manual validation on representative physical Android devices.
