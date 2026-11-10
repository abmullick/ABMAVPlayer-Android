package com.abm.avplayer

import android.media.audiofx.Equalizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@Composable
fun AdvancedPlaybackMenu(
    player: Player?,
    onPickSubtitle: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var audioOpen by remember { mutableStateOf(false) }
    var subtitlesOpen by remember { mutableStateOf(false) }
    var equalizerOpen by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menuOpen = true }, enabled = player != null) {
            Icon(Icons.Default.MoreVert, contentDescription = "Playback tools", tint = Color.White)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Playback speed") },
                leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) },
                onClick = { menuOpen = false; speedOpen = true }
            )
            DropdownMenuItem(
                text = { Text("Audio track") },
                leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null) },
                onClick = { menuOpen = false; audioOpen = true }
            )
            DropdownMenuItem(
                text = { Text("Subtitle track") },
                leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                onClick = { menuOpen = false; subtitlesOpen = true }
            )
            DropdownMenuItem(
                text = { Text("Load external subtitle…") },
                leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                onClick = { menuOpen = false; onPickSubtitle() }
            )
            DropdownMenuItem(
                text = { Text("Equalizer") },
                leadingIcon = { Icon(Icons.Default.Equalizer, contentDescription = null) },
                onClick = { menuOpen = false; equalizerOpen = true }
            )
        }
    }

    if (speedOpen && player != null) {
        SpeedDialog(player = player, onDismiss = { speedOpen = false })
    }
    if (audioOpen && player != null) {
        TrackDialog(player = player, trackType = C.TRACK_TYPE_AUDIO,
            title = "Audio tracks", onDismiss = { audioOpen = false })
    }
    if (subtitlesOpen && player != null) {
        TrackDialog(player = player, trackType = C.TRACK_TYPE_TEXT,
            title = "Subtitle tracks", onDismiss = { subtitlesOpen = false })
    }
    if (equalizerOpen && player != null) {
        EqualizerDialog(player = player, onDismiss = { equalizerOpen = false })
    }
}

@Composable
private fun SpeedDialog(player: Player, onDismiss: () -> Unit) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed", color = Color.White) },
        text = {
            Column {
                speeds.forEach { speed ->
                    val selected = player.playbackParameters.speed == speed
                    TextButton(
                        onClick = {
                            player.playbackParameters = PlaybackParameters(speed)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (speed == 1f) "Normal  •  1.0×" else "${speed}×",
                            color = if (selected) Cyan else Color.White,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Cyan) } },
        containerColor = Panel
    )
}

@Composable
private fun TrackDialog(
    player: Player,
    trackType: Int,
    title: String,
    onDismiss: () -> Unit
) {
    val groups = player.currentTracks.groups.filter { it.type == trackType }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Color.White) },
        text = {
            LazyColumn {
                if (trackType == C.TRACK_TYPE_TEXT) {
                    item {
                        TextButton(
                            onClick = {
                                player.trackSelectionParameters =
                                    player.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                        .build()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Off", color = Color.White) }
                    }
                }
                if (groups.isEmpty()) {
                    item {
                        Text(
                            "No selectable tracks are available for this media.",
                            color = Muted,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                groups.forEachIndexed { groupIndex, group ->
                    items(group.length) { trackIndex ->
                        val format = group.getTrackFormat(trackIndex)
                        val label = format.label
                            ?: format.language
                            ?: "Track ${trackIndex + 1}"
                        val selected = group.isTrackSelected(trackIndex)
                        TextButton(
                            onClick = {
                                val override = TrackSelectionOverride(
                                    group.mediaTrackGroup, listOf(trackIndex)
                                )
                                player.trackSelectionParameters =
                                    player.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(trackType, false)
                                        .clearOverridesOfType(trackType)
                                        .addOverride(override)
                                        .build()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                label + if (selected) "  ✓" else "",
                                color = if (selected) Cyan else Color.White
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Cyan) } },
        containerColor = Panel
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun EqualizerDialog(player: Player, onDismiss: () -> Unit) {
    val audioSessionId = player.audioSessionId
    val equalizer = remember(audioSessionId) {
        if (audioSessionId > 0) runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        else null
    }
    var levels by remember(audioSessionId) {
        mutableStateOf(
            equalizer?.let { effect ->
                List(effect.numberOfBands.toInt()) { index ->
                    effect.getBandLevel(index.toShort()).toFloat() / 100f
                }
            } ?: emptyList()
        )
    }
    val range = equalizer?.bandLevelRange
    val minDb = (range?.getOrNull(0)?.toInt() ?: -1500) / 100f
    val maxDb = (range?.getOrNull(1)?.toInt() ?: 1500) / 100f
    var effectUnavailable by remember(audioSessionId) { mutableStateOf(equalizer == null) }
    var presetsOpen by remember(audioSessionId) { mutableStateOf(false) }

    DisposableEffect(equalizer) {
        onDispose { runCatching { equalizer?.release() } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio equalizer", color = Color.White) },
        text = {
            if (equalizer == null || levels.isEmpty()) {
                Text(
                    "The device did not expose an audio equalizer for this playback session. Try again after starting an audio track.",
                    color = Muted
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "Device-supported ${levels.size}-band equalizer",
                        color = Muted,
                        fontSize = 11.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Presets", color = Cyan, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.Box {
                            TextButton(onClick = { presetsOpen = true }) {
                                Text("Choose preset", color = Cyan)
                            }
                            DropdownMenu(
                                expanded = presetsOpen,
                                onDismissRequest = { presetsOpen = false }
                            ) {
                                (0 until equalizer.numberOfPresets.toInt()).forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(equalizer.getPresetName(preset.toShort()).toString()) },
                                        onClick = {
                                            runCatching {
                                                equalizer.usePreset(preset.toShort())
                                                levels = List(equalizer.numberOfBands.toInt()) { i ->
                                                    equalizer.getBandLevel(i.toShort()).toFloat() / 100f
                                                }
                                            }
                                            presetsOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        TextButton(onClick = {
                            runCatching {
                                equalizer.usePreset(0)
                                levels = List(equalizer.numberOfBands.toInt()) { i ->
                                    equalizer.getBandLevel(i.toShort()).toFloat() / 100f
                                }
                            }
                        }) { Text("Reset", color = Cyan) }
                    }
                    HorizontalDivider(color = Raised)
                    LazyColumn {
                        items(levels.size) { index ->
                            val centerHz = equalizer.getCenterFreq(index.toShort()) / 1000
                            val frequency = if (centerHz >= 1000) "${centerHz / 1000}k" else "${centerHz}"
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(frequency, color = Color.White, fontSize = 11.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text("%+.1f dB".format(levels[index]), color = Cyan, fontSize = 11.sp)
                                }
                                Slider(
                                    value = levels[index].coerceIn(minDb, maxDb),
                                    onValueChange = { value ->
                                        val updated = levels.toMutableList()
                                        updated[index] = value
                                        levels = updated
                                        runCatching {
                                            equalizer.setBandLevel(index.toShort(), (value * 100).toInt().toShort())
                                        }.onFailure { effectUnavailable = true }
                                    },
                                    valueRange = minDb..maxDb
                                )
                            }
                        }
                    }
                    if (effectUnavailable) {
                        Text("The device audio effect is unavailable or was disabled by the system.", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Cyan) } },
        containerColor = Panel
    )
}
