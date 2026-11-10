package com.abm.avplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun connectMediaController(context: Context): MediaController =
    suspendCancellableCoroutine { continuation ->
        val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
        val future: ListenableFuture<MediaController> =
            MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                if (continuation.isActive) continuation.resume(controller)
                else MediaController.releaseFuture(future)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }, context.mainExecutor)
        continuation.invokeOnCancellation {
            MediaController.releaseFuture(future)
        }
    }
