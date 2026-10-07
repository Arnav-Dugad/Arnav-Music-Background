package com.arnav.music.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Immutable

/** Meaningful, subtle haptics. Every type maps to a distinct system primitive; never continuous. */
@Immutable
class ArnavHaptics(private val view: View?, private val enabled: Boolean) {
    private fun perform(constant: Int) {
        if (!enabled) return
        view?.performHapticFeedback(constant)
    }
    fun navigate() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun select() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun favorite() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun queued() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.VIRTUAL_KEY)
    fun snap() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun destructive() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    fun press() = perform(HapticFeedbackConstants.KEYBOARD_TAP)
}
