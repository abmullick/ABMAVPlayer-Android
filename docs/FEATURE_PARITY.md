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
| Playback speed 0.5x–2.0x | Media3 PlaybackParameters | Implemented: 0.5x, 0.75x, 1x, 1.25x, 1.5x, 1.75x and 2x |
| Full-screen video | Compose player surface + immersive system bars | Implemented: immersive fullscreen layout; automatic rotation remains device/orientation-policy dependent |
| Audio track selection | Media3 TrackSelectionParameters | Implemented for selectable tracks exposed by the decoder |
| Subtitle track selection | Media3 text tracks | Implemented for selectable embedded tracks, including Off |
| External subtitle files | SAF + MediaItem subtitle configuration | Implemented for SRT, WebVTT and SSA/ASS sidecar files; device/decoder parser support applies |
| 10-band EQ and presets | App-owned Media3 PCM AudioProcessor with ten peaking biquad filters | Implemented: 31 Hz–16 kHz bands, ±12 dB, persistent settings and seven presets. Applies to decoded PCM; encoded passthrough is not processed |
| Preamp / EQ headroom | App-owned PCM DSP | Implemented: persistent ±12 dB preamp and automatic headroom offset based on largest positive EQ-band gain |
| Dynamic-range compressor | App-owned PCM DSP | Initial implementation: adjustable threshold, ratio, attack/release and make-up gain; requires listening and device validation |
| ReplayGain loudness normalisation | Parse track/album ReplayGain metadata and apply gain in PCM pipeline | Planned; metadata parsing and album-vs-track policy are not implemented |
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
- The Android EQ, preamp and initial compressor are app-owned DSP stages in Media3's decoded PCM pipeline. Encoded passthrough cannot be altered by PCM processors; codec/device validation remains necessary. The compressor is an initial peak-level implementation, not a mastering-grade loudness processor.
- Google Cast SDK is the appropriate Android sender path. It is not a drop-in replacement for Windows LibVLC renderer discovery or the Windows DLNA/DIAL implementation.
- Protected streaming content is subject to provider DRM and device certification. The app must not attempt DRM circumvention.
- Windows keyboard shortcuts and desktop drag-and-drop need touch-first equivalents on phones, with keyboard/mouse support on tablets and ChromeOS where practical.

A feature is considered complete only after implementation, automated tests where possible, and manual validation on representative physical Android devices.
