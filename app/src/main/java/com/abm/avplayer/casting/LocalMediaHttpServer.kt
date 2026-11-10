package com.abm.avplayer.casting

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small LAN HTTP range server for user-selected content:// documents.
 * The Cast receiver must be on a network that can reach this Android device.
 */
class LocalMediaHttpServer(
    private val resolver: ContentResolver,
    private val uri: Uri
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val clients = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private var fileLength: Long = -1L
    private var startOffset: Long = 0L
    private var mimeType: String = "application/octet-stream"
    private var displayName: String = "media"

    @Synchronized
    fun start(): String {
        if (running.get() && server != null) return mediaUrl()
        val afd = resolver.openAssetFileDescriptor(uri, "r")
            ?: error("Cannot open the selected media document")
        startOffset = afd.startOffset
        fileLength = afd.length
        if (fileLength < 0L) {
            afd.close()
            error("This media provider does not expose a seekable file length")
        }
        mimeType = resolver.getType(uri) ?: "application/octet-stream"
        displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "media"
        } ?: "media"
        val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("0.0.0.0"))
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
        }, "ABM-Cast-HTTP").apply { isDaemon = true; start() }
        return mediaUrl()
    }

    private fun mediaUrl(): String {
        val address = NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress ?: error("No reachable Wi-Fi/LAN IPv4 address found")
        return "http://$address:${server?.localPort}/media"
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
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
            var start = 0L
            var end = fileLength - 1L
            var partial = false
            rangeHeader?.removePrefix("bytes=")?.split("-", limit = 2)?.let { range ->
                val requestedStart = range.getOrNull(0)?.toLongOrNull()
                val requestedEnd = range.getOrNull(1)?.toLongOrNull()
                if (requestedStart != null) {
                    start = requestedStart.coerceIn(0L, fileLength - 1L)
                    end = (requestedEnd ?: end).coerceIn(start, fileLength - 1L)
                    partial = true
                }
            }
            val length = end - start + 1L
            val status = if (partial) "206 Partial Content" else "200 OK"
            val headers = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (partial) append("Content-Range: bytes $start-$end/$fileLength\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray(Charsets.US_ASCII))
            output.flush()
            if (parts[0] == "HEAD") return
            val afd = resolver.openAssetFileDescriptor(uri, "r") ?: return
            FileInputStream(afd.fileDescriptor).channel.use { channel ->
                channel.position(startOffset + start)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val count = channel.read(java.nio.ByteBuffer.wrap(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.flush()
            }
            afd.close()
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
