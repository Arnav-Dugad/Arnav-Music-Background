package com.arnav.music.core.firebase

import android.content.Context
import com.arnav.music.BuildConfig
import com.arnav.music.core.common.Log
import com.google.firebase.FirebaseApp

/**
 * Single answer to "is the cloud available?". When the build has no google-services.json the
 * app runs in local-only mode and every Firebase-backed feature degrades gracefully.
 */
class FirebaseGate(private val context: Context) {
    @Volatile private var initialised = false
    @Volatile private var available = false

    val appCheckProvider: String get() = if (AppCheckDebugToken.isOn(context)) "Debug token" else AppCheckInstaller.PROVIDER

    val isAvailable: Boolean
        get() {
            ensure()
            return available
        }

    @Synchronized
    fun ensure() {
        if (initialised) return
        initialised = true
        available = runCatching {
            if (!BuildConfig.FIREBASE_CONFIGURED) return@runCatching false
            val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
            if (app != null) {
                runCatching {
                    // App Check (free) protects Firestore / AI Logic from abuse by non-genuine clients.
                    AppCheckInstaller.install(context)
                }
            }
            app != null
        }.onFailure { Log.w("Firebase init failed", it) }.getOrDefault(false)
    }
}
