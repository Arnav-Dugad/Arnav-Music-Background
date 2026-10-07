package com.arnav.music.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Receives PackageInstaller session results for self-updates. */
class InstallResultReceiver : BroadcastReceiver(), KoinComponent {
    private val updates: UpdateManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        updates.onInstallResult(status, message, confirm)
    }
}
