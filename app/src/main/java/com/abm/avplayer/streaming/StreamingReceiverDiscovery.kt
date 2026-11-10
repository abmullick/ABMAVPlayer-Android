package com.abm.avplayer.streaming

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

data class StreamingReceiver(
    val id: String,
    val name: String,
    val address: String,
    val supportsDlna: Boolean,
    val supportsDial: Boolean,
    val descriptionUrl: String,
    val avTransportControlUrl: String?,
    val avTransportServiceType: String?,
    val dialApplicationUrl: String?
) {
    val protocolLabel: String
        get() = listOfNotNull(
            if (supportsDlna) "DLNA" else null,
            if (supportsDial) "DIAL" else null
        ).joinToString(" + ")
}

data class DlnaPlaybackStatus(val state: String, val position: String, val duration: String)
data class DialApplicationStatus(val state: String, val detail: String)

data class YoutubeLoungePlaybackStatus(val state: String, val currentTimeSeconds: Double, val durationSeconds: Double, val videoId: String)

private data class YoutubeLoungeSession(val token: String, val sid: String, val gsessionId: String, var lastEventId: String?, var requestId: Int = 2, var commandOffset: Int = 1, var state: String = "UNKNOWN", var currentTimeSeconds: Double = 0.0, var durationSeconds: Double = 0.0, var videoId: String = "")

object StreamingReceiverDiscovery {
    private val youtubeSessions = ConcurrentHashMap<String, YoutubeLoungeSession>()
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val DIAL_TARGET = "urn:dial-multiscreen-org:service:dial:1"
    private const val RENDERER_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

