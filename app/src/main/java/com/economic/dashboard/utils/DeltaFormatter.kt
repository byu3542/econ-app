package com.economic.dashboard.utils

import android.content.Context
import androidx.core.content.ContextCompat
import com.economic.dashboard.R

/**
 * Central formatter for change/delta values shown on KPI cards and heroes.
 *
 * Direction is encoded three ways so it never relies on color alone
 * (WCAG / colorblind-safe): a ▲/▼ glyph, a +/− sign, and a color. When the
 * user enables the colorblind-safe setting, the color pair switches from
 * green/red to an Okabe–Ito blue/orange pair that stays distinguishable for
 * the common forms of color vision deficiency.
 */
object DeltaFormatter {

    /** ▲ for a rise, ▼ for a fall, • for no change. */
    @JvmStatic
    fun glyph(delta: Double): String = when {
        delta > 0 -> "▲"
        delta < 0 -> "▼"
        else -> "•"
    }

    /** "+" for a rise, "−" (U+2212) for a fall, "" for no change. */
    @JvmStatic
    fun sign(delta: Double): String = when {
        delta > 0 -> "+"
        delta < 0 -> "−" // true minus, not a hyphen
        else -> ""
    }

    /**
     * Builds the full delta label, e.g. "▲ +0.20%".
     *
     * @param numberFmt a format string applied to the absolute value,
     *                  e.g. "%.2f%%", "%.1fpp", "%.2f".
     */
    @JvmStatic
    fun format(delta: Double, numberFmt: String): String =
        glyph(delta) + " " + sign(delta) +
            String.format(java.util.Locale.US, numberFmt, Math.abs(delta))

    /**
     * The color to tint the delta label.
     *
     * @param goodWhenDown true for metrics where a decrease is favorable
     *                     (CPI, unemployment, mortgage rate, VIX).
     */
    @JvmStatic
    fun color(c: Context, delta: Double, goodWhenDown: Boolean): Int {
        val good = if (goodWhenDown) delta < 0 else delta > 0
        val cb = SettingsManager.colorblindEnabled(c)
        val res = if (cb) {
            if (good) R.color.delta_good_cb else R.color.delta_bad_cb
        } else {
            if (good) R.color.delta_good else R.color.delta_bad
        }
        return ContextCompat.getColor(c, res)
    }
}
