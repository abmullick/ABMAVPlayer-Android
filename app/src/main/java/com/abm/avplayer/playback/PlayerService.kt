package com.abm.avplayer.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.abm.avplayer.audio.TenBandEqSettings
import com.abm.avplayer.audio.TenBandEqualizerAudioProcessor

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
                    .setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            }
        }
        val exoPlayer = ExoPlayer.Builder(this, renderersFactory).build()
        player = exoPlayer
        val sessionId = exoPlayer.audioSessionId
        if (sessionId > 0) {
            getSharedPreferences("abm_playback", MODE_PRIVATE)
                .edit()
                .putInt("audio_session_id", sessionId)
                .apply()
        }
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
