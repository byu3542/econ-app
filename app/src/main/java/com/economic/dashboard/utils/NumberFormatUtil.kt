package com.economic.dashboard.utils

import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central, app-wide number and timestamp formatting (TICKET-09).
 *
 * Goal: every value that reaches the UI carries a consistent unit and decimal
 * precision, and every "last updated" line reads the same way. Fragments used
 * to format inline with scattered `String.format` calls; route them
 * through here instead so percent signs, thousands separators, index-point
 * suffixes and timestamps never drift between screens.
 *
 * All methods are null/NaN-safe and locale-fixed to US so the decimal point
 * and grouping separator are stable regardless of device locale.
 */
object NumberFormatUtil {

    @JvmField
    val EM_DASH = "—" // — placeholder for "no value"

    /** e.g. 4.33 → "4.33%" (2 decimals). */
    @JvmStatic
    fun percent(value: Double): String = percent(value, 2)

    /** e.g. 4.3 → "4.3%" at the requested precision. */
    @JvmStatic
    fun percent(value: Double, decimals: Int): String {
        if (value.isNaN()) return EM_DASH
        return String.format(Locale.US, "%,." + clampDecimals(decimals) + "f%%", value)
    }

    /** Signed percent for changes, e.g. +0.25 → "+0.25%", -0.10 → "-0.10%". */
    @JvmStatic
    fun signedPercent(value: Double, decimals: Int): String {
        if (value.isNaN()) return EM_DASH
        return String.format(Locale.US, "%+,." + clampDecimals(decimals) + "f%%", value)
    }

    /** Percentage-point change, e.g. +0.3 → "+0.30pp". */
    @JvmStatic
    fun percentagePoints(value: Double, decimals: Int): String {
        if (value.isNaN()) return EM_DASH
        return String.format(Locale.US, "%+,." + clampDecimals(decimals) + "fpp", value)
    }

    /** Index / points value, e.g. 18.42 → "18.4 pt". */
    @JvmStatic
    fun indexPoints(value: Double, decimals: Int): String {
        if (value.isNaN()) return EM_DASH
        return String.format(Locale.US, "%,." + clampDecimals(decimals) + "f pt", value)
    }

    /** Currency with thousands separators, e.g. 34567.8 → "$34,567.80". */
    @JvmStatic
    fun dollars(value: Double, decimals: Int): String {
        if (value.isNaN()) return EM_DASH
        return String.format(Locale.US, "$%,." + clampDecimals(decimals) + "f", value)
    }

    /** Plain number with a thousands separator and fixed decimals. */
    @JvmStatic
    fun number(value: Double, decimals: Int): String {
        if (value.isNaN()) return EM_DASH
        return String.format(Locale.US, "%,." + clampDecimals(decimals) + "f", value)
    }

    // ── "As of" timestamps ──────────────────────────────────────────────────

    /** "As of Jul 22, 9:38 AM ET" from an epoch-millis instant. */
    @JvmStatic
    fun asOf(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val stamp = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(epochMillis))
        return "As of $stamp ET"
    }

    /** "As of now" convenience for a fresh fetch. */
    @JvmStatic
    fun asOfNow(): String = asOf(System.currentTimeMillis())

    /**
     * Formats an already-formatted data date (yyyy-MM-dd) as "As of Mon d, yyyy".
     * Falls back to the raw string if it isn't in the expected shape.
     */
    @JvmStatic
    fun asOfDate(yyyyMmDd: String?): String {
        if (yyyyMmDd == null || yyyyMmDd.length < 10) return yyyyMmDd ?: ""
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(yyyyMmDd.substring(0, 10))
                ?: return yyyyMmDd
            "As of " + SimpleDateFormat("MMM d, yyyy", Locale.US).format(d)
        } catch (e: Exception) {
            yyyyMmDd
        }
    }

    private fun clampDecimals(d: Int): Int = Math.max(0, Math.min(6, d))

    /**
     * IOS-03 — tabular (fixed-width) digits, so a count-up/rolling value never
     * shifts horizontally. The app theme's default TextView style already
     * carries this (see Widget.Econ.TextView in themes.xml); this is the
     * explicit call for the count-up path (ValueAnimatorUtil) and any other
     * TextView set up outside that default, so it can't be missed. Idempotent.
     */
    @JvmStatic
    fun applyTabularFigures(tv: TextView?) {
        tv?.fontFeatureSettings = "tnum"
    }
}
