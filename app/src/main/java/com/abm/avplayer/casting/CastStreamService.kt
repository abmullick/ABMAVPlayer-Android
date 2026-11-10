package com.abm.avplayer.casting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.abm.avplayer.MainActivity
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

/**
 * Owns the temporary LAN endpoints used by Google Cast.
 *
 * This service is started as a user-initiated media-playback foreground service so the
 * HTTP server is not tied to MainActivity's lifecycle while the receiver is playing.
 */
class CastStreamService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): CastStreamService = this@CastStreamService
    }

    private val binder = LocalBinder()
    private var singleServer: LocalMediaHttpServer? = null
    private var queueServer: LocalMediaQueueHttpServer? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var castContext: CastContext? = null
    private var castSessionListener: SessionManagerListener<CastSession>? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching { CastContext.getSharedInstance(this) }.onSuccess { context ->
            castContext = context
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) = Unit
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
                override fun onSessionEnded(session: CastSession, error: Int) = stopStreaming()
                override fun onSessionStartFailed(session: CastSession, error: Int) = stopStreaming()
                override fun onSessionResumeFailed(session: CastSession, error: Int) = stopStreaming()
                override fun onSessionStarting(session: CastSession) = Unit
                override fun onSessionEnding(session: CastSession) = Unit
                override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
                override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            }
            castSessionListener = listener
            context.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    fun startSingleServer(uri: android.net.Uri, contentType: String): String {
        clearServers()
        val server = LocalMediaHttpServer(contentResolver, uri, contentType)
        singleServer = server
        return runCatching { server.start() }.onFailure {
            singleServer = null
            releaseLocks()
        }.getOrThrow().also { acquireLocks() }
    }

    fun startQueueServer(sources: List<LocalCastMediaSource>): LocalMediaQueueHttpServer {
        clearServers()
        val server = LocalMediaQueueHttpServer(contentResolver, sources)
        queueServer = server
        runCatching { server.start() }.onFailure {
            queueServer = null
            releaseLocks()
        }.getOrThrow()
        acquireLocks()
        return server
    }

    val hasActiveEndpoint: Boolean
        get() = singleServer != null || queueServer != null

    fun clearServers() {
        singleServer?.close()
        singleServer = null
        queueServer?.close()
        queueServer = null
        releaseLocks()
    }

    fun stopStreaming() {
        clearServers()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        if (cpuLock?.isHeld != true) {
            cpuLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ABMAVPlayer:CastStream")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock?.isHeld != true) {
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ABMAVPlayer:CastStream")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { if (cpuLock?.isHeld == true) cpuLock?.release() }
        cpuLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps local media available to the selected Cast receiver"
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ABM AV Player is casting")
            .setContentText("Keeping your selected media available to the TV")
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        castSessionListener?.let { listener ->
            castContext?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        }
        castSessionListener = null
        castContext = null
        clearServers()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "abm_cast_stream"
        private const val NOTIFICATION_ID = 4207
    }
}
