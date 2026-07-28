package com.economic.dashboard.utils

import android.content.Context
import android.provider.Settings

/**
 * TICKET-28 — accessibility gate for motion.
 *
 * Returns false when the user has turned system animations off (developer
 * options / accessibility "Remove animations", i.e.
 * `Settings.Global.ANIMATOR_DURATION_SCALE == 0`), or when the app's own
 * optional "Reduce motion" override is enabled. Animated reveals (skeleton,
 * insight) and the KPI count-up fall back to an instant state change when this
 * returns false, so nothing flickers or jumps for motion-sensitive users.
 */
object MotionUtil {

    /** App-level override, exposed in Settings (optional). Off by default. */
    @JvmField
    val KEY_REDUCE_MOTION = "reduce_motion"

    /** True when animations should play; false to fall back to instant state. */
    @JvmStatic
    fun animationsEnabled(c: Context?): Boolean {
        if (c == null) return true
        // An explicit in-app "Reduce motion" toggle always wins.
        if (SettingsManager.getBool(c, KEY_REDUCE_MOTION, false)) return false
        return try {
            val scale = Settings.Global.getFloat(
                c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            scale != 0f
        } catch (e: Exception) {
            true // if the setting can't be read, don't suppress motion
        }
    }
}
