package com.arnav.music.core.firebase

import android.content.Context

/**
 * Release builds attest with Play Integrity. Play only vouches for installs from Google Play, so a
 * sideloaded APK fails attestation once App Check is enforced; [AppCheckDebugToken] lets the owner's
 * own phone attest with a registered debug token instead.
 */
internal object AppCheckInstaller {
    const val PROVIDER = "Play Integrity"
    fun install(context: Context) = AppCheckDebugToken.installFor(context)
}
