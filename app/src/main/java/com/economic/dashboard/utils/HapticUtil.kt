package com.economic.dashboard.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * IOS-09 — thin wrapper over [View.performHapticFeedback], gated on
 * [SettingsManager.KEY_HAPTICS] (default on). iOS fires haptics
 * constantly; this is the one place every gesture in the app routes through
 * so that setting can silence all of them at once.
 */
object HapticUtil {

    private fun enabled(v: View): Boolean =
        SettingsManager.getBool(v.context, SettingsManager.KEY_HAPTICS, true)

    /** Selection tick — tab switch, segment change. */
    @JvmStatic
    fun selection(v: View?) {
        if (v == null || !enabled(v)) return
        val constant = if (Build.VERSION.SDK_INT >= 34)
            HapticFeedbackConstants.SEGMENT_TICK
        else
            HapticFeedbackConstants.CLOCK_TICK
        v.performHapticFeedback(constant)
    }

    /** Positive confirmation — a pull-to-refresh completing. */
    @JvmStatic
    fun confirm(v: View?) {
        if (v == null || !enabled(v)) return
        val constant = if (Build.VERSION.SDK_INT >= 30)
            HapticFeedbackConstants.CONFIRM
        else
            HapticFeedbackConstants.CLOCK_TICK
        v.performHapticFeedback(constant)
    }

    /** Negative feedback — a failed fetch. */
    @JvmStatic
    fun reject(v: View?) {
        if (v == null || !enabled(v)) return
        val constant = if (Build.VERSION.SDK_INT >= 30)
            HapticFeedbackConstants.REJECT
        else
            HapticFeedbackConstants.LONG_PRESS
        v.performHapticFeedback(constant)
    }

    /** Light tick — chart scrub crossing a data point. */
    @JvmStatic
    fun scrubTick(v: View?) {
        if (v == null || !enabled(v)) return
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
