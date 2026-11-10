package com.abm.avplayer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.activity.ComponentActivity
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
import com.abm.avplayer.data.MediaDatabase
import com.abm.avplayer.data.MediaFileEntity
import com.abm.avplayer.data.MediaLibraryRepository
import com.abm.avplayer.data.PlaylistEntity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Canvas = Color(0xFF090B12)
private val Panel = Color(0xFF141925)
private val Raised = Color(0xFF1B2332)
private val Cyan = Color(0xFF54D8E8)
private val Violet = Color(0xFF9A7BFF)
private val Muted = Color(0xFF929BAE)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                try {
                    while (true) {
                        position = active.currentPosition.coerceAtLeast(0L)
                        duration = active.duration.coerceAtLeast(0L)
                        playing = active.isPlaying
                        if (duration > 0L) progress = (position.toFloat() / duration).coerceIn(0f, 1f)
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
                                controller?.let { player ->
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
                                    val tracks = library.filter { it.uri in uris }
                                    if (tracks.isNotEmpty()) {
                                        controller?.setMediaItems(tracks.map(::toMediaItem))
                                        controller?.prepare()
                                        controller?.play()
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
                            hasMedia = controller?.currentMediaItem != null,
                            playing = playing, position = position, duration = duration,
                            progress = progress,
                            onBrowse = { picker.launch(arrayOf("audio/*", "video/*")) },
                            onLibrary = { page = "library" },
                            onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                            onPrevious = { controller?.seekToPreviousMediaItem() },
                            onNext = { controller?.seekToNextMediaItem() },
                            onSeek = { value ->
                                progress = value
                                if (duration > 0L) controller?.seekTo((duration * value).toLong())
                            },
                            onShuffle = { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
                            onRepeat = {
                                controller?.let {
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

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controllerState.value = null
        super.onDestroy()
    }
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
    player: Player?,
    hasMedia: Boolean,
    playing: Boolean,
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
    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasMedia && player != null) {
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
                Text("No video loaded", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            IconButton(
                onClick = onFullscreen,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen", tint = Color.White)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xCC090B12)).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold)
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
                        IconButton(onClick = onPrevious) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                        }
                        IconButton(onClick = onPlayPause) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play", tint = Cyan)
                        }
                        IconButton(onClick = onNext) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                        }
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
                IconButton(onClick = onFullscreen, enabled = hasMedia) {
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
                    if (hasMedia && player != null) {
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    IconButton(onClick = onShuffle) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle",
                            tint = if (player?.shuffleModeEnabled == true) Cyan else Muted)
                    }
                    IconButton(onClick = onPrevious) {
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
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next",
                            tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = onRepeat) {
                        Icon(Icons.Default.Repeat, contentDescription = "Repeat",
                            tint = if (player?.repeatMode != Player.REPEAT_MODE_OFF) Cyan else Muted)
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
