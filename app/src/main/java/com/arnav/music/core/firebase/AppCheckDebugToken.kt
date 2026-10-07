package com.arnav.music.core.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import java.util.UUID

/**
 * Per-phone App Check for builds installed from GitHub. Play Integrity only vouches for installs from
 * Google Play, so once App Check is enforced for AI Logic a sideloaded APK is rejected. With this on,
 * the phone attests with the App Check debug provider instead, using a token generated on the phone
 * that its owner registers in Firebase console → App Check → Apps → ⋮ → Manage debug tokens.
 *
 * The token is a secret: it is created and kept on the device, shown only on the owner's own Settings
 * screen, never logged by the app (release builds also strip the SDK's debug logging) and never in Git.
 */
object AppCheckDebugToken {
    private const val PREFS = "app_check_mode"
    private const val KEY_ON = "use_debug_token"

    // Where the App Check debug provider keeps its secret (firebase-appcheck-debug StorageHelper). The
    // provider reuses a stored secret and only makes its own when none is there, so seeding it here
    // means the token shown in Settings is the one the provider sends.
    private const val SDK_PREFS_TEMPLATE = "com.google.firebase.appcheck.debug.store.%s"
    private const val SDK_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    /** Saves the choice and switches the provider right away (Firebase must already be initialised). */
    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        runCatching { installFor(context) }
    }

    /** This phone's debug token, created on first use. Null when Firebase isn't set up in this build. */
    fun token(context: Context): String? = runCatching {
        val key = FirebaseApp.getInstance().persistenceKey
        val prefs = context.getSharedPreferences(SDK_PREFS_TEMPLATE.format(key), Context.MODE_PRIVATE)
        prefs.getString(SDK_SECRET_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(SDK_SECRET_KEY, it).commit()
        }
    }.getOrNull()

    /** Installs the provider matching the saved choice. */
    internal fun installFor(context: Context) {
        val appCheck = FirebaseAppCheck.getInstance()
        if (isOn(context)) {
            token(context)
            appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        } else {
            appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
    }
}
