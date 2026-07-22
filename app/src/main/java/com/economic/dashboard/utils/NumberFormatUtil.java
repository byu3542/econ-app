package com.economic.dashboard.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Central, app-wide number and timestamp formatting (TICKET-09).
 *
 * Goal: every value that reaches the UI carries a consistent unit and decimal
 * precision, and every "last updated" line reads the same way. Fragments used
 * to format inline with scattered {@code String.format} calls; route them
 * through here instead so percent signs, thousands separators, index-point
 * suffixes and timestamps never drift between screens.
 *
 * All methods are null/NaN-safe and locale-fixed to US so the decimal point
 * and grouping separator are stable regardless of device locale.
 */
public final class NumberFormatUtil {

    private NumberFormatUtil() {}

    public static final String EM_DASH = "—"; // — placeholder for "no value"

    /** e.g. 4.33 → "4.33%" (2 decimals). */
    public static String percent(double value) {
        return percent(value, 2);
    }

    /** e.g. 4.3 → "4.3%" at the requested precision. */
    public static String percent(double value, int decimals) {
        if (Double.isNaN(value)) return EM_DASH;
        return String.format(Locale.US, "%,." + clampDecimals(decimals) + "f%%", value);
    }

    /** Signed percent for changes, e.g. +0.25 → "+0.25%", -0.10 → "-0.10%". */
    public static String signedPercent(double value, int decimals) {
        if (Double.isNaN(value)) return EM_DASH;
        return String.format(Locale.US, "%+,." + clampDecimals(decimals) + "f%%", value);
    }

    /** Percentage-point change, e.g. +0.3 → "+0.30pp". */
    public static String percentagePoints(double value, int decimals) {
        if (Double.isNaN(value)) return EM_DASH;
        return String.format(Locale.US, "%+,." + clampDecimals(decimals) + "fpp", value);
    }

    /** Index / points value, e.g. 18.42 → "18.4 pt". */
    public static String indexPoints(double value, int decimals) {
        if (Double.isNaN(value)) return EM_DASH;
        return String.format(Locale.US, "%,." + clampDecimals(decimals) + "f pt", value);
    }

    /** Currency with thousands separators, e.g. 34567.8 → "$34,567.80". */
    public static String dollars(double value, int decimals) {
        if (Double.isNaN(value)) return EM_DASH;
        return String.format(Locale.US, "$%,." + clampDecimals(decimals) + "f", value);
    }

    /** Plain number with a thousands separator and fixed decimals. */
    public static String number(double value, int decimals) {
        if (Double.isNaN(value)) return EM_DASH;
        return String.format(Locale.US, "%,." + clampDecimals(decimals) + "f", value);
    }

    // ── "As of" timestamps ──────────────────────────────────────────────────

    /** "As of Jul 22, 9:38 AM ET" from an epoch-millis instant. */
    public static String asOf(long epochMillis) {
        if (epochMillis <= 0) return "";
        String stamp = new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(epochMillis));
        return "As of " + stamp + " ET";
    }

    /** "As of now" convenience for a fresh fetch. */
    public static String asOfNow() {
        return asOf(System.currentTimeMillis());
    }

    /**
     * Formats an already-formatted data date (yyyy-MM-dd) as "As of Mon d, yyyy".
     * Falls back to the raw string if it isn't in the expected shape.
     */
    public static String asOfDate(String yyyyMmDd) {
        if (yyyyMmDd == null || yyyyMmDd.length() < 10) return yyyyMmDd == null ? "" : yyyyMmDd;
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(yyyyMmDd.substring(0, 10));
            if (d == null) return yyyyMmDd;
            return "As of " + new SimpleDateFormat("MMM d, yyyy", Locale.US).format(d);
        } catch (Exception e) {
            return yyyyMmDd;
        }
    }

    private static int clampDecimals(int d) {
        return Math.max(0, Math.min(6, d));
    }
}