    suspend fun discover(context: Context): List<StreamingReceiver> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("abm-streaming-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val locations = linkedMapOf<String, String?>()
            MulticastSocket(0).use { socket ->
                socket.soTimeout = 350
                socket.timeToLive = 2
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val targets = listOf(DIAL_TARGET, RENDERER_TARGET)
                targets.forEach { target ->
                    val request = (
                        "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $target\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII)
                    socket.send(java.net.DatagramPacket(request, request.size, group, SSDP_PORT))
                }
                val started = System.currentTimeMillis()
                val buffer = ByteArray(8192)
                while (System.currentTimeMillis() - started < 5_000L) {
                    try {
                        val packet = java.net.DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val response = String(packet.data, packet.offset, packet.length, StandardCharsets.ISO_8859_1)
                        val location = response.lineSequence()
                            .firstOrNull { it.substringBefore(':').trim().equals("location", true) }
                            ?.substringAfter(':')?.trim()
                        val advertisedApplicationUrl = response.lineSequence()
                            .firstOrNull { it.substringBefore(':').trim().equals("application-url", true) }
                            ?.substringAfter(':')?.trim()
                        if (!location.isNullOrBlank()) {
                            val parsed = runCatching { URL(location) }.getOrNull() ?: continue
                            if (parsed.protocol.equals("http", true) && isPrivateAddress(parsed.host)) {
                                locations[location] = locations[location] ?: advertisedApplicationUrl
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Continue until the discovery window expires.
                    }
                }
            }

            val found = linkedMapOf<String, StreamingReceiver>()
            locations.forEach { (location, advertisedApplicationUrl) ->
                runCatching { readReceiverDescription(location, advertisedApplicationUrl) }.getOrNull()?.let { receiver ->
                    val key = receiver.address.lowercase() + "|" + receiver.name.lowercase()
                    val old = found[key]
                    found[key] = if (old == null) receiver else merge(old, receiver)
                }
            }
            found.values.sortedBy { it.name.lowercase() }
        } finally {
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    private fun readReceiverDescription(location: String, advertisedApplicationUrl: String?): StreamingReceiver? {
        val connection = openPrivateHttp(URL(location), "GET") ?: return null
        val xml: String
        val applicationHeader: String?
        try {
            if (connection.responseCode !in 200..299) return null
            xml = connection.inputStream.bufferedReader().use { it.readText().take(512_000) }
            applicationHeader = advertisedApplicationUrl ?: connection.getHeaderField("Application-URL")
        } finally {
            connection.disconnect()
        }
        val document = parseXml(xml) ?: return null
        val root = document.documentElement
        val name = firstText(root, "friendlyName") ?: URL(location).host
        val services = root.getElementsByTagNameNS("*", "service")
        var control: String? = null
        var serviceType: String? = null
        for (i in 0 until services.length) {
            val service = services.item(i) as? Element ?: continue
            val type = childText(service, "serviceType") ?: continue
            if (type.contains(":service:AVTransport:", true)) {
                val controlPath = childText(service, "controlURL")
                if (!controlPath.isNullOrBlank()) {
                    val resolved = URL(URL(location), controlPath)
                    if (resolved.host.equals(URL(location).host, true) && isPrivateAddress(resolved.host)) {
                        control = resolved.toString()
                        serviceType = type
                    }
                }
                break
            }
        }
        val appUrl = applicationHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { URL(URL(location), raw) }.getOrNull()
        }?.takeIf { it.host.equals(URL(location).host, true) && it.protocol == "http" && isPrivateAddress(it.host) }
            ?.let { if (it.toString().endsWith("/")) it.toString() else "${it}/" }
        val dlna = control != null
        val dial = appUrl != null
        if (!dlna && !dial) return null
        return StreamingReceiver(
            id = URL(location).host.lowercase() + "|" + name.lowercase(),
            name = name,
            address = URL(location).host,
            supportsDlna = dlna,
            supportsDial = dial,
            descriptionUrl = location,
            avTransportControlUrl = control,
            avTransportServiceType = serviceType,
            dialApplicationUrl = appUrl
        )
    }

    suspend fun playOnDlna(receiver: StreamingReceiver, mediaUrl: String, mimeType: String, title: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val control = receiver.avTransportControlUrl ?: error("This TV does not expose a DLNA AVTransport service.")
                val endpoint = URL(control)
                require(endpoint.host.equals(receiver.address, true) && isPrivateAddress(endpoint.host)) {
                    "The TV control endpoint failed the local-network safety check."
                }
                val protocolInfo = "http-get:*:$mimeType:*"
                val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${xmlEscape(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="${xmlEscape(protocolInfo)}">${xmlEscape(mediaUrl)}</res></item></DIDL-Lite>"""
                val metadata = xmlEscape(didl)
                val serviceType = receiver.avTransportServiceType ?: error("Missing DLNA AVTransport service type.")
                soap(endpoint, serviceType, "SetAVTransportURI",
                    "<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(mediaUrl)}</CurrentURI><CurrentURIMetaData>$metadata</CurrentURIMetaData>")
                soap(endpoint, serviceType, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                Unit
            }
        }

    suspend fun launchYoutubeOnDial(receiver: StreamingReceiver, videoId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Could not identify a valid YouTube video ID on this page." }
                youtubeSessions.remove(receiver.id)
                val endpoint = dialYoutubeEndpoint(receiver)
                // Start the TV app first, then pair a YouTube Lounge remote session as Windows does.
                val launch = openPrivateHttp(endpoint, "POST") ?: error("Could not contact the TV's YouTube app.")
                try {
                    launch.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    launch.doOutput = true
                    launch.outputStream.use { it.write(ByteArray(0)) }
                    if (launch.responseCode !in listOf(200, 201, 202)) error("The TV rejected YouTube launch (HTTP " + launch.responseCode + ").")
                } finally { launch.disconnect() }

                var screenId: String? = null
                for (attempt in 0 until 6) {
                    val status = openPrivateHttp(endpoint, "GET")
                    if (status != null) {
                        try {
                            if (status.responseCode in 200..299) {
                                val body = status.inputStream.bufferedReader().use { it.readText().take(32_768) }
                                val xml = parseXml(body)
                                screenId = xml?.documentElement?.let { firstText(it, "screenId") }
                                    ?: Regex("<screenId>([^<]+)</screenId>").find(body)?.groupValues?.getOrNull(1)
                                    ?: Regex("[\\\"']screenId[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']").find(body)?.groupValues?.getOrNull(1)
                            }
                        } finally { status.disconnect() }
                    }
                    if (!screenId.isNullOrBlank()) break
                    Thread.sleep(500)
                }
                val resolvedScreenId = screenId?.takeIf { it.isNotBlank() }
                    ?: error("YouTube started, but the TV did not expose its screen ID.")

                val tokenResponse = youtubeLoungePost(
                    "/api/lounge/pairing/get_lounge_token_batch",
                    "screen_ids=" + formEncode(resolvedScreenId)
                )
                val token = Regex("\\\"loungeToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                    .find(tokenResponse)?.groupValues?.getOrNull(1)
                    ?: error("YouTube did not provide a remote-control token for this TV.")

                val bindPath = "/api/lounge/bc/bind?CVER=1&RID=1&VER=8&app=youtube-desktop&device=REMOTE_CONTROL&id=remote&loungeIdToken=" +
                    formEncode(token) + "&name=ABM%20AV%20Player"
                val bindResponse = youtubeLoungePost(bindPath, "")
                val sid = Regex("""\["c","([^"]+)""").find(bindResponse)?.groupValues?.getOrNull(1)
                    ?: error("YouTube did not return a valid remote-control session.")
                val aid = Regex("""\["c","[^"]+","([^"]+)""").find(bindResponse)?.groupValues?.getOrNull(1)
                val gsession = Regex("\\[\\\"S\\\",\\\"([^\\\"]+)").find(bindResponse)?.groupValues?.getOrNull(1)
                    ?: error("YouTube did not return a valid remote-control session.")

                val session = YoutubeLoungeSession(token, sid, gsession, aid)
                youtubeSessions[receiver.id] = session
                try {
                    sendYoutubeLoungeCommand(receiver, session, "setPlaylist", videoId)
                } catch (error: Exception) {
                    youtubeSessions.remove(receiver.id)
                    throw error
                }
            }
        }

    fun hasYoutubeRemoteSession(receiver: StreamingReceiver): Boolean =
        youtubeSessions.containsKey(receiver.id)

    fun clearYoutubeRemoteSession(receiver: StreamingReceiver) {
        youtubeSessions.remove(receiver.id)
    }

    suspend fun controlYoutubeLounge(receiver: StreamingReceiver, action: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(action in setOf("play", "pause", "stop", "next", "previous")) { "Unsupported YouTube remote command." }
                val session = youtubeSessions[receiver.id] ?: error("YouTube remote control is not paired. Launch a video first.")
                sendYoutubeLoungeCommand(receiver, session, if (action == "stop") "stopVideo" else action, null)
            }
        }

    suspend fun closeDialYoutube(receiver: StreamingReceiver): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = dialYoutubeEndpoint(receiver)
                val connection = openPrivateHttp(endpoint, "DELETE") ?: error("Could not contact the TV YouTube app.")
                try {
                    val code = connection.responseCode
                    if (code !in 200..299 && code != 404) error("DIAL stop failed (HTTP " + code + ").")
                } finally { connection.disconnect() }
                youtubeSessions.remove(receiver.id)
                Unit
            }
        }

    private fun sendYoutubeLoungeCommand(
        receiver: StreamingReceiver,
        session: YoutubeLoungeSession,
        command: String,
        videoId: String?
    ) {
        val (requestId, commandOffset) = synchronized(session) {
            val current = session.requestId to session.commandOffset
            session.requestId++
            session.commandOffset++
            current
        }
        val body = if (command == "setPlaylist") {
            val id = videoId ?: error("Missing YouTube video ID.")
            "count=1&ofs=" + commandOffset + "&req0__sc=setPlaylist&req0_currentIndex=0&req0_currentTime=0&req0_videoId=" +
                formEncode(id) + "&req0_videoIds=" + formEncode(id)
        } else {
            "count=1&ofs=" + commandOffset + "&req0__sc=" + formEncode(command)
        }
        val path = "/api/lounge/bc/bind?CVER=1&RID=" + requestId +
            "&VER=8&SID=" + formEncode(session.sid) +
            "&gsessionid=" + formEncode(session.gsessionId) +
            "&loungeIdToken=" + formEncode(session.token) +
            (if (session.lastEventId.isNullOrBlank()) "" else "&AID=" + formEncode(session.lastEventId!!)) +
            "&app=youtube-desktop&device=REMOTE_CONTROL&name=ABM%20AV%20Player&v=2"
        val response = youtubeLoungePost(path, body)
        if (response.contains("\\\"error\\\"", true) || response.contains("ERROR", true)) {
            error("YouTube reported an error while processing the remote command.")
        }
        require(receiver.supportsDial) { "Selected receiver no longer advertises DIAL." }
    }

    private fun youtubeLoungePost(path: String, body: String): String {
        val url = URL("https://www.youtube.com" + path)
        val connection = url.openConnection() as? javax.net.ssl.HttpsURLConnection
            ?: error("Could not open YouTube's secure remote-control endpoint.")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "ABM-AV-Player-Android/1.0")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Origin", "https://www.youtube.com")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText().take(1_048_576) }.orEmpty()
            if (code !in 200..299) error("YouTube remote-control request failed (HTTP " + code + ").")
            return response
        } finally { connection.disconnect() }
    }

    private fun formEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    suspend fun getDlnaPlaybackStatus(receiver: StreamingReceiver): Result<DlnaPlaybackStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = checkedControlEndpoint(receiver)
                val serviceType = receiver.avTransportServiceType ?: error("Missing DLNA AVTransport service type.")
                val transport = parseXml(soap(endpoint, serviceType, "GetTransportInfo", "<InstanceID>0</InstanceID>"))
                    ?: error("TV returned an invalid transport status.")
                // Some renderers expose transport state but reject position queries; keep the
                // connection healthy and show an unknown position rather than marking the TV offline.
                val position = runCatching {
                    parseXml(soap(endpoint, serviceType, "GetPositionInfo", "<InstanceID>0</InstanceID>"))
                }.getOrNull()
                DlnaPlaybackStatus(
                    state = firstText(transport.documentElement, "CurrentTransportState") ?: "UNKNOWN",
                    position = position?.let { firstText(it.documentElement, "RelTime") } ?: "00:00:00",
                    duration = position?.let { firstText(it.documentElement, "TrackDuration") } ?: "00:00:00"
                )
            }
        }

    suspend fun controlDlna(receiver: StreamingReceiver, action: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(action in setOf("Play", "Pause", "Stop")) { "Unsupported DLNA transport command." }
                val endpoint = checkedControlEndpoint(receiver)
                val serviceType = receiver.avTransportServiceType ?: error("Missing DLNA AVTransport service type.")
                val args = if (action == "Play") "<InstanceID>0</InstanceID><Speed>1</Speed>" else "<InstanceID>0</InstanceID>"
                soap(endpoint, serviceType, action, args)
                Unit
            }
        }

    suspend fun seekDlna(receiver: StreamingReceiver, target: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(target.matches(Regex("""\d{2,}:\d{2}:\d{2}"""))) { "Invalid seek position." }
                val endpoint = checkedControlEndpoint(receiver)
                val serviceType = receiver.avTransportServiceType ?: error("Missing DLNA AVTransport service type.")
                soap(endpoint, serviceType, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>")
                Unit
            }
        }

    suspend fun getDialApplicationStatus(receiver: StreamingReceiver): Result<DialApplicationStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = dialYoutubeEndpoint(receiver)
                val connection = openPrivateHttp(endpoint, "GET") ?: error("Could not contact the TV YouTube app.")
                try {
                    val code = connection.responseCode
                    if (code == 404) {
                        // DIAL 404 means the TV answered but the requested app is not running (or not installed).
                        return@runCatching DialApplicationStatus("STOPPED", "YouTube app is not running")
                    }
                    if (code !in 200..299) error("DIAL status failed (HTTP $code).")
                    val body = connection.inputStream.bufferedReader().use { it.readText().take(16_384) }
                    val xml = parseXml(body)
                    val state = xml?.documentElement?.getAttribute("state")?.takeIf { it.isNotBlank() } ?: "RUNNING"
                    DialApplicationStatus(state.uppercase(), xml?.documentElement?.let { firstText(it, "name") ?: firstText(it, "link") } ?: "YouTube app")
                } finally { connection.disconnect() }
            }
        }

    suspend fun stopDialYoutube(receiver: StreamingReceiver): Result<Unit> = closeDialYoutube(receiver)

    private fun checkedControlEndpoint(receiver: StreamingReceiver): URL {
        val endpoint = URL(receiver.avTransportControlUrl ?: error("This TV has no DLNA AVTransport endpoint."))
        require(endpoint.host == receiver.address && isPrivateAddress(endpoint.host)) {
            "TV control endpoint failed the local-network safety check."
        }
        return endpoint
    }

    private fun dialYoutubeEndpoint(receiver: StreamingReceiver): URL {
        val base = receiver.dialApplicationUrl ?: error("This TV has no DIAL application endpoint.")
        val endpoint = URL(URL(base), "YouTube")
        require(endpoint.host == receiver.address && endpoint.protocol == "http" && isPrivateAddress(endpoint.host)) {
            "TV application endpoint failed the local-network safety check."
        }
        return endpoint
    }

    private fun soap(endpoint: URL, serviceType: String, action: String, arguments: String): String {
        val connection = openPrivateHttp(endpoint, "POST") ?: error("Could not contact the DLNA TV.")
        val envelope = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${xmlEscape(serviceType)}">$arguments</u:$action></s:Body></s:Envelope>"""
        try {
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
            connection.doOutput = true
            connection.outputStream.use { it.write(envelope.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText().take(400) } }.getOrNull()
                error("DLNA $action failed (HTTP $code)${detail?.let { ": $it" } ?: ""}.")
            }
            return connection.inputStream.bufferedReader().use { it.readText().take(32_768) }
        } finally {
            connection.disconnect()
        }
    }

    private fun openPrivateHttp(url: URL, method: String): HttpURLConnection? {
        if (url.protocol != "http" || !isPrivateAddress(url.host)) return null
        return (url.openConnection() as? HttpURLConnection)?.apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = 2_500
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "ABM-AV-Player-Android/1.0")
        }
    }

    private fun parseXml(xml: String) = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        factory.newDocumentBuilder().parse(xml.byteInputStream())
    }.getOrNull()

    private fun firstText(root: Element, tag: String): String? =
        root.getElementsByTagNameNS("*", tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun childText(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", tag)
        return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isPrivateAddress(host: String): Boolean {
        // SSDP control targets must be literal IPv4 addresses; never resolve arbitrary hostnames.
        if (!host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address ?: return false
        val bytes = address.address.map { it.toInt() and 0xff }
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168) ||
            (bytes[0] == 169 && bytes[1] == 254)
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun merge(a: StreamingReceiver, b: StreamingReceiver) = a.copy(
        supportsDlna = a.supportsDlna || b.supportsDlna,
        supportsDial = a.supportsDial || b.supportsDial,
        avTransportControlUrl = a.avTransportControlUrl ?: b.avTransportControlUrl,
        avTransportServiceType = a.avTransportServiceType ?: b.avTransportServiceType,
        dialApplicationUrl = a.dialApplicationUrl ?: b.dialApplicationUrl
    )
}
