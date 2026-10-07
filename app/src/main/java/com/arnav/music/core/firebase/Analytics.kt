package com.arnav.music.core.firebase

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Privacy-first product telemetry: off by default, opt-in from Settings → Privacy.
 * Never sends track titles, artists, search text or listening history.
 */
class Analytics(private val context: Context, private val gate: FirebaseGate) {
    @Volatile private var enabled = false

    fun setEnabled(consent: Boolean) {
        enabled = consent
        if (gate.isAvailable) runCatching { FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(consent) }
    }

    fun log(event: Event, params: Map<String, String> = emptyMap()) {
        if (!enabled || !gate.isAvailable) return
        runCatching {
            val bundle = Bundle().apply { params.forEach { (k, v) -> putString(k, v.take(40)) } }
            FirebaseAnalytics.getInstance(context).logEvent(event.key, bundle)
        }
    }

    enum class Event(val key: String) {
        SEARCH("search"), PLAY_REQUESTED("play_requested"), FAVORITE_CHANGED("favorite_changed"),
        PLAYLIST_CREATED("playlist_created"), GLASS_MODE_CHANGED("glass_mode_changed"),
        AI_INVOKED("arnav_ai_invoked"), RECOMMENDATION_CLICKED("recommendation_clicked"), MOMENT_OPENED("moment_opened"),
    }
}
