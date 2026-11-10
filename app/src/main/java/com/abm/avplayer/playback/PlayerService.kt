package com.abm.avplayer.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.abm.avplayer.audio.TenBandEqSettings
import com.abm.avplayer.audio.ReplayGainMetadataParser
import com.abm.avplayer.audio.TenBandEqualizerAudioProcessor

@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        TenBandEqSettings.load(this)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                val processor = TenBandEqualizerAudioProcessor()
                return DefaultAudioSink.Builder(context)
                    // Keep the integer PCM path so Media3's time-stretch processor remains available.
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            }
        }
        val exoPlayer = ExoPlayer.Builder(this, renderersFactory).build()
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                TenBandEqSettings.resetReplayGainForTrack()
            }

            override fun onMetadata(metadata: Metadata) {
                TenBandEqSettings.updateReplayGain(ReplayGainMetadataParser.parse(metadata))
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_AUDIO) continue
                    for (trackIndex in 0 until group.length) {
                        group.getTrackFormat(trackIndex).metadata?.let { metadata ->
                            TenBandEqSettings.updateReplayGain(ReplayGainMetadataParser.parse(metadata))
                        }
                    }
                }
            }
        })
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
