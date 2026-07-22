package com.economic.dashboard.utils;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.economic.dashboard.R;

import java.util.Locale;

/**
 * Central formatter for change/delta values shown on KPI cards and heroes.
 *
 * Direction is encoded three ways so it never relies on color alone
 * (WCAG / colorblind-safe): a ▲/▼ glyph, a +/− sign, and a color. When the
 * user enables the colorblind-safe setting, the color pair switches from
 * green/red to an Okabe–Ito blue/orange pair that stays distinguishable for
 * the common forms of color vision deficiency.
 */
public final class DeltaFormatter {

    private DeltaFormatter() {}

    /** ▲ for a rise, ▼ for a fall, • for no change. */
    public static String glyph(double delta) {
        if (delta > 0) return "▲"; // ▲
        if (delta < 0) return "▼"; // ▼
        return "•";                // •
    }

    /** "+" for a rise, "−" (U+2212) for a fall, "" for no change. */
    public static String sign(double delta) {
        if (delta > 0) return "+";
        if (delta < 0) return "−"; // − true minus, not a hyphen
        return "";
    }

    /**
     * Builds the full delta label, e.g. "▲ +0.20%".
     *
     * @param numberFmt a format string applied to the absolute value,
     *                  e.g. "%.2f%%", "%.1fpp", "%.2f".
     */
    public static String format(double delta, String numberFmt) {
        return glyph(delta) + " " + sign(delta)
                + String.format(Locale.US, numberFmt, Math.abs(delta));
    }

    /**
     * The color to tint the delta label.
     *
     * @param goodWhenDown true for metrics where a decrease is favorable
     *                     (CPI, unemployment, mortgage rate, VIX).
     */
    public static int color(Context c, double delta, boolean goodWhenDown) {
        boolean good = goodWhenDown ? delta < 0 : delta > 0;
        boolean cb = SettingsManager.colorblindEnabled(c);
        int res;
        if (cb) res = good ? R.color.delta_good_cb : R.color.delta_bad_cb;
        else    res = good ? R.color.delta_good    : R.color.delta_bad;
        return ContextCompat.getColor(c, res);
    }
}
