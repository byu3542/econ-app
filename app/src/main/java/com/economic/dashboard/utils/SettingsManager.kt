package com.economic.dashboard.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.economic.dashboard.alerts.AlertRule
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Single access point for all user settings, stored in SharedPreferences.
 * Every setting has a sensible default so the app behaves identically
 * for users who never open the settings sheet.
 */
object SettingsManager {

    @JvmField val PREFS_NAME = "app_settings"

    // Appearance
    @JvmField val KEY_NIGHT_MODE = "night_mode"
    @JvmField val KEY_COLORBLIND = "colorblind_mode" // blue/orange safe deltas
    // Data
    @JvmField val KEY_REFRESH_ON_OPEN = "refresh_on_open"
    // General
    @JvmField val KEY_DEFAULT_TAB = "default_tab" // 0=Overview 1=Markets 2=Economy 3=News
    // News categories
    @JvmField val KEY_NEWS_GOV = "news_gov"
    @JvmField val KEY_NEWS_MEDIA = "news_media"
    @JvmField val KEY_NEWS_INTL = "news_intl"
    @JvmField val KEY_NEWS_RESEARCH = "news_research"
    // AI Analyst
    @JvmField val KEY_SMART_CHIPS = "smart_chips"
    @JvmField val KEY_DETAILED_AI = "detailed_ai"
    // IOS-09: haptics, default on
    @JvmField val KEY_HAPTICS = "haptics_enabled"
    // Charts
    @JvmField val KEY_CHART_GRIDLINES = "chart_gridlines"
    @JvmField val KEY_CHART_DECIMALS = "chart_decimals" // 1..3
    @JvmField val KEY_CHART_TIMEFRAME = "chart_timeframe" // months: 3,6,12,24,60
    // Notifications
    @JvmField val KEY_NOTIFY_BIG_MOVES = "notify_big_moves"
    @JvmField val KEY_NOTIFY_RELEASES = "notify_releases"
    // Internal bookkeeping (not shown in UI)
    @JvmField val KEY_DAILY_BRIEF = "daily_ai_brief"
    @JvmField val KEY_LAST_BRIEF_DATE = "last_brief_date"

    @JvmField val KEY_LAST_MOVE_NOTIFIED = "last_move_notified"
    @JvmField val KEY_LAST_RELEASE_NOTIFIED = "last_release_notified"

    /** Newest news pubDate the user has actually seen (drives the News tab unread
        badge, TICKET-17). Stored as epoch millis; 0 = nothing seen yet. */
    @JvmField val KEY_NEWS_LAST_SEEN = "news_last_seen"

    /** Analyst-tab unread badge (TICKET-26). `LAST_INSIGHT` is stamped by
        the daily-brief worker when it produces new analyst content; `LAST_SEEN`
        is advanced when the user opens the Analyst. Both epoch millis; 0 = unset. */
    @JvmField val KEY_ANALYST_LAST_SEEN = "analyst_last_seen"
    @JvmField val KEY_ANALYST_LAST_INSIGHT = "analyst_last_insight"

    /** TICKET-24: user-configured metric threshold alerts, stored as a JSON
        array of [AlertRule]. */
    @JvmField val KEY_ALERT_RULES = "alert_rules"

    /** TICKET-25: watchlist (ordered, comma-separated metric keys) + one-time
        onboarding flag. An empty/missing watchlist means "use the default". */
    @JvmField val KEY_WATCHLIST = "watchlist"
    @JvmField val KEY_ONBOARDING_COMPLETE = "onboarding_complete"

    @JvmStatic
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Generic helpers ──────────────────────────────────────────────
    @JvmStatic
    fun getBool(c: Context, key: String, def: Boolean): Boolean = prefs(c).getBoolean(key, def)

    @JvmStatic
    fun setBool(c: Context, key: String, value: Boolean) {
        prefs(c).edit().putBoolean(key, value).apply()
    }

    @JvmStatic
    fun getInt(c: Context, key: String, def: Int): Int = prefs(c).getInt(key, def)

    @JvmStatic
    fun setInt(c: Context, key: String, value: Int) {
        prefs(c).edit().putInt(key, value).apply()
    }

    @JvmStatic
    fun getString(c: Context, key: String, def: String?): String? = prefs(c).getString(key, def)

    @JvmStatic
    fun setString(c: Context, key: String, value: String?) {
        prefs(c).edit().putString(key, value).apply()
    }

    @JvmStatic
    fun getLong(c: Context, key: String, def: Long): Long = prefs(c).getLong(key, def)

    @JvmStatic
    fun setLong(c: Context, key: String, value: Long) {
        prefs(c).edit().putLong(key, value).apply()
    }

