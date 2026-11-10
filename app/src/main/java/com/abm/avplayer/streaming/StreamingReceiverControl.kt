package com.abm.avplayer.streaming

import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result as GoogleApiResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Protocol-neutral control contract used by the Streaming Centre.
 *
 * DIAL is deliberately represented as app lifecycle capability only. DIAL itself
 * does not define universal media pause/resume/seek commands.
 */
data class ReceiverControlCapabilities(
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canStopPlayback: Boolean = false,
    val canSeek: Boolean = false,
    val canLaunchApps: Boolean = false,
    val canStopApps: Boolean = false,
    val canReportPlaybackState: Boolean = false
)

data class ReceiverPlaybackSnapshot(
    val connectionState: String,
    val playbackState: String,
    val positionSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val title: String? = null
)

sealed class ReceiverControlCommand {
    object Play : ReceiverControlCommand()
    object Pause : ReceiverControlCommand()
    object StopPlayback : ReceiverControlCommand()
    data class SeekTo(val positionSeconds: Double) : ReceiverControlCommand()
    data class LaunchApp(val applicationName: String, val launchData: String = "") : ReceiverControlCommand()
    data class StopApp(val applicationName: String) : ReceiverControlCommand()
}

interface StreamingReceiverControl {
    val capabilities: ReceiverControlCapabilities
    suspend fun getStatus(): Result<ReceiverPlaybackSnapshot>
    suspend fun execute(command: ReceiverControlCommand): Result<Unit>
}

class DlnaReceiverControl(private val receiver: StreamingReceiver) : StreamingReceiverControl {
    override val capabilities = ReceiverControlCapabilities(
        canPlay = true, canPause = true, canStopPlayback = true, canSeek = true, canReportPlaybackState = true
    )

    override suspend fun getStatus(): Result<ReceiverPlaybackSnapshot> =
        StreamingReceiverDiscovery.getDlnaPlaybackStatus(receiver).map { status ->
            ReceiverPlaybackSnapshot(
                connectionState = "Connected",
                playbackState = status.state,
                positionSeconds = status.position.toSecondsOrNull(),
                durationSeconds = status.duration.toSecondsOrNull()
            )
        }

    override suspend fun execute(command: ReceiverControlCommand): Result<Unit> = when (command) {
        ReceiverControlCommand.Play -> StreamingReceiverDiscovery.controlDlna(receiver, "Play")
        ReceiverControlCommand.Pause -> StreamingReceiverDiscovery.controlDlna(receiver, "Pause")
        ReceiverControlCommand.StopPlayback -> StreamingReceiverDiscovery.controlDlna(receiver, "Stop")
        is ReceiverControlCommand.SeekTo -> {
            if (!command.positionSeconds.isFinite() || command.positionSeconds < 0.0) {
                Result.failure(IllegalArgumentException("Invalid DLNA seek position."))
            } else StreamingReceiverDiscovery.seekDlna(receiver, command.positionSeconds.toDlnaTime())
        }
        is ReceiverControlCommand.LaunchApp, is ReceiverControlCommand.StopApp ->
            Result.failure(UnsupportedOperationException("DLNA does not launch DIAL applications."))
    }
}

class YoutubeLoungeReceiverControl(private val receiver: StreamingReceiver) : StreamingReceiverControl {
    override val capabilities = ReceiverControlCapabilities(
        canPlay = true, canPause = true, canStopPlayback = true, canSeek = true, canReportPlaybackState = true
    )

    override suspend fun getStatus(): Result<ReceiverPlaybackSnapshot> =
        StreamingReceiverDiscovery.getYoutubeLoungePlaybackStatus(receiver).map { status ->
            ReceiverPlaybackSnapshot(
                connectionState = if (status.state == "DISCONNECTED") "Disconnected" else "Connected",
                playbackState = status.state,
                positionSeconds = status.currentTimeSeconds,
                durationSeconds = status.durationSeconds,
                title = status.videoId
            )
        }

