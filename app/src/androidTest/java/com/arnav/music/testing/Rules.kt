package com.arnav.music.testing

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.test.rule.GrantPermissionRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Runtime permissions the app asks for, granted up front so no system dialog ever covers the app
 * (local playback asks for notifications the first time it plays).
 */
fun grantAppPermissions(): GrantPermissionRule {
    val perms = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return GrantPermissionRule.grant(*perms)
}

/**
 * Logs test boundaries and any uncaught exception (with a greppable tag) before the default
 * handler kills the process, so crashes are easy to find in the CI logcat dump.
 */
class CrashLoggerRule : TestWatcher() {
    private var previous: Thread.UncaughtExceptionHandler? = null

    override fun starting(description: Description) {
        Log.i(TAG, "=== START ${description.displayName}")
        previous = Thread.getDefaultUncaughtExceptionHandler()
        val chained = previous
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e("ArnavTestCrash", "Uncaught exception on ${thread.name} during ${description.displayName}", error)
            if (chained != null) chained.uncaughtException(thread, error) else throw error
        }
    }

    override fun finished(description: Description) {
        Thread.setDefaultUncaughtExceptionHandler(previous)
        Log.i(TAG, "=== END ${description.displayName}")
    }

    override fun failed(e: Throwable, description: Description) {
        Log.e(TAG, "=== FAILED ${description.displayName}", e)
    }
}
