package com.arnav.music.core.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arnav.music.BuildConfig
import com.arnav.music.MainActivity
import com.arnav.music.R
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.Log
import com.arnav.music.domain.format.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@Serializable
private data class GhAsset(val name: String = "", val size: Long = 0, @SerialName("browser_download_url") val url: String = "")

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String = "",
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sumsUrl: String?,
    val notes: String,
    val htmlUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val downloaded: Long, val total: Long, val bytesPerSecond: Long) : UpdateState {
        val fraction: Float get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
        val secondsLeft: Long? get() = if (bytesPerSecond <= 0 || total <= 0) null else (total - downloaded) / bytesPerSecond
    }
    data class Verifying(val release: ReleaseInfo) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    /** Android needs "Install unknown apps" allowed for Arnav Music before it can update itself. */
    data class NeedsPermission(val release: ReleaseInfo, val file: File) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val release: ReleaseInfo?, val message: String) : UpdateState
}

/**
 * Self-updates from GitHub Releases (no store, no server, no cost):
 * check → resumable download with live progress → SHA-256 + signing-certificate verification →
 * PackageInstaller session (silent on Android 12+ once Arnav Music is the installer of record).
 */
class UpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir: File get() = File(context.filesDir, "updates").apply { mkdirs() }

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    /** Debug builds are signed with a different key and could never install a release over themselves. */
    val supported: Boolean get() = !BuildConfig.DEBUG && BuildConfig.UPDATE_REPO.isNotBlank()
    val lastCheckedAt: Long get() = prefs.getLong("last_check", 0L)

    fun isDue(intervalMs: Long = 6 * 3_600_000L) = clock.now() - lastCheckedAt > intervalMs

    /** Returns the newer release if there is one. Never throws. */
    suspend fun check(): ReleaseInfo? = mutex.withLock {
        if (!supported) return@withLock null
        val previous = _state.value
        if (previous is UpdateState.Downloading || previous is UpdateState.Installing) return@withLock (previous as? UpdateState.Downloading)?.release
        _state.value = UpdateState.Checking
        val result = runCatching { fetchLatest() }
        prefs.edit().putLong("last_check", clock.now()).apply()
        result.fold(
            onSuccess = { release ->
                when {
                    release == null || release.versionCode <= currentVersionCode -> {
                        cleanup(keepVersion = null)
                        _state.value = UpdateState.UpToDate(clock.now()); null
                    }
                    else -> {
                        val ready = File(dir, release.apkName)
                        _state.value = if (ready.exists() && ready.length() == release.apkSize && prefs.getInt("verified_code", -1) == release.versionCode) {
                            UpdateState.ReadyToInstall(release, ready)
                        } else UpdateState.Available(release)
                        release
                    }
                }
            },
            onFailure = { e ->
                Log.w("update check failed", e)
                _state.value = if (previous is UpdateState.Available || previous is UpdateState.ReadyToInstall) previous
                else UpdateState.Failed(null, "Couldn't reach GitHub. Check your connection and try again.")
                null
            },
        )
    }

    private suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ArnavMusic/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) error("GitHub ${r.code}")
            val release = json.decodeFromString(GhRelease.serializer(), r.body?.string().orEmpty())
            if (release.draft) return@withContext null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", true) } ?: return@withContext null
            val code = Regex("""(\d+)\.(\d+)\.(\d+)""").find(release.tag)?.groupValues?.get(3)?.toIntOrNull() ?: return@withContext null
            ReleaseInfo(
                versionCode = code,
                versionName = release.tag.removePrefix("v"),
                apkName = apk.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_"),
                apkUrl = apk.url,
                apkSize = apk.size,
                sumsUrl = release.assets.firstOrNull { it.name.equals("SHA256SUMS.txt", true) }?.url,
                notes = cleanNotes(release.body.orEmpty()),
                htmlUrl = release.htmlUrl,
            )
        }
    }

    /** Downloads (resuming a partial file if present) with live progress, then verifies. */
    suspend fun download(release: ReleaseInfo, notify: Boolean = true): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val part = File(dir, release.apkName + ".part")
            val target = File(dir, release.apkName)
            cleanup(keepVersion = release.apkName)
            try {
                var downloaded = if (part.exists()) part.length() else 0L
                if (downloaded > release.apkSize) { part.delete(); downloaded = 0 }
                val request = Request.Builder().url(release.apkUrl)
                    .header("User-Agent", "ArnavMusic/${BuildConfig.VERSION_NAME}")
                    .apply { if (downloaded > 0) header("Range", "bytes=$downloaded-") }
                    .build()
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) error("Download failed (${r.code})")
                    val resumed = r.code == 206
                    if (!resumed) downloaded = 0
                    val total = release.apkSize.takeIf { it > 0 } ?: ((r.body?.contentLength() ?: -1L) + downloaded)
                    val body = r.body ?: error("Empty download")
                    FileOutputStream(part, resumed).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var lastEmit = 0L
                            var windowStart = clock.now()
                            var windowBytes = 0L
                            var speed = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                downloaded += n
                                windowBytes += n
                                val now = clock.now()
                                if (now - windowStart >= 500) {
                                    val instant = windowBytes * 1000 / (now - windowStart)
                                    speed = if (speed == 0L) instant else (speed * 0.6 + instant * 0.4).toLong()
                                    windowStart = now; windowBytes = 0
                                }
                                if (now - lastEmit >= 120 || downloaded == total) {
                                    lastEmit = now
                                    _state.value = UpdateState.Downloading(release, downloaded, total, speed)
                                    if (notify) progressNotification(release, downloaded, total)
                                }
                            }
                        }
                    }
                }
                if (release.apkSize > 0 && part.length() != release.apkSize) error("Download was incomplete. Try again.")
                _state.value = UpdateState.Verifying(release)
                verify(release, part)
                target.delete()
                if (!part.renameTo(target)) error("Couldn't save the update")
                prefs.edit().putInt("verified_code", release.versionCode).apply()
                _state.value = UpdateState.ReadyToInstall(release, target)
                if (notify) readyNotification(release)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = UpdateState.Available(release)
                cancelNotification()
                throw e
            } catch (e: Exception) {
                Log.w("update download failed", e)
                if (e is SecurityException) part.delete()
                _state.value = UpdateState.Failed(release, e.message ?: "Download failed. Try again.")
                cancelNotification()
                false
            }
        }
    }

    /** Checksum from the release's SHA256SUMS.txt and an exact signing-certificate match. */
    private fun verify(release: ReleaseInfo, file: File) {
        release.sumsUrl?.let { url ->
            val sums = client.newCall(Request.Builder().url(url).header("User-Agent", "ArnavMusic").build()).execute().use { it.body?.string().orEmpty() }
            val expected = sums.lineSequence().map { it.trim().split(Regex("""\s+""")) }
                .firstOrNull { it.size >= 2 && it.last().trimStart('*') == release.apkName }?.first()
            if (expected != null) {
                val actual = sha256(file)
                if (!actual.equals(expected, true)) throw SecurityException("The download didn't match its checksum, so it was discarded.")
            }
        }
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw SecurityException("The download isn't a valid app package.")
        if (archive.packageName != context.packageName) throw SecurityException("The download is for a different app.")
        val mine = signerDigests(pm.getPackageInfo(context.packageName, flags))
        val theirs = signerDigests(archive)
        if (mine.isEmpty() || mine != theirs) throw SecurityException("The update isn't signed with Arnav Music's key, so it wasn't installed.")
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.toList().orEmpty() else info.signatures?.toList().orEmpty()
        return sigs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun canInstallPackages(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** On Android 12+, once Arnav Music installed itself, later updates need no confirmation tap. */
    fun canInstallSilently(): Boolean = Build.VERSION.SDK_INT >= 31 && runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName == context.packageName
    }.getOrDefault(false)

    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the verified APK to the system installer. */
    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        val s = _state.value
        val (release, file) = when (s) {
            is UpdateState.ReadyToInstall -> s.release to s.file
            is UpdateState.NeedsPermission -> s.release to s.file
            else -> return@withContext false
        }
        if (!file.exists()) { _state.value = UpdateState.Available(release); return@withContext false }
        if (!canInstallPackages()) { _state.value = UpdateState.NeedsPermission(release, file); return@withContext false }
        runCatching {
            _state.value = UpdateState.Installing(release)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                if (Build.VERSION.SDK_INT >= 34) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
            true
        }.getOrElse { e ->
            Log.w("install failed", e)
            _state.value = UpdateState.Failed(release, "Android couldn't start the installation.")
            false
        }
    }

    internal fun onInstallResult(status: Int, message: String?, confirm: Intent?) {
        val release = (_state.value as? UpdateState.Installing)?.release ?: (_state.value as? UpdateState.ReadyToInstall)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // In foreground: show the system dialog right away; otherwise ask via notification
                // (Android blocks activity starts from the background).
                val foreground = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                val shown = foreground && runCatching { context.startActivity(confirm); true }.getOrDefault(false)
                if (!shown && release != null) confirmNotification(release, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> { cancelNotification(); _state.value = UpdateState.UpToDate(clock.now()) }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                val file = release?.let { File(dir, it.apkName) }
                _state.value = if (release != null && file != null && file.exists()) UpdateState.ReadyToInstall(release, file) else UpdateState.Idle
            }
            else -> _state.value = UpdateState.Failed(release, when (status) {
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android blocked the update (signature conflict). Reinstall from GitHub."
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update isn't compatible with your device."
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "The update was blocked by your device policy."
                else -> message?.take(120) ?: "Installation failed."
            })
        }
    }

    /** Deletes stale downloads (everything except [keepVersion]). */
    private fun cleanup(keepVersion: String?) {
        dir.listFiles()?.forEach { f -> if (keepVersion == null || !f.name.startsWith(keepVersion)) f.delete() }
    }

    fun isOnUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ---- Notifications --------------------------------------------------------------------

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Download progress and installs of new Arnav Music versions"
        })
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 11, Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("arnavmusic://settings/updates")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission")
    private fun post(builder: NotificationCompat.Builder) {
        ensureChannel()
        runCatching { if (NotificationManagerCompat.from(context).areNotificationsEnabled()) NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun base(release: ReleaseInfo) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_arnav)
        .setContentIntent(openAppIntent())
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentTitle("Arnav Music ${release.versionName}")

    private fun progressNotification(release: ReleaseInfo, downloaded: Long, total: Long) {
        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
        post(base(release).setOngoing(true).setProgress(100, pct, total <= 0)
            .setContentText("Downloading · ${Formatters.bytes(downloaded)} of ${Formatters.bytes(total)} ($pct%)"))
    }

    private fun readyNotification(release: ReleaseInfo) {
        post(base(release).setOngoing(false).setAutoCancel(true).setProgress(0, 0, false).setContentText("Update downloaded · tap to install"))
    }

    private fun confirmNotification(release: ReleaseInfo, confirm: Intent) {
        val pi = PendingIntent.getActivity(context, 12, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(base(release).setContentIntent(pi).setAutoCancel(true).setContentText("Tap to finish updating"))
    }

    fun cancelNotification() = runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }

    private fun cleanNotes(body: String): String = body
        .substringBefore("### Install")
        .lines()
        .map { it.replace(Regex("""^#+\s*"""), "").replace("**", "").replace("`", "").trim() }
        .filter { it.isNotBlank() && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") }
        .joinToString("\n")
        .take(1200)

    companion object {
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 77
    }
}
