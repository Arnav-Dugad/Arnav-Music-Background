package com.arnav.music.core.importer

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Matches queued imported songs to YouTube uploads in small, quota-capped batches. Runs every 6 hours
 * while songs are waiting, once right after an import, and when the user asks for "Match more now".
 * Whatever is left when a budget is spent simply waits for the next run.
 */
class MatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val matcher: ImportMatcher by inject()

    override suspend fun doWork(): Result {
        val outcome = runCatching { matcher.runPending(manual = inputData.getBoolean(KEY_MANUAL, false)) }.getOrNull()
        // Offline or a transient error: the periodic run (or the next import) picks it up again.
        return if (outcome == null) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "import-match-periodic"
        private const val ONCE = "import-match-now"
        private const val KEY_MANUAL = "manual"

        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every 6 hours with a network connection. Safe to call repeatedly. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MatchWorker>(6, TimeUnit.HOURS).setConstraints(constraints()).build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        /** One run as soon as there's a network; [manual] when the user asked for it. */
        fun runOnce(context: Context, manual: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MatchWorker>().setConstraints(constraints()).setInputData(workDataOf(KEY_MANUAL to manual)).build(),
            )
        }

        /** App start: keep the periodic run only while something is waiting to be matched. */
        suspend fun ensureScheduled(context: Context, matcher: ImportMatcher) {
            if (matcher.hasOpenRows()) schedule(context) else cancel(context)
        }
    }
}