    // ── Typed convenience accessors ──────────────────────────────────
    @JvmStatic
    fun getNightMode(c: Context): Int = getInt(c, KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    @JvmStatic
    fun setNightMode(c: Context, mode: Int) {
        setInt(c, KEY_NIGHT_MODE, mode)
    }

    @JvmStatic
    fun getDefaultTab(c: Context): Int = getInt(c, KEY_DEFAULT_TAB, 0)

    @JvmStatic
    fun getChartDecimals(c: Context): Int = getInt(c, KEY_CHART_DECIMALS, 2)

    @JvmStatic
    fun getChartTimeframeMonths(c: Context): Int = getInt(c, KEY_CHART_TIMEFRAME, 60)

    /**
     * Per-chart time range (TICKET-11). Each chart persists its own choice under
     * a suffixed key and falls back to the global default until the user picks a
     * range for that chart. `chartId` is a short stable slug, e.g. "gdp".
     */
    @JvmStatic
    fun getChartTimeframeMonths(c: Context, chartId: String): Int =
        getInt(c, KEY_CHART_TIMEFRAME + "_" + chartId, getChartTimeframeMonths(c))

    @JvmStatic
    fun setChartTimeframeMonths(c: Context, chartId: String, months: Int) {
        setInt(c, KEY_CHART_TIMEFRAME + "_" + chartId, months)
    }

    @JvmStatic
    fun gridlinesEnabled(c: Context): Boolean = getBool(c, KEY_CHART_GRIDLINES, true)

    /** When true, deltas use a blue/orange colorblind-safe palette. */
    @JvmStatic
    fun colorblindEnabled(c: Context): Boolean = getBool(c, KEY_COLORBLIND, false)

    // ── News unread badge (TICKET-17) ────────────────────────────────
    /** Newest news item (epoch millis) the user has seen; 0 if never. */
    @JvmStatic
    fun getNewsLastSeen(c: Context): Long = getLong(c, KEY_NEWS_LAST_SEEN, 0L)

    /** Records the newest seen news timestamp, monotonically (never rewinds). */
    @JvmStatic
    fun setNewsLastSeen(c: Context, millis: Long) {
        if (millis > getNewsLastSeen(c)) setLong(c, KEY_NEWS_LAST_SEEN, millis)
    }

    // ── Analyst unread badge (TICKET-26) ─────────────────────────────────
    /** When the user last opened the Analyst (epoch millis); 0 if never. */
    @JvmStatic
    fun getAnalystLastSeen(c: Context): Long = getLong(c, KEY_ANALYST_LAST_SEEN, 0L)

    /** Advances the "analyst seen" watermark, monotonically. */
    @JvmStatic
    fun setAnalystLastSeen(c: Context, millis: Long) {
        if (millis > getAnalystLastSeen(c)) setLong(c, KEY_ANALYST_LAST_SEEN, millis)
    }

    /** Timestamp of the newest analyst insight (daily brief); 0 if none yet. */
    @JvmStatic
    fun getAnalystLastInsight(c: Context): Long = getLong(c, KEY_ANALYST_LAST_INSIGHT, 0L)

    /** Stamps that a new analyst insight was just produced. */
    @JvmStatic
    fun setAnalystLastInsight(c: Context, millis: Long) {
        setLong(c, KEY_ANALYST_LAST_INSIGHT, millis)
    }

    // ── Threshold alert rules (TICKET-24) ────────────────────────────────
    private val alertGson = Gson()
    private val alertListType = object : TypeToken<List<AlertRule>>() {}.type

    /** All saved alert rules; never null. */
    @JvmStatic
    fun getAlertRules(c: Context): List<AlertRule> {
        val json = getString(c, KEY_ALERT_RULES, null)
        if (json.isNullOrEmpty()) return ArrayList()
        return try {
            val rules: List<AlertRule>? = alertGson.fromJson(json, alertListType)
            rules ?: ArrayList()
        } catch (e: Exception) {
            ArrayList()
        }
    }

    /** Overwrites the stored rule list. */
    @JvmStatic
    fun saveAlertRules(c: Context, rules: List<AlertRule>?) {
        setString(c, KEY_ALERT_RULES, alertGson.toJson(rules ?: ArrayList<AlertRule>(), alertListType))
    }

    /** Appends one rule. */
    @JvmStatic
    fun addAlertRule(c: Context, rule: AlertRule?) {
        if (rule == null) return
        val rules = getAlertRules(c).toMutableList()
        rules.add(rule)
        saveAlertRules(c, rules)
    }

    /** Removes every rule for the given series key. Returns how many were removed. */
    @JvmStatic
    fun removeAlertRulesFor(c: Context, seriesKey: String?): Int {
        if (seriesKey == null) return 0
        val rules = getAlertRules(c).toMutableList()
        val before = rules.size
        rules.removeAll { seriesKey == it.seriesKey }
        val removed = before - rules.size
        if (removed > 0) saveAlertRules(c, rules)
        return removed
    }

    // ── Watchlist + onboarding (TICKET-25) ───────────────────────────────
    /** Default watchlist order — matches the current fixed Overview order. */
    @JvmField
    val DEFAULT_WATCHLIST = arrayOf("gdp", "spread_10y3m", "cpi", "employment", "mbs_mortgage", "vix")

    /** Ordered metric keys the user cares about most; falls back to the default. */
    @JvmStatic
    fun getWatchlist(c: Context): List<String> {
        val csv = getString(c, KEY_WATCHLIST, null)
        if (csv == null || csv.trim().isEmpty()) return ArrayList(DEFAULT_WATCHLIST.toList())
        val out = ArrayList<String>()
        for (k in csv.split(",")) {
            val t = k.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return if (out.isEmpty()) ArrayList(DEFAULT_WATCHLIST.toList()) else out
    }

    /** Persists the ordered watchlist (null/empty clears back to the default). */
    @JvmStatic
    fun setWatchlist(c: Context, keys: List<String>?) {
        if (keys.isNullOrEmpty()) {
            setString(c, KEY_WATCHLIST, "")
            return
        }
        val sb = StringBuilder()
        for (k in keys) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(k)
        }
        setString(c, KEY_WATCHLIST, sb.toString())
    }

    @JvmStatic
    fun isOnboardingComplete(c: Context): Boolean = getBool(c, KEY_ONBOARDING_COMPLETE, false)

    @JvmStatic
    fun setOnboardingComplete(c: Context) {
        setBool(c, KEY_ONBOARDING_COMPLETE, true)
    }
}
