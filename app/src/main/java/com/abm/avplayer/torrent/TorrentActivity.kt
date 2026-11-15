package com.abm.avplayer.torrent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.abm.avplayer.AbmTheme
import com.abm.avplayer.applyAbmLightSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TorrentActivity : ComponentActivity() {
    private var torrentService: TorrentManagerService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            torrentService = (binder as? TorrentManagerService.LocalBinder)?.service()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            torrentService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAbmLightSystemBars()
        val serviceIntent = Intent(this, TorrentManagerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            var magnet by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf("Use a magnet link or choose a .torrent file.") }
            var tasks by remember { mutableStateOf(emptyList<TorrentTaskSnapshot>()) }
            var files by remember { mutableStateOf<List<TorrentFileEntry>>(emptyList()) }
            var fileTask by remember { mutableStateOf<String?>(null) }
            var selectedTask by remember { mutableStateOf<String?>(null) }
            var showFiles by remember { mutableStateOf(false) }

            val torrentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) lifecycleScope.launch {
                    busy = true
                    runCatching {
                        val bytes = withContext(Dispatchers.IO) {
                            val input = contentResolver.openInputStream(uri)
                                ?: error("Could not read the selected torrent file.")
                            input.use { stream ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var total = 0
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= 16 * 1024 * 1024) { "Torrent metadata exceeds the 16 MiB safety limit." }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                        withContext(Dispatchers.IO) { torrentService?.addTorrent(bytes) ?: error("Torrent service is starting.") }
                    }.onSuccess { message = "Torrent added. Waiting for metadata and peers…" }
                        .onFailure { message = it.message ?: "Could not add torrent." }
                    busy = false
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    val service = torrentService
                    if (service != null) {
                        tasks = withContext(Dispatchers.IO) { service.refresh() }
                    }
                    delay(1000)
                }
            }

            AbmTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Torrent Stream", color = MaterialTheme.colorScheme.onSurface) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    },
                    containerColor = Color(0xFFFFFFFF)
                ) { insets ->
                    Column(
                        Modifier.fillMaxSize().padding(insets).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Download media you have the right to access. BitTorrent may also upload pieces to other peers.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = magnet, onValueChange = { magnet = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Magnet URI") }, singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    lifecycleScope.launch {
                                        busy = true
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                torrentService?.addMagnet(magnet.trim()) ?: error("Torrent service is starting.")
                                            }
                                        }.onSuccess { message = "Magnet added. Retrieving metadata…" }
                                            .onFailure { message = it.message ?: "Could not add magnet." }
                                        busy = false
                                    }
                                },
                                enabled = !busy && magnet.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f)
                            ) { Text("Add magnet") }
                            Button(
                                onClick = { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Text(" Open .torrent")
                            }
                        }
                        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (busy) CircularProgressIndicator()
                        Text("DOWNLOADS  ·  ${tasks.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (tasks.isEmpty()) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Text("No torrent tasks yet. Add a magnet link or a local .torrent file to begin.", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(tasks, key = { it.infoHash }) { task ->
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(task.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                            Text("${(task.progress * 100).toInt()}%  ·  ${formatBytes(task.downloaded)} / ${formatBytes(task.total)}", style = MaterialTheme.typography.bodySmall)
                                            LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
                                            Text("↓ ${formatRate(task.downloadRate)}   ↑ ${formatRate(task.uploadRate)}   ·   ${task.peers} peers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (!task.hasMetadata) Text("Retrieving torrent metadata…", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TextButton(onClick = {
                                                    lifecycleScope.launch {
                                                        runCatching {
                                                            withContext(Dispatchers.IO) {
                                                                val result = torrentService?.files(task.infoHash).orEmpty()
                                                                require(result.isNotEmpty()) { "Metadata is not ready yet." }
                                                                result
                                                            }
                                                        }.onSuccess {
                                                            files = it
                                                            fileTask = task.infoHash
                                                            showFiles = true
                                                        }.onFailure { message = it.message ?: "Could not list torrent files." }
                                                    }
                                                }, enabled = task.hasMetadata) { Text("Files") }
                                                IconButton(onClick = {
                                                    lifecycleScope.launch { withContext(Dispatchers.IO) {
                                                        if (task.paused) torrentService?.resume(task.infoHash) else torrentService?.pause(task.infoHash)
                                                    } }
                                                }) { Icon(if (task.paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (task.paused) "Resume" else "Pause") }
                                                Spacer(Modifier.weight(1f))
                                                IconButton(onClick = {
                                                    lifecycleScope.launch {
                                                        withContext(Dispatchers.IO) { torrentService?.remove(task.infoHash, false) }
                                                        tasks = tasks.filterNot { it.infoHash == task.infoHash }
                                                        message = "Task removed. Downloaded data was retained."
                                                    }
                                                }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Remove task") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showFiles) {
                AlertDialog(
                    onDismissRequest = { showFiles = false },
                    title = { Text("Select one file to download") },
                    text = {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(files) { file ->
                                Column(Modifier.fillMaxWidth().clickable {
                                    val hash = fileTask ?: return@clickable
                                    lifecycleScope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) { torrentService?.selectFile(hash, file.index) ?: error("Torrent service unavailable.") }
                                        }.onSuccess { message = "Selected ${file.path}. Only this file is prioritised for download." }
                                            .onFailure { message = it.message ?: "Could not select file." }
                                        showFiles = false
                                    }
                                }.padding(8.dp)) {
                                    Text(file.path, style = MaterialTheme.typography.bodyMedium)
                                    Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showFiles = false }) { Text("Close") } }
                )
            }
        }
    }

    override fun onDestroy() {
        if (bound) runCatching { unbindService(connection) }
        bound = false
        torrentService = null
        super.onDestroy()
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "${value.coerceAtLeast(0)} B"
    val units = listOf("KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = -1
    do { size /= 1024.0; unit++ } while (size >= 1024.0 && unit < units.lastIndex)
    return "%.1f %s".format(size, units[unit])
}

private fun formatRate(value: Int) = "${formatBytes(value.toLong())}/s"
