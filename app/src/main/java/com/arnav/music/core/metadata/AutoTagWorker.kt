package com.arnav.music.core.metadata

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arnav.music.core.settings.SettingsRepository
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * Daily auto-tagging of on-device songs with missing info (see [AutoTagger]), on unmetered
 * networks with a healthy battery, [AutoTagger.BATCH_SIZE] songs per run. While songs are left, a
 * follow-up batch is chained a few minutes later; everything stops as soon as "Fix missing song
 * info" is turned off.
 */
class AutoTagWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val koin = runCatching { GlobalContext.get() }.getOrNull() ?: return Result.success()
        val settings = koin.get<SettingsRepository>()
        val tagger = koin.get<AutoTagger>()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!settings.settings.value.autoTagLocal) return Result.success()
        val result = runCatching {
            tagger.runBatch(AutoTagger.BATCH_SIZE) { isStopped || !settings.settings.value.autoTagLocal }
        }.getOrNull() ?: return Result.success()
        val more = !isStopped && settings.settings.value.autoTagLocal && result.remaining > 0 && !result.stoppedEarly
        if (more) enqueueNext(applicationContext, manual)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "auto-tag"
        private const val NEXT = "auto-tag-next"
        private const val KEY_MANUAL = "manual"
        private const val NEXT_DELAY_MINUTES = 3L

        private fun constraints(manual: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (manual) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Keeps the daily run scheduled while [enabled]; cancels all auto-tag work otherwise. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(NEXT)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoTagWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints(manual = false))
                .setInitialDelay(20, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Looks up songs now (any connection), batch after batch until done. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoTagWorker>()
                .setConstraints(constraints(manual = true))
                .setInputData(Data.Builder().putBoolean(KEY_MANUAL, true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NEXT, ExistingWorkPolicy.REPLACE, request)
        }

        private fun enqueueNext(context: Context, manual: Boolean) {
            val request = OneTimeWorkRequestBuilder<AutoTagWorker>()
                .setConstraints(constraints(manual))
                .setInitialDelay(NEXT_DELAY_MINUTES, TimeUnit.MINUTES)
                .setInputData(Data.Builder().putBoolean(KEY_MANUAL, manual).build())
                .build()
            // Appended, so a batch that's still running (this one) isn't cancelled.
            WorkManager.getInstance(context).enqueueUniqueWork(NEXT, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
