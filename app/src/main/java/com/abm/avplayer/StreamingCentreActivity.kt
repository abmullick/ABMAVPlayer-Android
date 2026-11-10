package com.abm.avplayer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.MediaLoadRequestData
import org.json.JSONObject
import com.abm.avplayer.streaming.StreamingReceiver
import com.abm.avplayer.streaming.StreamingReceiverDiscovery
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private data class StreamingProvider(val name: String, val subtitle: String, val url: String, val accent: Color)

private val streamingProviders = listOf(
    StreamingProvider("YouTube", "Videos, channels and playlists", "https://www.youtube.com/", Color(0xFFFF5555)),
    StreamingProvider("Netflix", "Films and series", "https://www.netflix.com/", Color(0xFFE50914)),
    StreamingProvider("Prime Video", "Movies, series and live sport", "https://www.primevideo.com/", Color(0xFF38BDF8)),
    StreamingProvider("JioHotstar", "Entertainment and sport", "https://www.hotstar.com/in", Color(0xFF8B7CFF)),
    StreamingProvider("Sony LIV", "Shows, films and sport", "https://www.sonyliv.com/", Color(0xFF8C7BFF)),
    StreamingProvider("ZEE5", "TV shows, movies and originals", "https://www.zee5.com/", Color(0xFFB78CFF))
)

class StreamingCentreActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalOrientation = requestedOrientation
        setContent {
            var homeMode by remember { mutableStateOf(true) }
            var pageTitle by remember { mutableStateOf("Streaming Centre") }
            var currentUrl by remember { mutableStateOf("") }
            var canGoBack by remember { mutableStateOf(false) }
            var canGoForward by remember { mutableStateOf(false) }
            var pageProgress by remember { mutableIntStateOf(0) }
            var browserError by remember { mutableStateOf("") }
            var castMessage by remember { mutableStateOf("") }
            var receiverPanel by remember { mutableStateOf(false) }
            var receivers by remember { mutableStateOf<List<StreamingReceiver>>(emptyList()) }
            var selectedReceiverId by remember { mutableStateOf<String?>(null) }
            var discoveryBusy by remember { mutableStateOf(false) }
            var activeReceiver by remember { mutableStateOf<StreamingReceiver?>(null) }
            var receiverPlaybackState by remember { mutableStateOf("Connecting…") }
            var receiverConnectionState by remember { mutableStateOf("Connected") }
            var receiverReconnectBusy by remember { mutableStateOf(false) }
            var lastReconnectAttemptMs by remember { mutableStateOf(0L) }
            var receiverPosition by remember { mutableStateOf("00:00:00") }
            var receiverDuration by remember { mutableStateOf("00:00:00") }
            var receiverControlBusy by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            fun refreshReceivers() {
                discoveryBusy = true
                castMessage = "Searching your local network for Google Cast, DLNA and YouTube DIAL devices…"
                scope.launch {
                    try {
                        receivers = StreamingReceiverDiscovery.discover(this@StreamingCentreActivity)
                        if (receivers.none { it.id == selectedReceiverId }) selectedReceiverId = receivers.firstOrNull()?.id
                        castMessage = if (receivers.isEmpty()) "No DLNA or YouTube DIAL devices found. Check the same Wi-Fi." else "Found " + receivers.size + " network receiver(s)."
                    } catch (error: Exception) {
                        castMessage = "Device discovery failed: " + (error.message ?: "network error")
                    } finally { discoveryBusy = false }
                }
            }
            fun reconnectActiveReceiver() {
                val previous = activeReceiver ?: return
                receiverReconnectBusy = true
                lastReconnectAttemptMs = System.currentTimeMillis()
                receiverConnectionState = "Reconnecting…" 
                scope.launch {
                    try {
                        val found = StreamingReceiverDiscovery.discover(this@StreamingCentreActivity)
                        val refreshed = found.firstOrNull { it.address.equals(previous.address, true) && it.name.equals(previous.name, true) }
                            ?: found.firstOrNull { it.address.equals(previous.address, true) }
                        if (refreshed == null) {
                            receiverConnectionState = "Offline"
                            receiverPlaybackState = "Waiting for TV"
                            castMessage = previous.name + " was not found. Check power and Wi-Fi, then retry."
                        } else {
                            activeReceiver = refreshed
                            receiverConnectionState = "Connected"
                            castMessage = "Reconnected to " + refreshed.name
                            if (refreshed.supportsDlna) {
                                StreamingReceiverDiscovery.getDlnaPlaybackStatus(refreshed).onSuccess {
                                    receiverPlaybackState = it.state
                                    receiverPosition = it.position
                                    receiverDuration = it.duration
                                }.onFailure { receiverPlaybackState = "Connected · playback status unavailable" }
                            } else {
                                StreamingReceiverDiscovery.getDialApplicationStatus(refreshed).onSuccess {
                                    receiverPlaybackState = it.state
                                }.onFailure { receiverPlaybackState = "App status unavailable" }
                            }
                        }
                    } catch (error: Exception) {
                        receiverConnectionState = "Offline"
                        castMessage = "Reconnect failed: " + (error.message ?: "network error")
                    } finally { receiverReconnectBusy = false }
                }
            }
            fun controlSelectedReceiver(action: String) {
                val receiver = activeReceiver ?: return
                if (!receiver.supportsDlna) return
                receiverControlBusy = true
                scope.launch {
                    val result = StreamingReceiverDiscovery.controlDlna(receiver, action)
                    castMessage = result.fold({ action + " command sent to " + receiver.name }, { "TV control failed: " + (it.message ?: "receiver error") })
                    receiverControlBusy = false
                    StreamingReceiverDiscovery.getDlnaPlaybackStatus(receiver).onSuccess {
                        receiverPlaybackState = it.state
                        receiverPosition = it.position
                        receiverDuration = it.duration
                    }
                }
            }

            fun seekSelectedReceiver(deltaSeconds: Long) {
                val receiver = activeReceiver ?: return
                if (!receiver.supportsDlna) return
                fun toSeconds(value: String): Long = value.split(":").takeIf { it.size == 3 }?.let { parts ->
                    (parts[0].toLongOrNull() ?: 0L) * 3600 + (parts[1].toLongOrNull() ?: 0L) * 60 + (parts[2].toLongOrNull() ?: 0L)
                } ?: 0L
                val target = (toSeconds(receiverPosition) + deltaSeconds).coerceAtLeast(0)
                val formatted = "%02d:%02d:%02d".format(target / 3600, (target % 3600) / 60, target % 60)
                receiverControlBusy = true
                scope.launch {
                    val result = StreamingReceiverDiscovery.seekDlna(receiver, formatted)
                    castMessage = result.fold({ "Seek sent to " + receiver.name }, { "Seek failed: " + (it.message ?: "receiver error") })
                    receiverControlBusy = false
                }
            }

            LaunchedEffect(activeReceiver?.id) {
                val receiverId = activeReceiver?.id ?: return@LaunchedEffect
                while (activeReceiver?.id == receiverId) {
                    val receiver = activeReceiver ?: break
                    val statusOk = if (receiver.supportsDlna) {
                        StreamingReceiverDiscovery.getDlnaPlaybackStatus(receiver).onSuccess {
                            receiverConnectionState = "Connected"
                            receiverPlaybackState = it.state
                            receiverPosition = it.position
                            receiverDuration = it.duration
                        }.isSuccess
                    } else {
                        StreamingReceiverDiscovery.getDialApplicationStatus(receiver).onSuccess {
                            receiverConnectionState = "Connected"
                            receiverPlaybackState = it.state
                        }.isSuccess
                    }
                    if (!statusOk) {
                        receiverConnectionState = "Connection lost · retrying"
                        receiverPlaybackState = "Status unavailable"
                        val now = System.currentTimeMillis()
                        if (!receiverReconnectBusy && now - lastReconnectAttemptMs >= 15_000L) {
                            lastReconnectAttemptMs = now
                            val found = runCatching { StreamingReceiverDiscovery.discover(this@StreamingCentreActivity) }.getOrNull().orEmpty()
                            val refreshed = found.firstOrNull { it.address.equals(receiver.address, true) && it.name.equals(receiver.name, true) }
                                ?: found.firstOrNull { it.address.equals(receiver.address, true) }
                            if (refreshed != null) {
                                activeReceiver = refreshed
                                receiverConnectionState = "Reconnected"
                            } else {
                                receiverConnectionState = "Offline · retrying"
                            }
                        }
                    }
                    delay(5_000)
                }
            }

            SideEffect {
                currentUrlState = { currentUrl = it }
                titleState = { pageTitle = it }
                navigationState = { back, forward -> canGoBack = back; canGoForward = forward }
                progressState = { pageProgress = it }
                pageErrorState = { browserError = it }
            }

            fun openProvider(provider: StreamingProvider) {
                homeMode = false
                browserError = ""
                browser?.loadUrl(provider.url)
            }

            BackHandler(enabled = customView != null || !homeMode) {
                if (customView != null) hideCustomView()
                else if (canGoBack) browser?.goBack()
                else {
                    homeMode = true
                    pageTitle = "Streaming Centre"
                    currentUrl = ""
                    browser?.stopLoading()
                    browser?.loadUrl("about:blank")
                }
            }

            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Canvas)) {
                    Row(
                        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (!homeMode && canGoBack) browser?.goBack()
                            else if (!homeMode) {
                                homeMode = true
                                pageTitle = "Streaming Centre"
                                currentUrl = ""
                                browser?.stopLoading()
                                browser?.loadUrl("about:blank")
                            } else finish()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        IconButton(onClick = { if (!homeMode && canGoForward) browser?.goForward() },
                            enabled = !homeMode && canGoForward) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Forward",
                                tint = if (!homeMode && canGoForward) Color.White else Muted)
                        }
                        IconButton(onClick = { if (!homeMode) browser?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White)
                        }
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text("ABM  ·  STREAMING", color = Cyan, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Text(pageTitle, color = Color.White, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = {
                            homeMode = true
                            pageTitle = "Streaming Centre"
                            currentUrl = ""
                            browserError = ""
                            browser?.stopLoading()
                            browser?.loadUrl("about:blank")
                        }) {
                            Icon(Icons.Default.Home, contentDescription = "Home", tint = Color.White)
                        }
                        AndroidView(
                            factory = { context ->
                                MediaRouteButton(context).also { button ->
                                    runCatching { CastButtonFactory.setUpMediaRouteButton(context, button) }
                                }
                            },
                            modifier = Modifier.size(42.dp)
                        )
                        IconButton(onClick = { receiverPanel = true; refreshReceivers() }) {
                            Icon(Icons.Default.Cast, contentDescription = "Find TVs", tint = Cyan)
                        }
                        IconButton(onClick = { openInBrowser() }, enabled = !homeMode && currentUrl.startsWith("https://")) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser", tint = Color.White)
                        }
                    }
                    if (!homeMode && pageProgress in 1..99) {
                        LinearProgressIndicator(progress = { pageProgress / 100f },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Cyan, trackColor = Panel)
                    }
                    if (castMessage.isNotBlank()) {
                        Text(castMessage, color = Cyan, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().background(Raised).padding(horizontal = 14.dp, vertical = 7.dp))
                    }
                    if (activeReceiver != null) {
                        val receiver = activeReceiver!!
                        Column(Modifier.fillMaxWidth().background(Raised).padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("TV · " + receiver.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Connection: " + receiverConnectionState, color = if (receiverConnectionState == "Connected" || receiverConnectionState == "Reconnected") Cyan else Color(0xFFFFB86B), fontSize = 10.sp)
                                    Text("Playback: " + receiverPlaybackState + if (receiver.supportsDlna) "  ·  " + receiverPosition + " / " + receiverDuration else "  ·  DIAL app", color = Muted, fontSize = 10.sp)
                                }
                                TextButton(onClick = { reconnectActiveReceiver() }, enabled = !receiverReconnectBusy) { Text(if (receiverReconnectBusy) "…" else "Reconnect", color = Cyan, fontSize = 10.sp) }
                                TextButton(onClick = { activeReceiver = null; receiverPlaybackState = "Disconnected"; receiverConnectionState = "Disconnected" }) { Text("Disconnect", color = Muted, fontSize = 10.sp) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (receiver.supportsDlna) {
                                    TextButton(onClick = { seekSelectedReceiver(-30) }, enabled = !receiverControlBusy && !receiverConnectionState.startsWith("Offline")) { Text("-30s", color = Cyan) }
                                    if (receiverPlaybackState.equals("PLAYING", true)) {
                                        TextButton(onClick = { controlSelectedReceiver("Pause") }, enabled = !receiverControlBusy && !receiverConnectionState.startsWith("Offline")) { Text("Pause", color = Cyan) }
                                    } else {
                                        TextButton(onClick = { controlSelectedReceiver("Play") }, enabled = !receiverControlBusy && receiverConnectionState != "Offline") { Text("Resume / Play", color = Cyan) }
                                    }
                                    TextButton(onClick = { controlSelectedReceiver("Stop") }, enabled = !receiverControlBusy && receiverConnectionState != "Offline") { Text("Stop", color = Cyan) }
                                    TextButton(onClick = { seekSelectedReceiver(30) }, enabled = !receiverControlBusy && receiverConnectionState != "Offline") { Text("+30s", color = Cyan) }
                                } else if (receiver.supportsDial) {
                                    TextButton(onClick = {
                                        val videoId = this@StreamingCentreActivity.currentYoutubeVideoId()
                                        if (videoId != null) {
                                            receiverControlBusy = true
                                            scope.launch {
                                                StreamingReceiverDiscovery.launchYoutubeOnDial(receiver, videoId)
                                                    .onSuccess { receiverPlaybackState = "RUNNING"; receiverConnectionState = "Connected"; castMessage = "YouTube relaunched on " + receiver.name }
                                                    .onFailure { castMessage = "Launch failed: " + (it.message ?: "receiver error") }
                                                receiverControlBusy = false
                                            }
                                        }
                                    }, enabled = !receiverControlBusy && this@StreamingCentreActivity.currentYoutubeVideoId() != null) { Text("Launch YouTube", color = Cyan) }
                                    TextButton(onClick = {
                                        receiverControlBusy = true
                                        scope.launch {
                                            StreamingReceiverDiscovery.stopDialYoutube(receiver).onSuccess {
                                                receiverPlaybackState = "STOPPED"
                                                receiverConnectionState = "Connected"
                                                castMessage = "YouTube app stopped on " + receiver.name
                                            }.onFailure { castMessage = "Stop failed: " + (it.message ?: "receiver error") }
                                            receiverControlBusy = false
                                        }
                                    }, enabled = !receiverControlBusy) { Text("Stop YouTube", color = Cyan) }
                                }
                            }
                            if (receiver.supportsDial && !receiver.supportsDlna) {
                                Text("DIAL supports app launch, status and stop. Generic pause/resume is not part of the DIAL protocol; use the TV remote or the provider's own Cast controls.", color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { context -> browser ?: createBrowser(context).also { browser = it } },
                            modifier = Modifier.fillMaxSize(),
                            update = { if (it !== browser) browser = it }
                        )
                        if (homeMode) {
                            Column(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(listOf(Color(0xFF111827), Canvas, Canvas))
                                ).padding(18.dp)
                            ) {
                                Text("YOUR STREAMING WORLD", color = Color.White, fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text("Open an official service and sign in with your own account.",
                                    color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(18.dp))
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(streamingProviders) { provider ->
                                        Column(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                                .background(Panel).clickable { openProvider(provider) }
                                                .padding(15.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                                                .background(provider.accent.copy(alpha = .18f)),
                                                contentAlignment = Alignment.Center) {
                                                Text(provider.name.take(1), color = provider.accent,
                                                    fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                            }
                                            Text(provider.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                            Text(provider.subtitle, color = Muted, fontSize = 10.sp,
                                                minLines = 2)
                                            Text("OPEN SERVICE  →", color = Cyan, fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("Provider subscriptions and availability are managed by each service.",
                                    color = Muted, fontSize = 10.sp)
                            }
                        } else if (browserError.isNotBlank()) {
                            Column(Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("This page could not be loaded", color = Color.White,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Text(browserError, color = Muted, fontSize = 12.sp)
                                TextButton(onClick = { browser?.reload() }) { Text("Try again", color = Cyan) }
                            }
                        }
                    }
                    if (!homeMode) {
                        Row(
                            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(currentUrl, color = Muted, fontSize = 10.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = { openInBrowser() }) { Text("Browser", color = Cyan, fontSize = 11.sp) }
                        }
                    }
                }
            }
                if (receiverPanel) {
                    Dialog(onDismissRequest = { receiverPanel = false }) {
                        Surface(Modifier.fillMaxWidth().heightIn(max = 620.dp), shape = RoundedCornerShape(22.dp), color = Panel) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("FIND TVs", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                        Text("Google Cast · DLNA / UPnP · YouTube DIAL", color = Muted, fontSize = 10.sp)
                                    }
                                    TextButton(onClick = { refreshReceivers() }, enabled = !discoveryBusy) { Text("Refresh", color = Cyan) }
                                }
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Raised).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Google Cast / Chromecast", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text("Use Google's official receiver picker", color = Muted, fontSize = 10.sp)
                                    }
                                    AndroidView(factory = { context -> MediaRouteButton(context).also { button -> runCatching { CastButtonFactory.setUpMediaRouteButton(context, button) } } }, modifier = Modifier.size(48.dp))
                                }
                                OutlinedButton(onClick = { requestVideoCast { castMessage = it }; receiverPanel = false }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)) { Text("Cast current video with Google Cast") }
                                if (discoveryBusy && receivers.isEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CircularProgressIndicator(Modifier.size(18.dp), color = Cyan, strokeWidth = 2.dp)
                                        Text("Searching local network…", color = Muted, fontSize = 12.sp)
                                    }
                                }
                                if (receivers.isEmpty() && !discoveryBusy) Text("No DLNA or DIAL receivers found. Keep the TV awake and check that both devices use the same Wi-Fi network.", color = Muted, fontSize = 12.sp)
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    lazyItems(receivers, key = { it.id }) { receiver ->
                                        val selected = receiver.id == selectedReceiverId
                                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) Color(0xFF263B4A) else Raised).clickable { selectedReceiverId = receiver.id }.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(receiver.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                Text(receiver.protocolLabel + " · " + receiver.address, color = Cyan, fontSize = 10.sp)
                                            }
                                            RadioButton(selected = selected, onClick = { selectedReceiverId = receiver.id })
                                        }
                                    }
                                }
                                val selectedReceiver = receivers.firstOrNull { it.id == selectedReceiverId }
                                if (selectedReceiver?.supportsDlna == true) {
                                    Button(onClick = {
                                        activeReceiver = selectedReceiver
                                        receiverPlaybackState = "Starting…"
                                        requestDlnaCast(selectedReceiver, scope) { castMessage = it }
                                        receiverPanel = false
                                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23675F))) { Text("Play current video on selected TV") }
                                }
                                if (selectedReceiver?.supportsDial == true) {
                                    OutlinedButton(onClick = {
                                        activeReceiver = selectedReceiver
                                        receiverPlaybackState = "Launching…"
                                        launchDialYoutube(selectedReceiver, scope) { castMessage = it }
                                        receiverPanel = false
                                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)) { Text("Launch YouTube on selected TV") }
                                }
                                Text("DRM-protected or encrypted streams cannot be extracted. Use the provider's own Cast control for those services.", color = Muted, fontSize = 10.sp)
                                TextButton(onClick = { receiverPanel = false }, modifier = Modifier.align(Alignment.End)) { Text("Close", color = Cyan) }
                            }
                        }
                    }
                }
        }
    }

    private fun createBrowser(context: android.content.Context): WebView {
        CookieManager.getInstance().setAcceptCookie(true)
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                settings.safeBrowsingEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme.equals("https", true) && isAllowedWebDestination(uri)) return false
                    if (uri.scheme.equals("http", true)) {
                        // Never silently downgrade a provider session to clear-text HTTP.
                        return true
                    }
                    if (uri.scheme == "intent") return true
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (url == "about:blank" || (url.startsWith("https://") && isAllowedWebDestination(Uri.parse(url)))) {
                        currentUrlState?.invoke(url)
                        pageErrorState?.invoke("")
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == "about:blank" || (url.startsWith("https://") && isAllowedWebDestination(Uri.parse(url)))) {
                        currentUrlState?.invoke(url)
                        titleState?.invoke(view.title ?: url)
                        navigationState?.invoke(view.canGoBack(), view.canGoForward())
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) pageErrorState?.invoke(error.description?.toString() ?: "Network error")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    pageErrorState?.invoke("The site's secure connection could not be verified.")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progressState?.invoke(newProgress) }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) { showCustomView(view, callback) }
                override fun onHideCustomView() { hideCustomView() }
            }
        }
    }

    // Compose state callbacks are assigned by the Activity content before the browser is first navigated.
    private var currentUrlState: ((String) -> Unit)? = null
    private var titleState: ((String) -> Unit)? = null
    private var navigationState: ((Boolean, Boolean) -> Unit)? = null
    private var progressState: ((Int) -> Unit)? = null
    private var pageErrorState: ((String) -> Unit)? = null

    private fun isAllowedWebDestination(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (host.startsWith("10.") || host.startsWith("127.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return false
        if (host.startsWith("172.")) {
            val second = host.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return false
        }
        if (host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")) return false
        return true
    }

    private fun openInBrowser() {
        val url = browser?.url ?: return
        if (url.startsWith("https://")) runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun requestDlnaCast(receiver: StreamingReceiver, scope: kotlinx.coroutines.CoroutineScope, report: (String) -> Unit) {
        val web = browser ?: return report("Open a streaming page and start a video first.")
        val script = """JSON.stringify((() => { const v=[...document.querySelectorAll('video')].filter(x=>x.currentSrc && (x.currentSrc.startsWith('https://') || x.currentSrc.startsWith('http://'))).sort((a,b)=>(b.videoWidth*b.videoHeight)-(a.videoWidth*a.videoHeight))[0]; return v ? {url:v.currentSrc,type:v.getAttribute('type')||'',title:document.title||'Streaming video'} : {url:'',type:'',title:document.title||''}; })())"""
        web.evaluateJavascript(script) { encoded ->
            runCatching {
                val payload = JSONObject(org.json.JSONArray("[$encoded]").getString(0))
                val url = payload.optString("url")
                require(url.startsWith("https://")) { "This page does not expose a direct video URL. Use the provider's own Cast button." }
                val mime = payload.optString("type").ifBlank {
                    when (Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
                        "m3u8" -> "application/vnd.apple.mpegurl"
                        "mpd" -> "application/dash+xml"
                        "mp4", "m4v" -> "video/mp4"
                        "webm" -> "video/webm"
                        else -> "video/mp4"
                    }
                }
                val title = payload.optString("title", "Streaming video")
                scope.launch {
                    val result = StreamingReceiverDiscovery.playOnDlna(receiver, url, mime, title)
                    report(result.fold(
                        { "DLNA play command sent to ${receiver.name}. The TV must be able to reach the media URL." },
                        { "DLNA handoff failed: ${it.message ?: "receiver error"}" }
                    ))
                }
            }.onFailure { report(it.message ?: "Could not prepare DLNA playback.") }
        }
    }

    private fun currentYoutubeVideoId(): String? {
        val page = browser?.url.orEmpty()
        return Regex("(?:[?&]v=|youtu\\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})")
            .find(page)?.groupValues?.getOrNull(1)
    }
    private fun launchDialYoutube(receiver: StreamingReceiver, scope: kotlinx.coroutines.CoroutineScope, report: (String) -> Unit) {
        val videoId = currentYoutubeVideoId()
        if (videoId == null) return report("Open a YouTube video first, then choose Launch YouTube on selected TV.")
        scope.launch {
            val result = StreamingReceiverDiscovery.launchYoutubeOnDial(receiver, videoId)
            report(result.fold(
                { "YouTube launch request sent to ${receiver.name}." },
                { "YouTube DIAL launch failed: ${it.message ?: "receiver error"}" }
            ))
        }
    }
    private fun requestVideoCast(report: (String) -> Unit) {
        val web = browser ?: return report("Open a streaming page and start a video first.")
        val castSession = runCatching { CastContext.getSharedInstance(this).sessionManager.currentCastSession }.getOrNull()
        val remote = castSession?.remoteMediaClient
        if (remote == null) return report("Choose a Google Cast receiver using the Cast icon first.")
        val script = """JSON.stringify((() => { const v=[...document.querySelectorAll('video')].filter(x=>x.currentSrc && x.currentSrc.startsWith('https://')).sort((a,b)=>(b.videoWidth*b.videoHeight)-(a.videoWidth*a.videoHeight))[0]; return v ? {url:v.currentSrc,type:v.getAttribute('type')||'',title:document.title||'Streaming video'} : {url:'',type:'',title:document.title||''}; })())"""
        web.evaluateJavascript(script) { encoded ->
            runCatching {
                val decodedPayload = org.json.JSONArray("[$encoded]").getString(0)
                val payload = JSONObject(decodedPayload)
                val url = payload.optString("url")
                require(url.startsWith("https://")) { "No directly addressable HTTPS video URL is exposed by this page. Use the provider's own Cast button where available." }
                val type = payload.optString("type").ifBlank {
                    when (Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
                        "m3u8" -> "application/x-mpegURL"
                        "mpd" -> "application/dash+xml"
                        "mp4", "m4v" -> "video/mp4"
                        "webm" -> "video/webm"
                        else -> "video/*"
                    }
                }
                val metadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(CastMediaMetadata.KEY_TITLE, payload.optString("title", "Streaming video"))
                }
                val info = MediaInfo.Builder(url).setContentType(type)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(metadata).build()
                remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
                    .setResultCallback { result ->
                        report(if (result.status.isSuccess) "Cast request sent. Receiver compatibility and provider access still apply."
                        else "Receiver rejected the direct video URL (Cast error ${result.status.statusCode}).")
                    }
            }.onFailure { report(it.message ?: "Could not hand off this video.") }
        }
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) return callback.onCustomViewHidden()
        customView = view
        customViewCallback = callback
        (window.decorView as? FrameLayout)?.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideCustomView() {
        val view = customView ?: return
        (window.decorView as? FrameLayout)?.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        hideCustomView()
        browser?.apply { stopLoading(); webChromeClient = null; destroy() }
        browser = null
        super.onDestroy()
    }
}
