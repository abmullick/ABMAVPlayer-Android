package com.abm.avplayer.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.abm.avplayer.MainActivity
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class TorrentFileEntry(val index: Int, val path: String, val size: Long)
data class TorrentTaskSnapshot(
    val infoHash: String,
    val name: String,
    val progress: Float,
    val downloaded: Long,
    val total: Long,
    val downloadRate: Int,
    val uploadRate: Int,
    val peers: Int,
    val hasMetadata: Boolean,
    val paused: Boolean,
    val error: String = ""
)

/**
 * Owns one process-wide libtorrent session. All JNI/session calls are serialized
 * on the IO dispatcher by the Activity; no native torrent object is exposed to Compose.
 */
class TorrentManagerService : Service() {
    inner class LocalBinder : Binder() { fun service() = this@TorrentManagerService }
    private val binder = LocalBinder()
    private lateinit var session: SessionManager
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val knownHashes = ConcurrentHashMap.newKeySet<String>()
    private val errors = ConcurrentHashMap<String, String>()
    private val pausedHashes = ConcurrentHashMap.newKeySet<String>()
    private val downloadRoot by lazy { File(getExternalFilesDir(null), "Torrent Downloads").apply { mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        session = SessionManager()
        val settings = SettingsPack()
        settings.setDhtBootstrapNodes("dht.libtorrent.org:25401,router.bittorrent.com:6881,dht.transmissionbt.com:6881")
        settings.setEnableDht(true)
        settings.setEnableLsd(true)
        session.start(com.frostwire.jlibtorrent.SessionParams(settings))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        return START_STICKY
    }

    fun addMagnet(uri: String): String {
        require(uri.length <= 8192 && uri.startsWith("magnet:?", true)) { "Enter a valid magnet URI." }
        val hash = parseBtih(uri) ?: error("This magnet link must contain a valid BitTorrent v1 info hash.")
        val handle = handles[hash]
        if (handle != null && handle.isValid()) return hash
        session.download(uri, File(downloadRoot, hash), torrent_flags_t())
        knownHashes.add(hash)
        return hash
    }

    fun addTorrent(data: ByteArray): String {
        require(data.isNotEmpty() && data.size <= 16 * 1024 * 1024) { "Torrent metadata must be between 1 byte and 16 MiB." }
        val info = TorrentInfo(data)
        val hash = info.infoHashV1().toHex()
        require(hash.matches(Regex("[0-9a-fA-F]{40}"))) { "Only v1/hybrid torrent metadata is currently supported." }
        session.download(info, File(downloadRoot, hash))
        session.find(info)?.let { handles[hash] = it }
        knownHashes.add(hash)
        return hash
    }

    fun refresh(): List<TorrentTaskSnapshot> {
        return knownHashes.mapNotNull { hash ->
            runCatching {
                val handle = handles[hash]?.takeIf { it.isValid() } ?: session.find(Sha1Hash(hash))?.also { handles[hash] = it } ?: return@mapNotNull null
                if (!handle.isValid()) return@mapNotNull null
                val status = handle.status(true)
                val metadata = handle.torrentFile()
                TorrentTaskSnapshot(
                    infoHash = hash,
                    name = status.name().ifBlank { "Retrieving metadata…" },
                    progress = status.progress().coerceIn(0f, 1f),
                    downloaded = status.totalWantedDone(),
                    total = status.totalWanted(),
                    downloadRate = status.downloadRate(),
                    uploadRate = status.uploadRate(),
                    peers = status.numPeers(),
                    hasMetadata = status.hasMetadata(),
                    paused = pausedHashes.contains(hash),
                    error = errors[hash].orEmpty()
                )
            }.getOrNull()
        }
    }

    fun files(hash: String): List<TorrentFileEntry> {
        val handle = handle(hash)
        val info = handle.torrentFile() ?: return emptyList()
        val files = info.files()
        return (0 until files.numFiles()).map { index ->
            TorrentFileEntry(index, files.fileName(index), files.fileSize(index))
        }
    }

    /** Match Windows behaviour: do not download every file in a multi-file torrent. */
    fun selectFile(hash: String, index: Int) {
        val handle = handle(hash)
        val info = handle.torrentFile() ?: error("Torrent metadata is not ready yet.")
        val count = info.files().numFiles()
        require(index in 0 until count) { "Invalid torrent file selection." }
        val priorities = Array(count) { Priority.IGNORE }
        priorities[index] = Priority.NORMAL
        handle.prioritizeFiles(priorities)
        handle.resume()
    }

    fun pause(hash: String) { handle(hash).pause(); pausedHashes.add(hash) }
    fun resume(hash: String) { handle(hash).resume(); pausedHashes.remove(hash) }

    fun remove(hash: String, deleteData: Boolean) {
        val handle = handles.remove(hash) ?: return
        if (handle.isValid()) session.remove(handle)
        if (deleteData) File(downloadRoot, hash).deleteRecursively()
        errors.remove(hash)
        pausedHashes.remove(hash)
        knownHashes.remove(hash)
    }

    fun setRateLimits(downloadBytesPerSecond: Int, uploadBytesPerSecond: Int) {
        require(downloadBytesPerSecond >= 0 && uploadBytesPerSecond >= 0)
        session.applySettings(SettingsPack()
            .downloadRateLimit(downloadBytesPerSecond)
            .uploadRateLimit(uploadBytesPerSecond))
    }

    private fun handle(hash: String): TorrentHandle {
        handles[hash]?.takeIf { it.isValid() }?.let { return it }
        val found = session.find(Sha1Hash(hash)) ?: error("Torrent task is not available in this session.")
        handles[hash] = found
        return found
    }

    private fun parseBtih(uri: String): String? {
        val raw = Regex("(?i)xt=urn:btih:([^&]+)").find(uri)?.groupValues?.get(1) ?: return null
        if (raw.matches(Regex("(?i)[0-9a-f]{40}"))) return raw.lowercase()
        if (!raw.matches(Regex("(?i)[a-z2-7]{32}"))) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val bytes = ArrayList<Byte>()
        raw.uppercase().forEach { char ->
            val value = alphabet.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes += ((buffer shr bits) and 0xff).toByte()
            }
        }
        return if (bytes.size == 20) bytes.joinToString("") { "%02x".format(it) } else null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Torrent downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ABM AV Player · Torrent")
            .setContentText("Torrent session is active")
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        Thread({ runCatching { session.stop() } }, "ABM-Torrent-Shutdown").apply { isDaemon = true; start() }
        handles.clear()
        knownHashes.clear()
        pausedHashes.clear()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "abm_torrent_downloads"
        private const val NOTIFICATION_ID = 4102
    }
}
