package com.abm.avplayer

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
import com.abm.avplayer.audio.TenBandEqSettings

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
                item {
                    TextButton(
                        onClick = {
                            player.trackSelectionParameters =
                                player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(trackType, trackType == C.TRACK_TYPE_TEXT)
                                    .clearOverridesOfType(trackType)
                                    .build()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (trackType == C.TRACK_TYPE_TEXT) "Off" else "Automatic",
                            color = Color.White
                        )
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
                            enabled = group.isTrackSupported(trackIndex),
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
                                color = if (!group.isTrackSupported(trackIndex)) Muted
                                    else if (selected) Cyan else Color.White
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

@Composable
private fun EqualizerDialog(player: Player, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(TenBandEqSettings.enabled) }
    var levels by remember { mutableStateOf(TenBandEqSettings.gains().toList()) }
    var presetsOpen by remember { mutableStateOf(false) }
    val presets = listOf("Flat", "Bass Boost", "Treble Boost", "Rock", "Classical", "Jazz", "Vocal")

    fun applyGains(values: List<Float>) {
        levels = values
        TenBandEqSettings.setGains(values.toFloatArray())
        TenBandEqSettings.save(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("10-band equalizer", color = Color.White)
                Text("App-owned DSP · PCM audio", color = Muted, fontSize = 11.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Equalizer", color = Color.White, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            TenBandEqSettings.setEnabled(it)
                            TenBandEqSettings.save(context)
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { presetsOpen = true }) {
                            Text("Presets ▾", color = Cyan)
                        }
                        DropdownMenu(
                            expanded = presetsOpen,
                            onDismissRequest = { presetsOpen = false }
                        ) {
                            presets.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset) },
                                    onClick = {
                                        applyGains(TenBandEqSettings.applyPreset(preset).toList())
                                        enabled = true
                                        TenBandEqSettings.setEnabled(true)
                                        TenBandEqSettings.save(context)
                                        presetsOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        applyGains(List(TenBandEqSettings.BAND_COUNT) { 0f })
                    }) { Text("Reset flat", color = Cyan) }
                }
                HorizontalDivider(color = Raised)
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(TenBandEqSettings.BAND_COUNT) { index ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    TenBandEqSettings.frequenciesHz[index].let {
                                        if (it >= 1000) "${it / 1000} kHz" else "${it} Hz"
                                    },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "%+.1f dB".format(levels[index]),
                                    color = Cyan,
                                    fontSize = 11.sp
                                )
                            }
                            Slider(
                                value = levels[index],
                                onValueChange = { value ->
                                    val updated = levels.toMutableList()
                                    updated[index] = value
                                    applyGains(updated)
                                },
                                valueRange = -12f..12f,
                                enabled = enabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = Cyan,
                                    activeTrackColor = Cyan,
                                    inactiveTrackColor = Raised,
                                    disabledThumbColor = Muted,
                                    disabledActiveTrackColor = Muted
                                )
                            )
                        }
                    }
                }
                Text(
                    "The equalizer processes decoded PCM audio. Encoded passthrough is not altered.",
                    color = Muted,
                    fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Cyan) }
        },
        containerColor = Panel
    )
}
