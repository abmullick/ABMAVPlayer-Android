package com.abm.avplayer.casting

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class LocalCastMediaSource(val id: String, val uri: Uri, val contentType: String)

/** Temporary LAN HTTP server exposing a queue of user-selected SAF documents to Cast. */
class LocalMediaQueueHttpServer(
    private val resolver: ContentResolver,
    sources: List<LocalCastMediaSource>
) : AutoCloseable {
    private data class Entry(val source: LocalCastMediaSource, val length: Long)
    private val entries: Map<String, Entry>
    private val running = AtomicBoolean(false)
    private val clients = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private val token = UUID.randomUUID().toString().replace("-", "")

    init {
        require(sources.isNotEmpty()) { "No media sources supplied" }
        entries = sources.associate { source ->
            require(source.id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid media source id" }
            val afd = resolver.openAssetFileDescriptor(source.uri, "r")
                ?: error("Cannot open ${source.uri}")
            val length = try { afd.length } finally { afd.close() }
            require(length > 0L) { "A media document does not expose a seekable file length" }
            source.id to Entry(source, length)
        }
        require(entries.size == sources.size) { "Duplicate media source id" }
    }

    @Synchronized
    fun start(): String {
        if (running.get() && server != null) return baseUrl()
        val socket = ServerSocket(0, 16, java.net.InetAddress.getByName("0.0.0.0"))
        server = socket
        running.set(true)
        Thread({
            while (running.get()) {
                try {
                    val client = socket.accept()
                    clients.execute { serve(client) }
                } catch (_: Exception) {
                    if (running.get()) close()
                }
            }
        }, "ABM-Cast-Queue-HTTP").apply { isDaemon = true; start() }
        return baseUrl()
    }

    fun urlFor(id: String): String {
        require(entries.containsKey(id)) { "Unknown Cast queue item" }
        return "${baseUrl()}/$token/$id"
    }

    private fun baseUrl(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
        val addresses = interfaces.flatMap { network ->
            network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { network.name.lowercase() to it.hostAddress }
        }
        val address = addresses.firstOrNull { (name, _) ->
            name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("eth")
        }?.second ?: addresses.firstOrNull()?.second
            ?: error("No reachable Wi-Fi/LAN IPv4 address found")
        return "http://$address:${server?.localPort}"
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = readLine(input)?.split(" ") ?: return
            val path = request.getOrNull(1).orEmpty().trimStart('/').split("/")
            val entry = if (path.size == 2 && path[0] == token) entries[path[1]] else null
            if (entry == null) {
                output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            val method = request.firstOrNull()
            if (method != "GET" && method != "HEAD") {
                output.write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            var rangeHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(":").trim()
            }
            val total = entry.length
            var start = 0L
            var end = total - 1L
            var partial = false
            if (rangeHeader?.startsWith("bytes=") == true) {
                val range = rangeHeader!!.removePrefix("bytes=").split("-", limit = 2)
                val requestedStart = range.getOrNull(0)?.toLongOrNull()
                val requestedEnd = range.getOrNull(1)?.toLongOrNull()
                when {
                    requestedStart != null && requestedStart < total -> {
                        start = requestedStart
                        end = (requestedEnd ?: end).coerceIn(start, total - 1L)
                        partial = true
                    }
                    requestedStart == null && requestedEnd != null && requestedEnd > 0L -> {
                        start = (total - requestedEnd).coerceAtLeast(0L)
                        partial = true
                    }
                }
            }
            val length = end - start + 1L
            output.write(buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${entry.source.contentType}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Headers: Content-Type, Accept-Encoding, Range\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII))
            output.flush()
            if (method == "HEAD") return
            val afd = resolver.openAssetFileDescriptor(entry.source.uri, "r") ?: return
            afd.createInputStream().use { stream ->
                var skip = start
                while (skip > 0L) {
                    val count = stream.skip(skip)
                    if (count > 0L) skip -= count else {
                        if (stream.read() < 0) break
                        skip--
                    }
                }
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.flush()
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
            if (value == 10) break
            if (value != 13) bytes.write(value)
            if (bytes.size() > 8192) return null
        }
        return bytes.toString(Charsets.US_ASCII.name())
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        server = null
        clients.shutdownNow()
    }
}
