package com.arnav.music.core.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import java.time.LocalDate
import java.time.ZoneId

data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)

fun interface Clock {
    fun now(): Long
    fun today(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** Observes connectivity so every screen can render an honest offline state. */
class NetworkMonitor(context: Context, scope: CoroutineScope) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    val isOnline: StateFlow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(currentlyOnline()) }
            override fun onLost(network: Network) { trySend(currentlyOnline()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(currentlyOnline()) }
        }
        trySend(currentlyOnline())
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
        awaitClose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, currentlyOnline())

    fun currentlyOnline(): Boolean {
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        // INTERNET alone: VALIDATED is often missing behind VPNs, private DNS or captive-portal
        // checks that are blocked, which made the app claim "offline" while requests worked.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Never logs secrets; release builds log nothing below warnings. */
object Log {
    private const val TAG = "ArnavMusic"
    fun d(msg: String) { if (com.arnav.music.BuildConfig.DEBUG) android.util.Log.d(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { android.util.Log.w(TAG, msg + (t?.let { ": ${it.javaClass.simpleName}" } ?: "")) }
}

fun <T> Flow<T>.stateInEager(scope: CoroutineScope, initial: T): StateFlow<T> = stateIn(scope, SharingStarted.Eagerly, initial)
