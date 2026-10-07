package com.arnav.music.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arnav.music.MainActivity
import com.arnav.music.R
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.intelligence.RecapPeriod
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Optional weekly recap, computed entirely on device. Off by default; never promotional. */
class RecapWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val settings: SettingsRepository by inject()
    private val intelligence: IntelligenceRepository by inject()
    private val library: LibraryRepository by inject()

    override suspend fun doWork(): Result {
        if (!settings.settings.value.weeklyRecapNotification) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val recap = intelligence.recap(RecapPeriod.WEEK)
        if (recap.isEmpty) return Result.success()
        val topArtist = recap.topArtists.firstOrNull()?.first?.let { k -> library.tracksByArtist(k).firstOrNull()?.artist }
        ensureChannel(applicationContext)
        val open = PendingIntent.getActivity(
            applicationContext, 7, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("arnavmusic://insights"), applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildString {
            append("${recap.minutesListened} minutes this week")
            topArtist?.let { append(" · most played: $it") }
            append(" · ${recap.discoveryPercent}% new to you")
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_arnav)
            .setContentTitle("Your week in music")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(41, n) }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "recaps"
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening recaps", NotificationManager.IMPORTANCE_LOW).apply { description = "Optional weekly summary of your listening" })
        }
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork("weekly-recap"); return }
            wm.enqueueUniquePeriodicWork("weekly-recap", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RecapWorker>(7, TimeUnit.DAYS).build())
        }
    }
}
