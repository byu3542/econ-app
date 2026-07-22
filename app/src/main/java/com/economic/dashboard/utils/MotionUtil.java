package com.economic.dashboard.utils;

import android.content.Context;
import android.provider.Settings;

/**
 * TICKET-28 — accessibility gate for motion.
 *
 * Returns false when the user has turned system animations off (developer
 * options / accessibility "Remove animations", i.e.
 * {@code Settings.Global.ANIMATOR_DURATION_SCALE == 0}), or when the app's own
 * optional "Reduce motion" override is enabled. Animated reveals (skeleton,
 * insight) and the KPI count-up fall back to an instant state change when this
 * returns false, so nothing flickers or jumps for motion-sensitive users.
 */
public final class MotionUtil {

    private MotionUtil() {}

    /** App-level override, exposed in Settings (optional). Off by default. */
    public static final String KEY_REDUCE_MOTION = "reduce_motion";

    /** True when animations should play; false to fall back to instant state. */
    public static boolean animationsEnabled(Context c) {
        if (c == null) return true;
        // An explicit in-app "Reduce motion" toggle always wins.
        if (SettingsManager.getBool(c, KEY_REDUCE_MOTION, false)) return false;
        try {
            float scale = Settings.Global.getFloat(
                    c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (Exception e) {
            return true; // if the setting can't be read, don't suppress motion
        }
    }
}
