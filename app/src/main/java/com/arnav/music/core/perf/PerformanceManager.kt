package com.arnav.music.core.perf

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.core.content.ContextCompat
import com.arnav.music.core.settings.AppSettings
import com.arnav.music.core.settings.GlassLevel
import com.arnav.music.core.settings.MotionLevel
import com.arnav.music.core.settings.PerformanceMode
import com.arnav.music.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class DeviceTier { LOW, MID, HIGH }

/** What the visual layer is allowed to spend right now. Everything visual reads this. */
data class EffectsBudget(
    val tier: DeviceTier,
    val realBlur: Boolean,
    val glass: GlassLevel,
    val livingArtwork: Boolean,
    val particles: Int,
    val shaders: Boolean,
    val motion: MotionLevel,
    val refreshRate: Float,
    val powerSave: Boolean,
    val thermalThrottled: Boolean,
    val reason: String,
) {
    val reducedMotion: Boolean get() = motion != MotionLevel.FULL
}

/** Automatic quality governor: power saver, thermal state, device class, user choice. */
class PerformanceManager(private val context: Context, settings: SettingsRepository, scope: CoroutineScope) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val powerSave = MutableStateFlow(power?.isPowerSaveMode == true)
    private val thermal = MutableStateFlow(currentThermal())

    val tier: DeviceTier = run {
        val am = context.getSystemService(ActivityManager::class.java)
        val cores = Runtime.getRuntime().availableProcessors()
        when {
            am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192 || cores <= 4 -> DeviceTier.LOW
            Build.VERSION.SDK_INT >= 31 && cores >= 8 && (am?.memoryClass ?: 0) >= 256 -> DeviceTier.HIGH
            else -> DeviceTier.MID
        }
    }

    val refreshRate: Float = runCatching {
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
    }.getOrDefault(60f)

    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { powerSave.value = power?.isPowerSaveMode == true }
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { power?.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { thermal.value = it } }
        }
    }

    private fun currentThermal(): Int = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: 0 else 0

    private fun systemAnimationsOff(): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    val budget: StateFlow<EffectsBudget> = combine(settings.settings, powerSave, thermal) { s, saver, th ->
        compute(s, saver, th)
    }.stateIn(scope, SharingStarted.Eagerly, compute(settings.settings.value, powerSave.value, thermal.value))

    private fun compute(s: AppSettings, saver: Boolean, thermalStatus: Int): EffectsBudget {
        val hot = Build.VERSION.SDK_INT >= 29 && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val mode = s.performance
        val constrained = mode == PerformanceMode.BATTERY_SAVER ||
            (mode == PerformanceMode.AUTOMATIC && (saver || hot || tier == DeviceTier.LOW))
        val balanced = mode == PerformanceMode.BALANCED || (mode == PerformanceMode.AUTOMATIC && tier == DeviceTier.MID)
        val sysOff = systemAnimationsOff()
        val motion = when {
            sysOff -> MotionLevel.MINIMAL
            constrained && s.motion == MotionLevel.FULL -> MotionLevel.REDUCED
            else -> s.motion
        }
        val glass = when {
            s.reduceTransparency || s.highContrast -> GlassLevel.OFF
            constrained && s.glass == GlassLevel.FULL -> GlassLevel.SUBTLE
            else -> s.glass
        }
        val reason = when {
            mode == PerformanceMode.BATTERY_SAVER -> "Battery saver mode"
            saver -> "System battery saver is on"
            hot -> "Device is warm — effects reduced"
            tier == DeviceTier.LOW && mode == PerformanceMode.AUTOMATIC -> "Optimised for this device"
            sysOff -> "System animations are off"
            else -> "Full quality"
        }
        return EffectsBudget(
            tier = tier,
            realBlur = Build.VERSION.SDK_INT >= 31 && !constrained && glass != GlassLevel.OFF && mode != PerformanceMode.BATTERY_SAVER,
            glass = glass,
            livingArtwork = !constrained && motion == MotionLevel.FULL && s.artworkMotion != com.arnav.music.core.settings.ArtworkMotion.OFF,
            particles = when { constrained || motion != MotionLevel.FULL -> 0; balanced -> 18; else -> 36 },
            shaders = Build.VERSION.SDK_INT >= 33 && !constrained && !balanced && mode != PerformanceMode.BATTERY_SAVER,
            motion = motion,
            refreshRate = refreshRate,
            powerSave = saver,
            thermalThrottled = hot,
            reason = reason,
        )
    }
}
