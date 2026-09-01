package com.economic.dashboard.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.economic.dashboard.alerts.AlertRule
import com.economic.dashboard.ui.EconomicViewModel
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
    // TICKET-P2-7: fingerprint of the historical data block behind the last
    // sent brief — a matching fingerprint means no material change, no brief.
    @JvmField val KEY_LAST_BRIEF_FINGERPRINT = "last_brief_fingerprint"

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

    /** Schema version of the persisted [KEY_ALERT_RULES] evaluation state;
        bumped when a series' alert units change so [getAlertRules] can reset
        stale lastState/lastValue exactly once. Unset = 1 (pre-unit-fix). */
    @JvmField val KEY_ALERT_SCHEMA_VERSION = "alert_schema_version"

    /** TICKET-25: watchlist (ordered, comma-separated metric keys) + one-time
        onboarding flag. An empty/missing watchlist means "use the default". */
    @JvmField val KEY_WATCHLIST = "watchlist"
    @JvmField val KEY_ONBOARDING_COMPLETE = "onboarding_complete"

    /** Design-branch 2a: which indicator each Overview slot tracks. */
    @JvmField val KEY_DASHBOARD_HERO = "dashboard_hero"
    @JvmField val KEY_DASHBOARD_SLOTS = "dashboard_slots"

    /** Direction 1d: manually drag-reordered cluster order (category keys,
     *  e.g. "growth,inflation,housing_markets"). Unset/invalid falls back to
     *  the watchlist-ranked order. */
    @JvmField val KEY_DASHBOARD_CATEGORY_ORDER = "dashboard_category_order"

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
     *
     * TICKET-56: the result is clamped to the series' cadence floor
     * ([ChartCadence.minMonths]) on read, so a stored 3M on quarterly GDP —
     * reachable with one tap before the floor existed — draws as 2Y everywhere
     * the window is consumed (selector, chart, sheet table) without the pref
     * being rewritten behind the user's back.
     */
    @JvmStatic
    fun getChartTimeframeMonths(c: Context, chartId: String): Int =
        maxOf(
            getInt(c, KEY_CHART_TIMEFRAME + "_" + chartId, getChartTimeframeMonths(c)),
            ChartCadence.minMonths(chartId)
        )

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

    /** Current [KEY_ALERT_SCHEMA_VERSION]. v2: "pce" evaluates Core PCE YoY %
        (was the raw index level) and "wages" the real-wage-growth spread in pp
        (was nominal $/hour) — see [com.economic.dashboard.alerts.AlertValues]. */
    private const val ALERT_SCHEMA_VERSION = 2

    /** All saved alert rules; never null. */
    @JvmStatic
    fun getAlertRules(c: Context): List<AlertRule> {
        val json = getString(c, KEY_ALERT_RULES, null)
        val rules: List<AlertRule> = if (json.isNullOrEmpty()) {
            ArrayList()
        } else try {
            val parsed: List<AlertRule>? = alertGson.fromJson(json, alertListType)
            parsed ?: ArrayList()
        } catch (e: Exception) {
            ArrayList()
        }
        migrateAlertEvalState(c, rules)
        return rules
    }

    /**
     * One-time evaluation-state reset for rules whose series changed alert
     * units at schema v2 (see [ALERT_SCHEMA_VERSION]). Old-unit lastState=true
     * would swallow the first legitimate crossing in the new units (the
     * evaluator fires on crossings only), and an old-unit lastValue would fire
     * one spurious "changed" notification. Every load path funnels through
     * [getAlertRules], and the stored version stamp keeps this idempotent.
     */
    private fun migrateAlertEvalState(c: Context, rules: List<AlertRule>) {
        if (getInt(c, KEY_ALERT_SCHEMA_VERSION, 1) >= ALERT_SCHEMA_VERSION) return
        var dirty = false
        for (r in rules) {
            if (r.seriesKey == EconomicViewModel.KEY_PCE || r.seriesKey == EconomicViewModel.KEY_WAGES) {
                if (r.lastState || r.lastValue != null) {
                    r.lastState = false
                    r.lastValue = null
                    dirty = true
                }
            }
        }
        if (dirty) saveAlertRules(c, rules)
        setInt(c, KEY_ALERT_SCHEMA_VERSION, ALERT_SCHEMA_VERSION)
    }

    /** Overwrites the stored rule list. */
    @JvmStatic
    fun saveAlertRules(c: Context, rules: List<AlertRule>?) {
        setString(c, KEY_ALERT_RULES, alertGson.toJson(rules ?: ArrayList<AlertRule>(), alertListType))
    }

    /**
     * Appends one rule unless an identical one is already stored.
     *
     * P9 TICKET-51: identity is (seriesKey, op, threshold). A duplicate used to
     * be appended silently, which meant two notifications per crossing and two
     * identical lines in the add-alert dialog. On a match the **stored** rule is
     * kept rather than replaced: its `lastState`/`lastValue` latch encodes where
     * the series already is, and re-arming it would either swallow the next
     * crossing or fire a spurious one (see
     * econ-os docs/solutions/logic-errors/alert-evaluator-raw-vs-popup-displayed-units.md).
     *
     * @return true if the rule was stored, false if an identical one existed.
     */
    @JvmStatic
    fun addAlertRule(c: Context, rule: AlertRule?): Boolean {
        if (rule == null) return false
        val rules = getAlertRules(c).toMutableList()
        if (rules.any { sameRule(it, rule) }) return false
        rules.add(rule)
        saveAlertRules(c, rules)
        return true
    }

    /**
     * Removes a single rule (P9 TICKET-53). [removeAlertRulesFor] remains the
     * bulk path. Undo is handled by passing the removed instance back to
     * [addAlertRule] — it still carries its evaluation state, so restoring it
     * restores the latch too.
     *
     * @return true if a matching rule was found and removed.
     */
    @JvmStatic
    fun removeAlertRule(c: Context, rule: AlertRule?): Boolean {
        if (rule == null) return false
        val rules = getAlertRules(c).toMutableList()
        val idx = rules.indexOfFirst { sameRule(it, rule) }
        if (idx < 0) return false
        rules.removeAt(idx)
        saveAlertRules(c, rules)
        return true
    }

    /** Rule identity for add/remove: same series, same operator, same threshold. */
    private fun sameRule(a: AlertRule, b: AlertRule): Boolean =
        a.seriesKey == b.seriesKey && a.op == b.op && a.threshold == b.threshold

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

    /** Split a stored CSV into trimmed, non-empty entries (empty list for null/blank input). */
    private fun parseCsvList(csv: String?): List<String> {
        val out = ArrayList<String>()
        if (!csv.isNullOrEmpty()) {
            for (k in csv.split(",")) {
                val t = k.trim()
                if (t.isNotEmpty()) out.add(t)
            }
        }
        return out
    }

    /** Ordered metric keys the user cares about most; falls back to the default. */
    @JvmStatic
    fun getWatchlist(c: Context): List<String> {
        val csv = getString(c, KEY_WATCHLIST, null)
        if (csv == null || csv.trim().isEmpty()) return ArrayList(DEFAULT_WATCHLIST.toList())
        val out = parseCsvList(csv)
        return if (out.isEmpty()) ArrayList(DEFAULT_WATCHLIST.toList()) else out
    }

    /** Persists the ordered watchlist (null/empty clears back to the default). */
    @JvmStatic
    fun setWatchlist(c: Context, keys: List<String>?) {
        if (keys.isNullOrEmpty()) {
            setString(c, KEY_WATCHLIST, "")
            return
        }
        setString(c, KEY_WATCHLIST, keys.joinToString(","))
    }

    @JvmStatic
    fun isOnboardingComplete(c: Context): Boolean = getBool(c, KEY_ONBOARDING_COMPLETE, false)

    @JvmStatic
    fun setOnboardingComplete(c: Context) {
        setBool(c, KEY_ONBOARDING_COMPLETE, true)
    }

    // ── Customizable dashboard (design branch 2a) ────────────────────────
    /** Default hero + 6 slots — matches the fixed layout this feature replaces. */
    @JvmField val DEFAULT_DASHBOARD_HERO = "fedfunds"
    @JvmField val DEFAULT_DASHBOARD_SLOTS = arrayOf("gdp", "spread", "cpi", "unemployment", "mortgage", "vix")

    @JvmStatic
    fun getDashboardHero(c: Context): String =
        getString(c, KEY_DASHBOARD_HERO, DEFAULT_DASHBOARD_HERO) ?: DEFAULT_DASHBOARD_HERO

    @JvmStatic
    fun setDashboardHero(c: Context, id: String) {
        setString(c, KEY_DASHBOARD_HERO, id)
    }

    /** Always returns exactly 6 ids — missing slots are backfilled from the default. */
    @JvmStatic
    fun getDashboardSlots(c: Context): List<String> {
        val csv = getString(c, KEY_DASHBOARD_SLOTS, null)
        val out = ArrayList(parseCsvList(csv))
        var i = 0
        while (out.size < 6) {
            out.add(DEFAULT_DASHBOARD_SLOTS[i % DEFAULT_DASHBOARD_SLOTS.size])
            i++
        }
        return out.subList(0, 6)
    }

    @JvmStatic
    fun setDashboardSlots(c: Context, ids: List<String>) {
        setString(c, KEY_DASHBOARD_SLOTS, ids.joinToString(","))
    }

    /** Manually drag-reordered category order, or null when the user has
     *  never reordered (caller should fall back to the watchlist rank). */
    @JvmStatic
    fun getDashboardCategoryOrder(c: Context): List<String>? {
        val csv = getString(c, KEY_DASHBOARD_CATEGORY_ORDER, null)
        if (csv.isNullOrEmpty()) return null
        val out = parseCsvList(csv)
        return if (out.isEmpty()) null else out
    }

    @JvmStatic
    fun setDashboardCategoryOrder(c: Context, keys: List<String>?) {
        if (keys.isNullOrEmpty()) {
            setString(c, KEY_DASHBOARD_CATEGORY_ORDER, "")
            return
        }
        setString(c, KEY_DASHBOARD_CATEGORY_ORDER, keys.joinToString(","))
    }
}
