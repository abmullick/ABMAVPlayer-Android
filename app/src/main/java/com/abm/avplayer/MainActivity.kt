package com.abm.avplayer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.mediarouter.app.MediaRouteButton
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.abm.avplayer.casting.CastStreamService
import com.abm.avplayer.casting.LocalCastMediaSource
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaSeekOptions
import com.abm.avplayer.data.MediaDatabase
import com.abm.avplayer.data.MediaFileEntity
import com.abm.avplayer.data.MediaLibraryRepository
import com.abm.avplayer.data.PlaylistEntity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

val Canvas = Color(0xFF090B12)
val Panel = Color(0xFF141925)
val Raised = Color(0xFF1B2332)
val Cyan = Color(0xFF54D8E8)
val Violet = Color(0xFF9A7BFF)
val Muted = Color(0xFF929BAE)

class MainActivity : ComponentActivity() {
    private var castContext: CastContext? = null
    private var castListener: SessionManagerListener<CastSession>? = null
    private var castStreamService: CastStreamService? = null
    private var castStreamBound = false
    private val pendingCastStreamCallbacks = mutableListOf<(CastStreamService) -> Unit>()
    private val castStreamConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? CastStreamService.LocalBinder)?.service() ?: return
            castStreamService = service
            val callbacks = pendingCastStreamCallbacks.toList()
            pendingCastStreamCallbacks.clear()
            callbacks.forEach { callback -> runCatching { callback(service) }.onFailure {
                castMessageState.value = "Could not prepare Cast streaming: ${it.message}"
            } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            castStreamService = null
            castStreamBound = false
            castMessageState.value = "Cast stream service disconnected; reconnect or select the media again"
        }
    }
    private val castConnectedState = mutableStateOf(false)
    private val castDeviceNameState = mutableStateOf("")
    private val castMessageState = mutableStateOf("")
    private val castShuffleState = mutableStateOf(false)
    private val castRepeatModeState = mutableIntStateOf(Player.REPEAT_MODE_OFF)
    private val castSubtitleAvailableState = mutableStateOf(false)
    private val castSubtitleEnabledState = mutableStateOf(false)
    private val castQueueSummaryState = mutableStateOf("")
    private var castOriginalFutureOrder: List<Int>? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupCast()
        val token = SessionToken(this, ComponentName(this, com.abm.avplayer.playback.PlayerService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.let { future ->
            future.addListener({
                runCatching { future.get() }.onSuccess { controllerState.value = it }
            }, mainExecutor)
        }

        val repository = MediaLibraryRepository(
            MediaDatabase.get(applicationContext).mediaDao(), contentResolver
        )

        setContent {
            val scope = rememberCoroutineScope()
            val controller by controllerState
            val library by repository.library.collectAsState(initial = emptyList())
            val playlists by repository.playlists.collectAsState(initial = emptyList())
            var page by remember { mutableStateOf("player") }
            var playlistDialog by remember { mutableStateOf(false) }
            var playlistName by remember { mutableStateOf("") }
            var position by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var progress by remember { mutableFloatStateOf(0f) }
            var playing by remember { mutableStateOf(false) }
            var title by remember { mutableStateOf("Nothing playing") }
            var fullscreen by remember { mutableStateOf(false) }
            val castConnected by castConnectedState
            val castDeviceName by castDeviceNameState
            val castMessage by castMessageState
            val castShuffleEnabled by castShuffleState
            val castRepeatMode by castRepeatModeState
            val castSubtitleAvailable by castSubtitleAvailableState
            val castSubtitleEnabled by castSubtitleEnabledState
            val castQueueSummary by castQueueSummaryState

            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    uris.forEach { uri ->
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                    }
                    scope.launch {
                        repository.importUris(uris)
                        page = "library"
                    }
                }
            }

            val subtitlePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let { subtitleUri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            subtitleUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val active = controller
                    val current = active?.currentMediaItem
                    val local = current?.localConfiguration
                    if (active != null && current != null && local != null) {
                        val mime = when {
                            subtitleUri.toString().substringBefore("?").endsWith(".vtt", true) ||
                                contentResolver.getType(subtitleUri) == "text/vtt" -> MimeTypes.TEXT_VTT
                            subtitleUri.toString().substringBefore("?").endsWith(".ssa", true) ||
                                subtitleUri.toString().substringBefore("?").endsWith(".ass", true) -> MimeTypes.TEXT_SSA
                            else -> MimeTypes.APPLICATION_SUBRIP
                        }
                        val subtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                            .setMimeType(mime)
                            .setLanguage("und")
                            .setLabel(subtitleUri.lastPathSegment ?: "External subtitle")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                        val updated = current.buildUpon()
                            .setSubtitleConfigurations(local.subtitleConfigurations + subtitle)
                            .build()
                        val index = active.currentMediaItemIndex
                        val resumePosition = active.currentPosition
                        val wasPlaying = active.playWhenReady
                        active.replaceMediaItem(index, updated)
                        active.seekTo(index, resumePosition)
                        active.playWhenReady = wasPlaying
                    }
                }
            }

            LaunchedEffect(controller) {
                val active = controller ?: return@LaunchedEffect
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        title = mediaItem?.mediaMetadata?.title?.toString()
                            ?: mediaItem?.localConfiguration?.uri?.lastPathSegment
                            ?: "Nothing playing"
                        mediaItem?.localConfiguration?.uri?.toString()?.let { uri ->
                            scope.launch { repository.recordPlayed(uri) }
                        }
                    }
                }
                active.addListener(listener)
                var lastCastRecoveryCheckpointAt = 0L
                try {
                    while (true) {
                        val remote = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
                        if (remote != null) {
                            val status = remote.mediaStatus
                            val queueItems = status?.queueItems.orEmpty()
                            val currentQueueIndex = queueItems.indexOfFirst { it.itemId == status?.currentItemId }
                            val currentQueueItem = queueItems.getOrNull(currentQueueIndex)
                            val queueTitle = currentQueueItem?.media?.metadata
                                ?.getString(CastMediaMetadata.KEY_TITLE)
                            val liveTitle = queueTitle
                                ?: remote.mediaInfo?.metadata?.getString(CastMediaMetadata.KEY_TITLE)
                            if (!liveTitle.isNullOrBlank()) title = liveTitle
                            castQueueSummaryState.value = if (queueItems.size > 1 && currentQueueIndex >= 0) {
                                "QUEUE  ·  ${currentQueueIndex + 1} / ${queueItems.size}"
                            } else ""
                            playing = status?.playerState == MediaStatus.PLAYER_STATE_PLAYING
                            castRepeatModeState.intValue = when (status?.queueRepeatMode) {
                                MediaStatus.REPEAT_MODE_REPEAT_ALL -> Player.REPEAT_MODE_ALL
                                MediaStatus.REPEAT_MODE_REPEAT_SINGLE -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            val castTracks = remote.mediaInfo?.mediaTracks.orEmpty()
                            castSubtitleAvailableState.value = castTracks.any { it.type == MediaTrack.TYPE_TEXT }
                            castSubtitleEnabledState.value = status?.activeTrackIds?.contains(1L) == true
                            position = remote.approximateStreamPosition.coerceAtLeast(0L)
                            duration = remote.streamDuration.coerceAtLeast(0L)
                            val now = System.currentTimeMillis()
                            if (now - lastCastRecoveryCheckpointAt >= 2_000L) {
                                updateCastRecoveryProgress(
                                    position,
                                    if (!castShuffleState.value && currentQueueIndex >= 0) currentQueueIndex else null
                                )
                                lastCastRecoveryCheckpointAt = now
                            }
                            if (duration > 0L) progress = (position.toFloat() / duration).coerceIn(0f, 1f)
                        } else {
                            position = active.currentPosition.coerceAtLeast(0L)
                            duration = active.duration.coerceAtLeast(0L)
                            playing = active.isPlaying
                            if (duration > 0L) progress = (position.toFloat() / duration).coerceIn(0f, 1f)
                        }
                        delay(500)
                    }
                } finally {
                    active.removeListener(listener)
                }
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Canvas) {
                    if (page == "library") {
                        LibraryScreen(
                            media = library,
                            playlists = playlists,
                            onBack = { page = "player" },
                            onImport = { picker.launch(arrayOf("audio/*", "video/*")) },
                            onPlay = { selected ->
                                if (castContext?.sessionManager?.currentCastSession != null) {
                                    castLibraryQueue(library, selected)
                                    page = "player"
                                } else controller?.let { player ->
                                    val queue = library.map(::toMediaItem)
                                    val index = library.indexOfFirst { it.uri == selected.uri }.coerceAtLeast(0)
                                    player.setMediaItems(queue, index, 0L)
                                    player.prepare()
                                    player.play()
                                    page = "player"
                                }
                            },
                            onRemove = { item -> scope.launch { repository.remove(item.uri) } },
                            onCreatePlaylist = { playlistDialog = true },
                            onPlayPlaylist = { playlist ->
                                scope.launch {
                                    val uris = repository.playlistUris(playlist.id).toSet()
                                    val tracks = uris.mapNotNull { uri -> library.firstOrNull { it.uri == uri } }
                                    if (tracks.isNotEmpty()) {
                                        if (castContext?.sessionManager?.currentCastSession != null) {
                                            castLibraryQueue(tracks, tracks.first())
                                        } else {
                                            controller?.setMediaItems(tracks.map(::toMediaItem))
                                            controller?.prepare()
                                            controller?.play()
                                        }
                                        page = "player"
                                    }
                                }
                            },
                            onAddToPlaylist = { playlist, item ->
                                scope.launch { repository.addToPlaylist(playlist.id, item.uri) }
                            },
                            onDeletePlaylist = { playlist ->
                                scope.launch { repository.deletePlaylist(playlist.id) }
                            }
                        )
                    } else {
                        PlayerScreen(
                            title = title, player = controller,
                            castConnected = castConnected, castDeviceName = castDeviceName, castMessage = castMessage,
                            castQueueSummary = castQueueSummary,
                            hasVideo = controller?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_VIDEO && it.isSupported } == true,
                            playing = playing, position = position, duration = duration,
                            castShuffleEnabled = castShuffleEnabled, castRepeatMode = castRepeatMode,
                            castSubtitleAvailable = castSubtitleAvailable, castSubtitleEnabled = castSubtitleEnabled,
                            onCastSubtitleToggle = {
                                val remote = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
                                if (remote != null && castSubtitleAvailableState.value) {
                                    val ids = if (castSubtitleEnabledState.value) longArrayOf() else longArrayOf(1L)
                                    remote.setActiveMediaTracks(ids).setResultCallback { result ->
                                        castMessageState.value = if (result.status.isSuccess) {
                                            if (ids.isEmpty()) "Cast subtitles turned off" else "Cast subtitles enabled"
                                        } else "TV could not change subtitles (error ${result.status.statusCode})"
                                    }
                                }
                            },
                            progress = progress,
                            onBrowse = { picker.launch(arrayOf("audio/*", "video/*")) },
                            onLibrary = { page = "library" },
                            onPlayPause = {
                                val remote = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
                                if (remote != null) {
                                    if (remote.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING) remote.pause()
                                    else remote.play()
                                } else controller?.let { if (it.isPlaying) it.pause() else it.play() }
                            },
                            onPrevious = { if (castConnected) castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.queuePrev(null)
                                else controller?.seekToPreviousMediaItem() },
                            onNext = { if (castConnected) castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.queueNext(null)
                                else controller?.seekToNextMediaItem() },
                            onSeek = { value ->
                                progress = value
                                val remote = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
                                if (remote != null && duration > 0L) remote.seek(MediaSeekOptions.Builder().setPosition((duration * value).toLong()).build())
                                else if (duration > 0L) controller?.seekTo((duration * value).toLong())
                            },
                            onShuffle = {
                                if (castConnected) setCastShuffleEnabled(!castShuffleState.value)
                                else controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                            },
                            onRepeat = {
                                if (castConnected) {
                                    val next = when (castRepeatModeState.intValue) {
                                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                        else -> Player.REPEAT_MODE_OFF
                                    }
                                    setCastRepeatMode(next)
                                } else controller?.let {
                                    it.repeatMode = when (it.repeatMode) {
                                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                        else -> Player.REPEAT_MODE_OFF
                                    }
                                }
                            },
                            onPickSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
                            fullscreen = fullscreen,
                            onFullscreen = {
                                fullscreen = !fullscreen
                                requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                val bars = WindowInsetsControllerCompat(window, window.decorView)
                                if (fullscreen) {
                                    bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                    bars.hide(WindowInsetsCompat.Type.systemBars())
                                } else {
                                    bars.show(WindowInsetsCompat.Type.systemBars())
                                }
                            }
                        )
                    }
                }
            }

            if (playlistDialog) {
                AlertDialog(
                    onDismissRequest = { playlistDialog = false },
                    title = { Text("Create playlist", color = Color.White) },
                    text = {
                        OutlinedTextField(
                            value = playlistName, onValueChange = { playlistName = it },
                            label = { Text("Playlist name") }, singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val name = playlistName.trim()
                            if (name.isNotEmpty()) scope.launch {
                                repository.createPlaylist(name)
                                playlistName = ""
                                playlistDialog = false
                            }
                        }) { Text("Create", color = Cyan) }
                    },
                    dismissButton = {
                        TextButton(onClick = { playlistDialog = false }) { Text("Cancel", color = Muted) }
                    },
                    containerColor = Panel
                )
            }
        }
    }

    private fun withCastStreamService(callback: (CastStreamService) -> Unit) {
        castStreamService?.let { callback(it); return }
        pendingCastStreamCallbacks += callback
        if (castStreamBound) return
        val intent = Intent(this, CastStreamService::class.java)
        runCatching {
            startForegroundService(intent)
            castStreamBound = bindService(intent, castStreamConnection, Context.BIND_AUTO_CREATE)
            if (!castStreamBound) error("Android could not bind the Cast stream service")
        }.onFailure {
            pendingCastStreamCallbacks.clear()
            runCatching { stopService(intent) }
            castMessageState.value = "Could not start background Cast streaming: ${it.message}"
        }
    }

    private fun stopCastStreamService() {
        pendingCastStreamCallbacks.clear()
        castStreamService?.stopStreaming()
        castStreamService = null
        if (castStreamBound) {
            runCatching { unbindService(castStreamConnection) }
            castStreamBound = false
        }
    }

    private fun castRecoveryPreferences() = getSharedPreferences("abm_cast_recovery", Context.MODE_PRIVATE)

    private fun saveCastRecoverySnapshot(items: List<Triple<MediaFileEntity, Uri, String>>, selectedIndex: Int) {
        val hasLocalSource = items.any { it.second.scheme != "http" && it.second.scheme != "https" }
        val prefs = castRecoveryPreferences()
        if (!hasLocalSource) { prefs.edit().remove("snapshot").apply(); return }
        val jsonItems = JSONArray()
        items.forEach { (file, uri, mime) ->
            jsonItems.put(JSONObject().put("uri", uri.toString()).put("title", file.title)
                .put("mime", mime).put("addedAt", file.addedAt))
        }
        val snapshot = JSONObject().put("mode", if (items.size == 1) "single" else "queue")
            .put("selectedIndex", selectedIndex).put("items", jsonItems)
        prefs.edit().putString("snapshot", snapshot.toString()).apply()
    }

    private fun updateCastRecoveryProgress(positionMs: Long, selectedIndex: Int? = null) {
        val raw = castRecoveryPreferences().getString("snapshot", null) ?: return
        runCatching {
            val snapshot = JSONObject(raw)
            snapshot.put("positionMs", positionMs.coerceAtLeast(0L))
            if (selectedIndex != null && snapshot.optString("mode") == "queue") {
                val count = snapshot.optJSONArray("items")?.length() ?: 0
                if (selectedIndex in 0 until count) snapshot.put("selectedIndex", selectedIndex)
            }
            castRecoveryPreferences().edit().putString("snapshot", snapshot.toString()).apply()
        }
    }

    private fun clearCastRecoverySnapshot() {
        castRecoveryPreferences().edit().remove("snapshot").apply()
    }

    private fun recoverCastStreamIfNeeded() {
        val raw = castRecoveryPreferences().getString("snapshot", null) ?: return
        castMessageState.value = "Checking the local stream before resuming…"
        withCastStreamService { service ->
            if (service.hasActiveEndpoint) {
                castMessageState.value = "Reconnected · local stream is still active"
                return@withCastStreamService
            }
            runCatching {
                val snapshot = JSONObject(raw)
                val itemsJson = snapshot.getJSONArray("items")
                val items = (0 until itemsJson.length()).map { index ->
                    val item = itemsJson.getJSONObject(index)
                    val uri = item.getString("uri")
                    val title = item.getString("title")
                    val mime = item.getString("mime")
                    MediaFileEntity(uri, title, mime, item.optLong("addedAt", System.currentTimeMillis())) to Triple(Uri.parse(uri), title, mime)
                }
                require(items.isNotEmpty()) { "No saved Cast items" }
                val resumePositionMs = snapshot.optLong("positionMs", 0L).coerceAtLeast(0L)
                when (snapshot.optString("mode")) {
                    "single" -> {
                        val data = items.first().second
                        castMedia(data.first.toString(), data.second, data.third, resumePositionMs)
                    }
                    else -> {
                        val library = items.map { it.first }
                        val selectedIndex = snapshot.optInt("selectedIndex", 0).coerceIn(library.indices)
                        castLibraryQueue(library, library[selectedIndex], resumePositionMs)
                    }
                }
            }.onFailure {
                clearCastRecoverySnapshot()
                castMessageState.value = "Could not restore the local Cast stream. Please select the media again (" + it.message + ")"
            }
        }
    }
    private fun setupCast() {
        runCatching { CastContext.getSharedInstance(this) }.onSuccess { context ->
            castContext = context
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    castConnectedState.value = true
                    castDeviceNameState.value = session.castDevice?.friendlyName.orEmpty()
                    castMessageState.value = "Connected"
                    val item = controllerState.value?.currentMediaItem
                    val uri = item?.localConfiguration?.uri?.toString()
                    if (uri != null) castMedia(uri, item.mediaMetadata.title?.toString() ?: "ABM AV Player", contentResolver.getType(Uri.parse(uri)))
                    else castMessageState.value = "Connected · select media to cast"
                }
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    castConnectedState.value = true
                    castDeviceNameState.value = session.castDevice?.friendlyName.orEmpty()
                    castMessageState.value = "Reconnected"
                    recoverCastStreamIfNeeded()
                }
                override fun onSessionEnded(session: CastSession, error: Int) {
                    castConnectedState.value = false
                    castDeviceNameState.value = ""
                    castMessageState.value = "Disconnected"
                    castShuffleState.value = false
                    castRepeatModeState.intValue = Player.REPEAT_MODE_OFF
                    castSubtitleAvailableState.value = false
                    castSubtitleEnabledState.value = false
                    castQueueSummaryState.value = ""
                    castOriginalFutureOrder = null
                    clearCastRecoverySnapshot()
                    stopCastStreamService()
                }
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    castMessageState.value = "Could not connect to TV (error $error)"
                }
                override fun onSessionResumeFailed(session: CastSession, error: Int) {
                    castConnectedState.value = false
                    castDeviceNameState.value = ""
                    castMessageState.value = "Could not restore the Cast session (error $error)"
                    clearCastRecoverySnapshot()
                    stopCastStreamService()
                }
                override fun onSessionStarting(session: CastSession) { castMessageState.value = "Connecting…" }
                override fun onSessionEnding(session: CastSession) { castMessageState.value = "Disconnecting…" }
                override fun onSessionResuming(session: CastSession, sessionId: String) { castMessageState.value = "Reconnecting…" }
                override fun onSessionSuspended(session: CastSession, reason: Int) { castMessageState.value = "Connection interrupted" }
            }
            castListener = listener
            context.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
            context.sessionManager.currentCastSession?.let { active ->
                castConnectedState.value = true
                castDeviceNameState.value = active.castDevice?.friendlyName.orEmpty()
                castMessageState.value = "Connected"
            }
        }.onFailure {
            castMessageState.value = "Google Play services / Cast unavailable"
        }
    }

    private fun castLibraryQueue(library: List<MediaFileEntity>, selected: MediaFileEntity, startPositionMs: Long = 0L) {
        val session = castContext?.sessionManager?.currentCastSession ?: return
        val needsLanServer = library.any { file ->
            val scheme = Uri.parse(file.uri).scheme
            scheme != "http" && scheme != "https"
        }
        if (needsLanServer && castStreamService == null) {
            withCastStreamService { castLibraryQueue(library, selected) }
            return
        }
        runCatching {
            val supported = library.mapNotNull { file ->
                val uri = Uri.parse(file.uri)
                val mime = file.mimeType?.takeUnless { it == "application/octet-stream" }
                    ?: contentResolver.getType(uri)?.takeUnless { it == "application/octet-stream" }
                    ?: mimeFromName(uri.lastPathSegment)
                if (mime == "application/octet-stream" || mime == "video/x-matroska") null
                else Triple(file, uri, mime)
            }
            require(supported.isNotEmpty()) { "No Cast-compatible media found in this library" }
            val selectedIndex = supported.indexOfFirst { it.first.uri == selected.uri }
            require(selectedIndex >= 0) { "The selected item is not supported by the Cast receiver" }
            saveCastRecoverySnapshot(supported, selectedIndex)

            castStreamService?.clearServers()

            val currentConfig = controllerState.value?.currentMediaItem?.localConfiguration
            val selectedSubtitle = currentConfig
                ?.takeIf { it.uri == Uri.parse(selected.uri) }
                ?.subtitleConfigurations
                ?.firstOrNull { it.mimeType == MimeTypes.TEXT_VTT }
            val selectedSubtitleUri = selectedSubtitle?.uri
            val localSources = supported.mapIndexedNotNull { index, item ->
                if (item.second.scheme == "http" || item.second.scheme == "https") null
                else LocalCastMediaSource("item$index", item.second, item.third)
            }.toMutableList()
            if (selectedSubtitleUri != null && selectedSubtitleUri.scheme != "http" && selectedSubtitleUri.scheme != "https") {
                localSources += LocalCastMediaSource("subtitle$selectedIndex", selectedSubtitleUri, "text/vtt")
            }
            val queueServer = if (localSources.isNotEmpty()) {
                castStreamService?.startQueueServer(localSources)
            } else null

            val queueItems = supported.mapIndexed { index, (file, uri, mime) ->
                val mediaUrl = if (uri.scheme == "http" || uri.scheme == "https") uri.toString()
                    else queueServer?.urlFor("item$index")
                        ?: error("Could not create a local Cast stream for ${file.title}")
                val metadataType = if (mime.startsWith("audio/")) CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK
                    else CastMediaMetadata.MEDIA_TYPE_MOVIE
                val metadata = CastMediaMetadata(metadataType).apply {
                    putString(CastMediaMetadata.KEY_TITLE, file.title)
                }
                val infoBuilder = MediaInfo.Builder(mediaUrl)
                    .setContentType(mime)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setMetadata(metadata)
                if (index == selectedIndex && selectedSubtitle != null) {
                    val subtitleUrl = if (selectedSubtitleUri?.scheme == "http" || selectedSubtitleUri?.scheme == "https") {
                        selectedSubtitleUri.toString()
                    } else queueServer?.urlFor("subtitle$selectedIndex")
                    if (subtitleUrl != null) {
                        val subtitleTrack = MediaTrack.Builder(1L, MediaTrack.TYPE_TEXT)
                            .setName(selectedSubtitle.label ?: selectedSubtitle.language ?: "Subtitles")
                            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                            .setContentId(subtitleUrl)
                            .setContentType("text/vtt")
                            .setLanguage(selectedSubtitle.language ?: "und")
                            .build()
                        infoBuilder.setMediaTracks(listOf(subtitleTrack))
                    }
                }
                val info = infoBuilder.build()
                MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(20.0).build()
            }

            controllerState.value?.pause()
            val remote = session.remoteMediaClient
                ?: error("TV connected, but remote playback is unavailable")
            castMessageState.value = "Sending ${queueItems.size} items to ${session.castDevice?.friendlyName ?: "TV"}…"
            remote.queueLoad(
                queueItems.toTypedArray(),
                selectedIndex,
                MediaStatus.REPEAT_MODE_REPEAT_OFF,
                startPositionMs.coerceAtLeast(0L),
                null
            ).setResultCallback { result ->
                castMessageState.value = if (result.status.isSuccess) {
                    "Playing library queue on ${session.castDevice?.friendlyName ?: "TV"}"
                } else {
                    castStreamService?.clearServers()
                    "TV could not load the queue (Cast error ${result.status.statusCode})"
                }
            }
            val skipped = library.size - supported.size
            if (skipped > 0) castMessageState.value = "Sending ${supported.size} compatible items; skipped $skipped unsupported file(s)"
            if (selectedSubtitle != null) castMessageState.value = "Cast queue sent with WebVTT subtitles available for the selected item"
        }.onFailure {
            castStreamService?.clearServers()
            castMessageState.value = "Cast queue failed: ${it.message}"
        }
    }

    private fun setCastShuffleEnabled(enabled: Boolean) {
        val remote = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        val status = remote.mediaStatus ?: return
        val items = status.queueItems.orEmpty()
        if (items.size < 3) {
            castMessageState.value = "Shuffle needs at least three queued items"
            return
        }
        val currentIndex = items.indexOfFirst { it.itemId == status.currentItemId }
        if (currentIndex < 0 || currentIndex >= items.lastIndex) {
            castMessageState.value = "No upcoming items available to shuffle"
            return
        }
        val futureIds = items.drop(currentIndex + 1).map { it.itemId }
        if (enabled) {
            castOriginalFutureOrder = futureIds
            remote.queueReorderItems(futureIds.shuffled().toIntArray(), MediaQueueItem.INVALID_ITEM_ID, null)
                .setResultCallback { result ->
                    if (result.status.isSuccess) {
                        castShuffleState.value = true
                        castMessageState.value = "Shuffle enabled on TV"
                    } else castMessageState.value = "TV could not shuffle the queue (error ${result.status.statusCode})"
                }
        } else {
            val original = castOriginalFutureOrder
            if (original.isNullOrEmpty()) {
                castShuffleState.value = false
                castMessageState.value = "Shuffle disabled; current queue order retained"
                return
            }
            remote.queueReorderItems(original.toIntArray(), MediaQueueItem.INVALID_ITEM_ID, null)
                .setResultCallback { result ->
                    if (result.status.isSuccess) {
                        castShuffleState.value = false
                        castMessageState.value = "Original upcoming order restored"
                    } else castMessageState.value = "TV could not restore queue order (error ${result.status.statusCode})"
                }
        }
    }

    private fun setCastRepeatMode(mode: Int) {
        val remote = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        val castMode = when (mode) {
            Player.REPEAT_MODE_ALL -> MediaStatus.REPEAT_MODE_REPEAT_ALL
            Player.REPEAT_MODE_ONE -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
            else -> MediaStatus.REPEAT_MODE_REPEAT_OFF
        }
        remote.queueSetRepeatMode(castMode, null).setResultCallback { result ->
            if (result.status.isSuccess) {
                castRepeatModeState.intValue = mode
                castMessageState.value = when (mode) {
                    Player.REPEAT_MODE_ALL -> "Repeat all enabled on TV"
                    Player.REPEAT_MODE_ONE -> "Repeat current item enabled on TV"
                    else -> "Repeat disabled on TV"
                }
            } else castMessageState.value = "TV could not change repeat mode (error ${result.status.statusCode})"
        }
    }

    private fun castMedia(uriText: String, title: String, suppliedMime: String?, startPositionMs: Long = 0L) {
        val session = castContext?.sessionManager?.currentCastSession ?: return
        val requestedUri = Uri.parse(uriText)
        if (requestedUri.scheme != "http" && requestedUri.scheme != "https" && castStreamService == null) {
            withCastStreamService { castMedia(uriText, title, suppliedMime, startPositionMs) }
            return
        }
        runCatching {
            val uri = Uri.parse(uriText)
            val mime = suppliedMime?.takeUnless { it == "application/octet-stream" }
                ?: contentResolver.getType(uri)?.takeUnless { it == "application/octet-stream" }
                ?: mimeFromName(uri.lastPathSegment)
            if (uri.scheme != "http" && uri.scheme != "https") {
                val recoveryFile = MediaFileEntity(uri.toString(), title, mime, System.currentTimeMillis())
                saveCastRecoverySnapshot(listOf(Triple(recoveryFile, uri, mime)), 0)
            } else clearCastRecoverySnapshot()
            val url = if (uri.scheme == "http" || uri.scheme == "https") {
                castStreamService?.clearServers()
                uri.toString()
            } else {
                if (mime == "application/octet-stream" || mime == "video/x-matroska") {
                    error("This format is not supported by Google's Default Media Receiver. Try MP4, WebM, MP3 or AAC.")
                }
                castStreamService?.startSingleServer(uri, mime)
                    ?: error("Background Cast streaming service is unavailable")
            }
            val mediaType = if (mime.startsWith("audio/")) CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK
                else CastMediaMetadata.MEDIA_TYPE_MOVIE
            val metadata = CastMediaMetadata(mediaType).apply { putString(CastMediaMetadata.KEY_TITLE, title) }
            val mediaInfo = MediaInfo.Builder(url)
                .setContentType(mime)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(metadata)
                .build()
            controllerState.value?.pause()
            val result = session.remoteMediaClient?.load(
                MediaLoadRequestData.Builder().setMediaInfo(mediaInfo).setAutoplay(true)
                    .setCurrentTime(startPositionMs.coerceAtLeast(0L)).build()
            )
            if (result == null) {
                castMessageState.value = "TV connected, but remote playback is unavailable"
            } else {
                castMessageState.value = "Sending to ${session.castDevice?.friendlyName ?: "TV"}"
                result.setResultCallback { loadResult ->
                    castMessageState.value = if (loadResult.status.isSuccess) {
                        "Playing on ${session.castDevice?.friendlyName ?: "TV"}"
                    } else {
                        "TV could not load this media (Cast error ${loadResult.status.statusCode})"
                    }
                }
            }
        }.onFailure {
            castStreamService?.clearServers()
            castMessageState.value = "Cast failed: ${it.message}"
        }
    }

    private fun mimeFromName(name: String?): String {
        val extension = name?.substringAfterLast('.', "")?.lowercase()
        return when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    override fun onDestroy() {
        castListener?.let { listener ->
            castContext?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        }
        castListener = null
        if (castStreamBound) {
            runCatching { unbindService(castStreamConnection) }
            castStreamBound = false
        }
        castStreamService = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controllerState.value = null
        super.onDestroy()
    }
}