    override suspend fun execute(command: ReceiverControlCommand): Result<Unit> = when (command) {
        ReceiverControlCommand.Play -> StreamingReceiverDiscovery.controlYoutubeLounge(receiver, "play")
        ReceiverControlCommand.Pause -> StreamingReceiverDiscovery.controlYoutubeLounge(receiver, "pause")
        ReceiverControlCommand.StopPlayback -> StreamingReceiverDiscovery.controlYoutubeLounge(receiver, "stop")
        is ReceiverControlCommand.SeekTo -> StreamingReceiverDiscovery.seekYoutubeLounge(receiver, command.positionSeconds)
        is ReceiverControlCommand.LaunchApp -> Result.failure(UnsupportedOperationException("Launch YouTube through the DIAL application controller."))
        is ReceiverControlCommand.StopApp -> StreamingReceiverDiscovery.stopDialApplication(receiver, command.applicationName)
    }
}

class DialApplicationReceiverControl(
    private val receiver: StreamingReceiver,
    private val applicationName: String
) : StreamingReceiverControl {
    override val capabilities = ReceiverControlCapabilities(canLaunchApps = true, canStopApps = true)

    override suspend fun getStatus(): Result<ReceiverPlaybackSnapshot> =
        StreamingReceiverDiscovery.getDialApplicationStatus(receiver, applicationName).map { status ->
            ReceiverPlaybackSnapshot("Connected", status.state, title = status.detail)
        }

    override suspend fun execute(command: ReceiverControlCommand): Result<Unit> = when (command) {
        is ReceiverControlCommand.LaunchApp ->
            StreamingReceiverDiscovery.launchDialApplication(receiver, applicationName, command.launchData)
        is ReceiverControlCommand.StopApp ->
            StreamingReceiverDiscovery.stopDialApplication(receiver, applicationName)
        else -> Result.failure(UnsupportedOperationException(
            "DIAL controls app lifecycle only; use the app's remote protocol for media transport controls."
        ))
    }
}

class GoogleCastReceiverControl(private val remote: RemoteMediaClient) : StreamingReceiverControl {
    override val capabilities = ReceiverControlCapabilities(
        canPlay = true, canPause = true, canStopPlayback = true, canSeek = true, canReportPlaybackState = true
    )

    override suspend fun getStatus(): Result<ReceiverPlaybackSnapshot> = runCatching {
        val status = remote.mediaStatus
        val state = when (status?.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
            MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
            MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
            MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
            else -> "UNKNOWN"
        }
        ReceiverPlaybackSnapshot(
            connectionState = "Connected",
            playbackState = state,
            positionSeconds = remote.approximateStreamPosition.coerceAtLeast(0L) / 1000.0,
            durationSeconds = remote.streamDuration.coerceAtLeast(0L) / 1000.0,
            title = remote.mediaInfo?.metadata?.getString("com.google.android.gms.cast.metadata.TITLE")
        )
    }

    override suspend fun execute(command: ReceiverControlCommand): Result<Unit> = when (command) {
        ReceiverControlCommand.Play -> await(remote.play())
        ReceiverControlCommand.Pause -> await(remote.pause())
        ReceiverControlCommand.StopPlayback -> await(remote.stop())
        is ReceiverControlCommand.SeekTo -> {
            if (!command.positionSeconds.isFinite() || command.positionSeconds < 0.0) {
                Result.failure(IllegalArgumentException("Invalid Google Cast seek position."))
            } else await(remote.seek(
                MediaSeekOptions.Builder().setPosition((command.positionSeconds * 1000.0).toLong()).build()
            ))
        }
        is ReceiverControlCommand.LaunchApp, is ReceiverControlCommand.StopApp ->
            Result.failure(UnsupportedOperationException("Use DIAL for TV app lifecycle operations."))
    }

    private suspend fun <T : GoogleApiResult> await(pending: PendingResult<T>): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            pending.setResultCallback { result ->
                if (continuation.isActive) {
                    val outcome = if (result.status.isSuccess) Result.success(Unit)
                    else Result.failure(IllegalStateException("Google Cast command failed (status ${result.status.statusCode})."))
                    continuation.resume(outcome)
                }
            }
        }
}

private fun String.toSecondsOrNull(): Double? {
    val parts = split(":")
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toLongOrNull() ?: return null
    return (hours * 3600L + minutes * 60L + seconds).toDouble()
}

private fun Double.toDlnaTime(): String {
    val total = toLong()
    return "%02d:%02d:%02d".format(total / 3600L, (total % 3600L) / 60L, total % 60L)
}
