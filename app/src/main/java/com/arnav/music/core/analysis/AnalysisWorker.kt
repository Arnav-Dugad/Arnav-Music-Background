package com.arnav.music.core.analysis

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.AudioFeaturesEntity
import com.arnav.music.core.local.LocalMediaSource
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.audio.AudioDsp
import com.arnav.music.domain.lyrics.VocalActivityMeter
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * Analyzes local songs on device (tempo, key, loudness, energy envelope, intro/outro, and a vocal
 * activity curve for auto-timed lyrics, see [VocalActivityStore]) in small batches.
 * Periodic runs only happen while charging; [runNow] skips that constraint. Nothing leaves the phone.
 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val koin = runCatching { GlobalContext.get() }.getOrNull() ?: return Result.success()
        val settings = koin.get<SettingsRepository>()
        val db = koin.get<ArnavDatabase>()
        val local = koin.get<LocalMediaSource>()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val enabledAtStart = settings.settings.value.analyzeLocalAudio
        if (!manual && !enabledAtStart) return Result.success()
        if (!local.hasPermission()) return Result.success()
        // Stop when the user turns analysis off mid-run (a manual run started while off keeps going).
        fun turnedOff() = !settings.settings.value.analyzeLocalAudio && (!manual || enabledAtStart)

        val dao = db.audioFeatures()
        val vocals = VocalActivityStore(applicationContext)
        val done = dao.analyzedIds(AudioFeatures.VERSION).toHashSet()
        val pending = local.tracks().first().filter { it.id.value !in done && it.playbackRef.isNotBlank() }
        val batch = pending.take(MAX_PER_RUN)
        var processed = 0
        for (track in batch) {
            if (isStopped || turnedOff()) break
            val row: AudioFeaturesEntity? = try {
                withContext(Dispatchers.Default) {
                    ensureActive()
                    analyze(track, this, vocals)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedRow(track.id.value)
            }
            if (row == null) break // stopped mid-track: don't record anything
            dao.upsert(row)
            processed++
        }
        if (manual && !isStopped && !turnedOff() && processed == batch.size && pending.size > batch.size) {
            enqueueOneTime(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    /** Returns null only when the work was stopped while decoding. */
    private fun analyze(track: Track, scope: CoroutineScope, vocals: VocalActivityStore): AudioFeaturesEntity? {
        // Mid/side vocal-band meter, fed while decoding (the side channel is never stored).
        val stereo = object : StereoSink {
            var meter: VocalActivityMeter? = null
            override fun begin(sampleRate: Int) { meter = VocalActivityMeter(sampleRate, AudioFeatures.ENVELOPE_STEP_MS) }
            override fun push(mid: Float, side: Float) { meter?.push(mid, side) }
        }
        val decoded = AudioDecoder.decode(
            applicationContext, Uri.parse(track.playbackRef),
            isCancelled = { isStopped || !scope.isActive },
            stereo = stereo,
        )
        if (isStopped || !scope.isActive) return null
        if (decoded == null || decoded.length < decoded.sampleRate * MIN_SECONDS) return failedRow(track.id.value)
        scope.ensureActive()
        // Stereo music is mostly correlated, so the mono mix reads ~3 dB below a stereo BS.1770 measurement.
        val offset = if (decoded.sourceChannels >= 2) 3.01f else 0f
        val a = AudioDsp.analyze(
            decoded.samples, decoded.sampleRate, decoded.length,
            envelopeStepMs = AudioFeatures.ENVELOPE_STEP_MS, loudnessOffsetDb = offset,
        )
        // Only the first AudioDecoder.MAX_SECONDS are decoded: a quiet stretch at the cut isn't the song's outro.
        val decodedMs = decoded.length * 1000L / decoded.sampleRate
        val knownMs = track.durationMs ?: 0L
        val truncated = decodedMs >= (AudioDecoder.MAX_SECONDS - 1) * 1000L || (knownMs > 0L && knownMs > decodedMs + 3_000L)
        stereo.meter?.let { m -> vocals.write(track.id.value, m.finish(), m.stepMs) }
        return AudioFeaturesEntity(
            trackId = track.id.value,
            bpm = a.tempo.bpm,
            beatOffsetMs = a.tempo.beatOffsetMs,
            loudnessDb = a.loudnessLufs,
            energy = a.energy,
            envelope = a.envelope,
            analyzedAt = System.currentTimeMillis(),
            version = AudioFeatures.VERSION,
            ok = true,
            musicalKey = a.key.key,
            introMs = a.sections.introMs,
            outroMs = if (truncated) 0L else a.sections.outroMs,
        )
    }

    private fun failedRow(trackId: String) = AudioFeaturesEntity(
        trackId = trackId, bpm = 0f, beatOffsetMs = 0L, loudnessDb = AudioDsp.SILENCE_LUFS, energy = 0f,
        envelope = ByteArray(0), analyzedAt = System.currentTimeMillis(), version = AudioFeatures.VERSION, ok = false,
    )

    companion object {
        private const val PERIODIC = "audio-analysis"
        private const val NOW = "audio-analysis-now"
        private const val KEY_MANUAL = "manual"
        private const val MAX_PER_RUN = 60
        private const val MIN_SECONDS = 5

        /** Keeps a 12-hourly, charging-only analysis pass scheduled (or cancels it). */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(PERIODIC); return }
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<AnalysisWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Analyzes the library now (not only while charging), batch after batch until done. */
        fun runNow(context: Context) = enqueueOneTime(context, ExistingWorkPolicy.KEEP)

        private fun enqueueOneTime(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInputData(Data.Builder().putBoolean(KEY_MANUAL, true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
        }
    }
}