@Composable
private fun CastSubtitleControl(available: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    if (available) {
        IconButton(onClick = onToggle) {
            Icon(Icons.Default.Subtitles, contentDescription = if (enabled) "Turn Cast subtitles off" else "Turn Cast subtitles on",
                tint = if (enabled) Cyan else Color.White)
        }
    }
}

@Composable
private fun CastRouteButton() {
    AndroidView(
        factory = { context ->
            MediaRouteButton(context).also { button ->
                runCatching { CastButtonFactory.setUpMediaRouteButton(context, button) }
            }
        },
        modifier = Modifier.size(42.dp)
    )
}

private fun toMediaItem(file: MediaFileEntity): MediaItem =
    MediaItem.Builder().setUri(file.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(file.title).build())
        .build()

@Composable
private fun LibraryScreen(
    media: List<MediaFileEntity>,
    playlists: List<PlaylistEntity>,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onPlay: (MediaFileEntity) -> Unit,
    onRemove: (MediaFileEntity) -> Unit,
    onCreatePlaylist: () -> Unit,
    onPlayPlaylist: (PlaylistEntity) -> Unit,
    onAddToPlaylist: (PlaylistEntity, MediaFileEntity) -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit
) {
    var tab by remember { mutableStateOf("media") }
    Column(
        Modifier.fillMaxSize().background(Canvas).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ABM", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("LIBRARY", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done", color = Cyan) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LibraryTab("Media", tab == "media") { tab = "media" }
            Spacer(Modifier.width(8.dp))
            LibraryTab("Playlists", tab == "playlists") { tab = "playlists" }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = if (tab == "media") onImport else onCreatePlaylist) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Cyan)
            }
        }
        if (tab == "media") {
            Text("${media.size} imported files", color = Muted, fontSize = 12.sp)
            if (media.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Violet, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Your library is waiting", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Import audio and video files to get started.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Text("Import media", color = Canvas)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(media, key = { it.uri }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                                .background(Panel).clickable { onPlay(item) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Cyan)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.mimeType ?: "Media file", color = Muted, fontSize = 10.sp)
                            }
                            playlists.forEach { playlist ->
                                Text(playlist.name.take(1), color = Violet,
                                    modifier = Modifier.clickable { onAddToPlaylist(playlist, item) }.padding(5.dp))
                            }
                            IconButton(onClick = { onRemove(item) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", tint = Muted)
                            }
                        }
                    }
                }
            }
        } else if (playlists.isEmpty()) {
            Text("Create a playlist to organise your favourite media.", color = Muted)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                            .background(Panel).clickable { onPlayPlaylist(playlist) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Violet)
                        Spacer(Modifier.width(12.dp))
                        Text(playlist.name, color = Color.White, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onDeletePlaylist(playlist) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete playlist", tint = Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, color = if (selected) Canvas else Muted,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Cyan else Raised).clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 10.dp),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PlayerScreen(
    title: String,
    castConnected: Boolean,
    castDeviceName: String,
    castMessage: String,
    castQueueSummary: String,
    player: Player?,
    hasVideo: Boolean,
    playing: Boolean,
    castShuffleEnabled: Boolean,
    castRepeatMode: Int,
    castSubtitleAvailable: Boolean,
    castSubtitleEnabled: Boolean,
    onCastSubtitleToggle: () -> Unit,
    position: Long,
    duration: Long,
    progress: Float,
    onBrowse: () -> Unit,
    onLibrary: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onPickSubtitle: () -> Unit,
    fullscreen: Boolean,
    onFullscreen: () -> Unit
) {
    BackHandler(enabled = fullscreen, onBack = onFullscreen)
    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!castConnected && hasVideo && player != null) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(if (castConnected) "PLAYING ON $castDeviceName" else "No video loaded", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CastRouteButton()
                IconButton(onClick = onFullscreen) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen", tint = Color.White)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xCC090B12)).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold)
                if (castConnected) Text("Casting to $castDeviceName · $castMessage", color = Cyan, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 18.dp))
                Slider(
                    value = progress.coerceIn(0f, 1f),
                    onValueChange = onSeek,
                    colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan)
                )
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(formatTime(position), color = Muted, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPrevious, enabled = true) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                        }
                        IconButton(onClick = onPlayPause) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play", tint = Cyan)
                        }
                        IconButton(onClick = onNext, enabled = true) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                        }
                        CastSubtitleControl(castSubtitleAvailable, castSubtitleEnabled, onCastSubtitleToggle)
                        CastSubtitleControl(castSubtitleAvailable, castSubtitleEnabled, onCastSubtitleToggle)
                AdvancedPlaybackMenu(player = player, onPickSubtitle = onPickSubtitle)
                    }
                    Text(formatTime(duration), color = Muted, fontSize = 11.sp)
                }
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF111827), Canvas, Canvas)))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(Cyan, Violet))),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Canvas)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ABM", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("AV PLAYER", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                AdvancedPlaybackMenu(player = player, onPickSubtitle = onPickSubtitle)
                CastRouteButton()
                IconButton(onClick = onFullscreen, enabled = hasVideo) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = Cyan)
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Panel),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(12.dp).clip(RoundedCornerShape(19.dp))
                        .background(Brush.linearGradient(
                            listOf(Color(0xFF202B40), Color(0xFF151C2B), Color(0xFF241D3D))
                        )),
                    contentAlignment = Alignment.Center
                ) {
                    if (!castConnected && player?.currentMediaItem != null && player != null) {
                        AndroidView(
                            factory = { context ->
                                PlayerView(context).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    this.player = player
                                }
                            },
                            update = { it.player = player },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        if (castConnected) {
                            Text("PLAYING ON $castDeviceName", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null,
                                tint = Cyan, modifier = Modifier.size(68.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("YOUR MEDIA. YOUR SPACE.", color = Color.White.copy(alpha = .75f),
                                fontSize = 10.sp, letterSpacing = 2.sp)
                        }
                    }
                }
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (castConnected && castQueueSummary.isNotBlank()) {
                    Text(castQueueSummary, color = Violet, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                }
                if (castConnected || (castMessage.isNotBlank() && castMessage != "Disconnected")) {
                    Text(if (castConnected) "CASTING  ·  $castDeviceName  ·  $castMessage" else castMessage,
                        color = if (castConnected) Cyan else Muted, fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                }
                Slider(
                    value = progress.coerceIn(0f, 1f),
                    onValueChange = onSeek,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan,
                        inactiveTrackColor = Raised)
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = Muted, fontSize = 11.sp)
                    Text(formatTime(duration), color = Muted, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onShuffle, enabled = true) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle",
                            tint = if (if (castConnected) castShuffleEnabled else player?.shuffleModeEnabled == true) Cyan else Muted)
                    }
                    IconButton(onClick = onPrevious, enabled = true) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous",
                            tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onPlayPause, shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                        modifier = Modifier.size(64.dp), contentPadding = PaddingValues(0.dp)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Canvas, modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = onNext, enabled = true) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next",
                            tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = onRepeat, enabled = true) {
                        Icon(Icons.Default.Repeat, contentDescription = "Repeat",
                            tint = if (if (castConnected) castRepeatMode != Player.REPEAT_MODE_OFF else player?.repeatMode != Player.REPEAT_MODE_OFF) Cyan else Muted)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBrowse, modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Raised)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Cyan)
                    Spacer(Modifier.width(8.dp)); Text("Open media", color = Color.White)
                }
                Button(onClick = onLibrary, modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Raised)) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Violet)
                    Spacer(Modifier.width(8.dp)); Text("Library", color = Color.White)
                }
            }
            Text("ABM AV PLAYER  •  BUILT FOR YOUR MEDIA", Modifier.fillMaxWidth(),
                color = Muted.copy(alpha = .7f), fontSize = 9.sp, letterSpacing = 1.2.sp)
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}
