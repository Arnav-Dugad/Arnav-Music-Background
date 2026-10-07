package com.arnav.music.core.update

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arnav.music.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Background auto-update: checks GitHub every 6 h, downloads on Wi-Fi (or any network if the
 * user allows), and installs silently when Android permits and the app isn't on screen.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val updates: UpdateManager by inject()
    private val settings: SettingsRepository by inject()

    override suspend fun doWork(): Result {
        val s = settings.settings.value
        if (!s.autoUpdate || !updates.supported) return Result.success()
        val release = updates.check() ?: (updates.state.value as? UpdateState.ReadyToInstall)?.release ?: return Result.success()
        if (updates.state.value !is UpdateState.ReadyToInstall) {
            if (s.updateWifiOnly && !updates.isOnUnmeteredNetwork()) return Result.success()
            if (!updates.download(release)) return Result.retry()
        }
        val foreground = withContext(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        if (s.autoInstallUpdates && !foreground && updates.canInstallSilently() && updates.canInstallPackages()) updates.install()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork("app-update"); return }
            wm.enqueueUniquePeriodicWork(
                "app-update", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                    .build(),
            )
        }
    }
}
