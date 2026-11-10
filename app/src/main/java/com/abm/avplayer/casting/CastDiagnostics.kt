package com.abm.avplayer.casting

import android.util.Log

/** Structured, privacy-conscious diagnostics for Google Cast handoff and local media delivery. */
object CastDiagnostics {
    private const val TAG = "ABMCastDiag"

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun warn(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    fun error(message: String, error: Throwable) {
        Log.e(TAG, message, error)
    }
}
