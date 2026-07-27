package com.economic.dashboard.utils;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * IOS-09 — thin wrapper over {@link View#performHapticFeedback}, gated on
 * {@link SettingsManager#KEY_HAPTICS} (default on). iOS fires haptics
 * constantly; this is the one place every gesture in the app routes through
 * so that setting can silence all of them at once.
 */
public final class HapticUtil {

    private HapticUtil() {}

    private static boolean enabled(View v) {
        return v != null && v.getContext() != null
                && SettingsManager.getBool(v.getContext(), SettingsManager.KEY_HAPTICS, true);
    }

    /** Selection tick — tab switch, segment change. */
    public static void selection(View v) {
        if (!enabled(v)) return;
        int constant = Build.VERSION.SDK_INT >= 34
                ? HapticFeedbackConstants.SEGMENT_TICK
                : HapticFeedbackConstants.CLOCK_TICK;
        v.performHapticFeedback(constant);
    }

    /** Positive confirmation — a pull-to-refresh completing. */
    public static void confirm(View v) {
        if (!enabled(v)) return;
        int constant = Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.CLOCK_TICK;
        v.performHapticFeedback(constant);
    }

    /** Negative feedback — a failed fetch. */
    public static void reject(View v) {
        if (!enabled(v)) return;
        int constant = Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.REJECT
                : HapticFeedbackConstants.LONG_PRESS;
        v.performHapticFeedback(constant);
    }

    /** Light tick — chart scrub crossing a data point. */
    public static void scrubTick(View v) {
        if (!enabled(v)) return;
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }
}
