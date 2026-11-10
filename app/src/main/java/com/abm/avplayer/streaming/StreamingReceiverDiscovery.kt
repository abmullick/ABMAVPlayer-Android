package com.abm.avplayer.streaming

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL
import java.nio.charset.StandardCharsets
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
            if (supportsDial) "YouTube DIAL" else null
        ).joinToString(" + ")
}

object StreamingReceiverDiscovery {
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
            val locations = linkedMapOf<String, String>()
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
                        if (!location.isNullOrBlank()) {
                            val parsed = runCatching { URL(location) }.getOrNull() ?: continue
                            if (parsed.protocol.equals("http", true) && isPrivateAddress(parsed.host)) {
                                locations.putIfAbsent(location, parsed.host)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Continue until the discovery window expires.
                    }
                }
            }

            val found = linkedMapOf<String, StreamingReceiver>()
            locations.keys.forEach { location ->
                runCatching { readReceiverDescription(location) }.getOrNull()?.let { receiver ->
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

    private fun readReceiverDescription(location: String): StreamingReceiver? {
        val connection = openPrivateHttp(URL(location), "GET") ?: return null
        val xml: String
        val applicationHeader: String?
        try {
            if (connection.responseCode !in 200..299) return null
            xml = connection.inputStream.bufferedReader().use { it.readText().take(512_000) }
            applicationHeader = connection.getHeaderField("Application-URL")
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
            }
        }

    suspend fun launchYoutubeOnDial(receiver: StreamingReceiver, videoId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Could not identify a valid YouTube video ID on this page." }
                val base = receiver.dialApplicationUrl ?: error("This TV does not advertise a DIAL application endpoint.")
                val endpoint = URL(URL(base), "YouTube")
                require(endpoint.host.equals(receiver.address, true) && endpoint.protocol == "http" && isPrivateAddress(endpoint.host)) {
                    "The TV application endpoint failed the local-network safety check."
                }
                val connection = openPrivateHttp(endpoint, "POST") ?: error("Could not contact the TV's YouTube app.")
                try {
                    connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    connection.doOutput = true
                    connection.outputStream.use { it.write("v=$videoId".toByteArray(StandardCharsets.UTF_8)) }
                    if (connection.responseCode !in listOf(200, 201, 202)) {
                        error("The TV rejected YouTube launch (HTTP ${connection.responseCode}).")
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }

    private fun soap(endpoint: URL, serviceType: String, action: String, arguments: String) {
        val connection = openPrivateHttp(endpoint, "POST") ?: error("Could not contact the DLNA TV.")
        val envelope = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${xmlEscape(serviceType)}">$arguments</u:$action></s:Body></s:Envelope>"""
        try {
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
            connection.doOutput = true
            connection.outputStream.use { it.write(envelope.toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText().take(400) } }.getOrNull()
                error("DLNA $action failed (HTTP ${connection.responseCode})${detail?.let { ": $it" } ?: ""}.")
            }
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
        if (!host.matches(Regex("""\\d{1,3}(?:\\.\\d{1,3}){3}"""))) return false
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
