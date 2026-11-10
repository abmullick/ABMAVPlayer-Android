package com.abm.avplayer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private val Canvas = Color(0xFF090B12)
private val Panel = Color(0xFF141925)
private val PanelRaised = Color(0xFF1B2332)
private val Cyan = Color(0xFF54D8E8)
private val Violet = Color(0xFF9A7BFF)
private val Muted = Color(0xFF929BAE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val player = remember {
                ExoPlayer.Builder(this).build().apply {
                    playWhenReady = false
                }
            }
            DisposableEffect(player) {
                onDispose { player.release() }
            }

            var mediaTitle by remember { mutableStateOf("Nothing playing") }
            var hasMedia by remember { mutableStateOf(false) }
            var isPlaying by remember { mutableStateOf(false) }
            var duration by remember { mutableLongStateOf(0L) }
            var position by remember { mutableLongStateOf(0L) }
            var sliderValue by remember { mutableFloatStateOf(0f) }

            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        hasMedia = mediaItem != null
                        mediaTitle = mediaItem?.mediaMetadata?.title?.toString()
                            ?: mediaItem?.localConfiguration?.uri?.lastPathSegment
                            ?: "Now playing"
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        duration = player.duration.coerceAtLeast(0L)
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            LaunchedEffect(player) {
                while (true) {
                    if (player.isPlaying) {
                        position = player.currentPosition.coerceAtLeast(0L)
                        duration = player.duration.coerceAtLeast(0L)
                        if (duration > 0L) sliderValue = position.toFloat() / duration
                    }
                    delay(500)
                }
            }

            val openMedia = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let {
                    contentResolver.takePersistableUriPermission(
                        it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    val item = MediaItem.fromUri(it)
                    player.setMediaItem(item)
                    player.prepare()
                    player.play()
                    mediaTitle = it.lastPathSegment ?: "Selected media"
                    hasMedia = true
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
                    PlayerHome(
                        title = mediaTitle,
                        player = player,
                        hasMedia = hasMedia,
                        isPlaying = isPlaying,
                        position = position,
                        duration = duration,
                        sliderValue = sliderValue,
                        onBrowse = {
                            openMedia.launch(arrayOf("audio/*", "video/*"))
                        },
                        onPlayPause = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        onSeek = { fraction ->
                            sliderValue = fraction
                            if (duration > 0L) player.seekTo((duration * fraction).toLong())
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerHome(
    title: String,
    player: ExoPlayer,
    hasMedia: Boolean,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    sliderValue: Float,
    onBrowse: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111827), Canvas, Canvas))
        ).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(
                    Brush.linearGradient(listOf(Cyan, Violet))
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Canvas)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("ABM", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("AV PLAYER", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            IconButton(onClick = { /* Cast device panel is added in the casting milestone. */ }) {
                Icon(Icons.Default.Cast, contentDescription = "Cast", tint = Cyan)
            }
            IconButton(onClick = { /* Equalizer screen is added in the audio-tools milestone. */ }) {
                Icon(Icons.Default.Tune, contentDescription = "Audio settings", tint = Muted)
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(26.dp)).background(Panel),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp)
                    .clip(RoundedCornerShape(19.dp)).background(
                        Brush.linearGradient(
                            listOf(Color(0xFF202B40), Color(0xFF151C2B), Color(0xFF241D3D))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (hasMedia) {
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
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = Cyan,
                            modifier = Modifier.size(68.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("YOUR MEDIA. YOUR SPACE.", color = Color.White.copy(alpha = .75f),
                            fontSize = 10.sp, letterSpacing = 2.sp)
                    }
                }
            }
            Text(
                title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                maxLines = 1
            )
            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = onSeek,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Cyan,
                    activeTrackColor = Cyan,
                    inactiveTrackColor = PanelRaised
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(position), color = Muted, fontSize = 11.sp)
                Text(formatTime(duration), color = Muted, fontSize = 11.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {}) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous",
                        tint = Muted, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(20.dp))
                Button(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                    modifier = Modifier.size(64.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Canvas,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Spacer(Modifier.width(20.dp))
                IconButton(onClick = {}) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next",
                        tint = Muted, modifier = Modifier.size(30.dp))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onBrowse,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PanelRaised)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(9.dp))
                Text("Open media", color = Color.White)
            }
            Button(
                onClick = {},
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PanelRaised)
            ) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Violet)
                Spacer(Modifier.width(9.dp))
                Text("Library", color = Color.White)
            }
        }
        Text(
            "ABM AV PLAYER  •  BUILT FOR YOUR MEDIA",
            modifier = Modifier.fillMaxWidth(),
            color = Muted.copy(alpha = .7f),
            fontSize = 9.sp,
            letterSpacing = 1.2.sp
        )
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
