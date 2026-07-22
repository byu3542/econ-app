package com.economic.dashboard.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Single access point for all user settings, stored in SharedPreferences.
 * Every setting has a sensible default so the app behaves identically
 * for users who never open the settings sheet.
 */
public class SettingsManager {

    public static final String PREFS_NAME = "app_settings";

    // Appearance
    public static final String KEY_NIGHT_MODE       = "night_mode";
    public static final String KEY_COLORBLIND       = "colorblind_mode";  // blue/orange safe deltas
    // Data
    public static final String KEY_REFRESH_ON_OPEN  = "refresh_on_open";
    // General
    public static final String KEY_DEFAULT_TAB      = "default_tab";      // 0=Overview 1=Markets 2=Economy 3=News
    // News categories
    public static final String KEY_NEWS_GOV         = "news_gov";
    public static final String KEY_NEWS_MEDIA       = "news_media";
    public static final String KEY_NEWS_INTL        = "news_intl";
    public static final String KEY_NEWS_RESEARCH    = "news_research";
    // AI Analyst
    public static final String KEY_SMART_CHIPS      = "smart_chips";
    public static final String KEY_DETAILED_AI      = "detailed_ai";
    // Charts
    public static final String KEY_CHART_GRIDLINES  = "chart_gridlines";
    public static final String KEY_CHART_DECIMALS   = "chart_decimals";   // 1..3
    public static final String KEY_CHART_TIMEFRAME  = "chart_timeframe";  // months: 3,6,12,24,60
    // Notifications
    public static final String KEY_NOTIFY_BIG_MOVES = "notify_big_moves";
    public static final String KEY_NOTIFY_RELEASES  = "notify_releases";
    // Internal bookkeeping (not shown in UI)
    public static final String KEY_DAILY_BRIEF      = "daily_ai_brief";
    public static final String KEY_LAST_BRIEF_DATE  = "last_brief_date";

    public static final String KEY_LAST_MOVE_NOTIFIED    = "last_move_notified";
    public static final String KEY_LAST_RELEASE_NOTIFIED = "last_release_notified";

    /** Newest news pubDate the user has actually seen (drives the News tab unread
        badge, TICKET-17). Stored as epoch millis; 0 = nothing seen yet. */
    public static final String KEY_NEWS_LAST_SEEN        = "news_last_seen";

    /** Analyst-tab unread badge (TICKET-26). {@code LAST_INSIGHT} is stamped by
        the daily-brief worker when it produces new analyst content; {@code LAST_SEEN}
        is advanced when the user opens the Analyst. Both epoch millis; 0 = unset. */
    public static final String KEY_ANALYST_LAST_SEEN     = "analyst_last_seen";
    public static final String KEY_ANALYST_LAST_INSIGHT  = "analyst_last_insight";

    /** TICKET-24: user-configured metric threshold alerts, stored as a JSON
        array of {@link com.economic.dashboard.alerts.AlertRule}. */
    public static final String KEY_ALERT_RULES           = "alert_rules";

    /** TICKET-25: watchlist (ordered, comma-separated metric keys) + one-time
        onboarding flag. An empty/missing watchlist means "use the default". */
    public static final String KEY_WATCHLIST             = "watchlist";
    public static final String KEY_ONBOARDING_COMPLETE   = "onboarding_complete";

    private SettingsManager() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Generic helpers ──────────────────────────────────────────────
    public static boolean getBool(Context c, String key, boolean def) {
        return prefs(c).getBoolean(key, def);
    }
    public static void setBool(Context c, String key, boolean value) {
        prefs(c).edit().putBoolean(key, value).apply();
    }
    public static int getInt(Context c, String key, int def) {
        return prefs(c).getInt(key, def);
    }
    public static void setInt(Context c, String key, int value) {
        prefs(c).edit().putInt(key, value).apply();
    }
    public static String getString(Context c, String key, String def) {
        return prefs(c).getString(key, def);
    }
    public static void setString(Context c, String key, String value) {
        prefs(c).edit().putString(key, value).apply();
    }
    public static long getLong(Context c, String key, long def) {
        return prefs(c).getLong(key, def);
    }
    public static void setLong(Context c, String key, long value) {
        prefs(c).edit().putLong(key, value).apply();
    }

    // ── Typed convenience accessors ──────────────────────────────────
    public static int getNightMode(Context c) {
        return getInt(c, KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
    public static void setNightMode(Context c, int mode) {
        setInt(c, KEY_NIGHT_MODE, mode);
    }
    public static int getDefaultTab(Context c) {
        return getInt(c, KEY_DEFAULT_TAB, 0);
    }
    public static int getChartDecimals(Context c) {
        return getInt(c, KEY_CHART_DECIMALS, 2);
    }
    public static int getChartTimeframeMonths(Context c) {
        return getInt(c, KEY_CHART_TIMEFRAME, 60);
    }
    /**
     * Per-chart time range (TICKET-11). Each chart persists its own choice under
     * a suffixed key and falls back to the global default until the user picks a
     * range for that chart. {@code chartId} is a short stable slug, e.g. "gdp".
     */
    public static int getChartTimeframeMonths(Context c, String chartId) {
        return getInt(c, KEY_CHART_TIMEFRAME + "_" + chartId, getChartTimeframeMonths(c));
    }
    public static void setChartTimeframeMonths(Context c, String chartId, int months) {
        setInt(c, KEY_CHART_TIMEFRAME + "_" + chartId, months);
    }
    public static boolean gridlinesEnabled(Context c) {
        return getBool(c, KEY_CHART_GRIDLINES, true);
    }
    /** When true, deltas use a blue/orange colorblind-safe palette. */
    public static boolean colorblindEnabled(Context c) {
        return getBool(c, KEY_COLORBLIND, false);
    }

    // ── News unread badge (TICKET-17) ────────────────────────────────
    /** Newest news item (epoch millis) the user has seen; 0 if never. */
    public static long getNewsLastSeen(Context c) {
        return getLong(c, KEY_NEWS_LAST_SEEN, 0L);
    }
    /** Records the newest seen news timestamp, monotonically (never rewinds). */
    public static void setNewsLastSeen(Context c, long millis) {
        if (millis > getNewsLastSeen(c)) setLong(c, KEY_NEWS_LAST_SEEN, millis);
    }

    // ── Analyst unread badge (TICKET-26) ─────────────────────────────────
    /** When the user last opened the Analyst (epoch millis); 0 if never. */
    public static long getAnalystLastSeen(Context c) {
        return getLong(c, KEY_ANALYST_LAST_SEEN, 0L);
    }
    /** Advances the "analyst seen" watermark, monotonically. */
    public static void setAnalystLastSeen(Context c, long millis) {
        if (millis > getAnalystLastSeen(c)) setLong(c, KEY_ANALYST_LAST_SEEN, millis);
    }
    /** Timestamp of the newest analyst insight (daily brief); 0 if none yet. */
    public static long getAnalystLastInsight(Context c) {
        return getLong(c, KEY_ANALYST_LAST_INSIGHT, 0L);
    }
    /** Stamps that a new analyst insight was just produced. */
    public static void setAnalystLastInsight(Context c, long millis) {
        setLong(c, KEY_ANALYST_LAST_INSIGHT, millis);
    }

    // ── Threshold alert rules (TICKET-24) ────────────────────────────────
    private static final com.google.gson.Gson ALERT_GSON = new com.google.gson.Gson();
    private static final java.lang.reflect.Type ALERT_LIST_TYPE =
            new com.google.gson.reflect.TypeToken<
                    java.util.List<com.economic.dashboard.alerts.AlertRule>>() {}.getType();

    /** All saved alert rules; never null. */
    public static java.util.List<com.economic.dashboard.alerts.AlertRule> getAlertRules(Context c) {
        String json = getString(c, KEY_ALERT_RULES, null);
        if (json == null || json.isEmpty()) return new java.util.ArrayList<>();
        try {
            java.util.List<com.economic.dashboard.alerts.AlertRule> rules =
                    ALERT_GSON.fromJson(json, ALERT_LIST_TYPE);
            return rules != null ? rules : new java.util.ArrayList<>();
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    /** Overwrites the stored rule list. */
    public static void saveAlertRules(Context c,
                                      java.util.List<com.economic.dashboard.alerts.AlertRule> rules) {
        setString(c, KEY_ALERT_RULES,
                ALERT_GSON.toJson(rules != null ? rules : new java.util.ArrayList<>(), ALERT_LIST_TYPE));
    }

    /** Appends one rule. */
    public static void addAlertRule(Context c, com.economic.dashboard.alerts.AlertRule rule) {
        if (rule == null) return;
        java.util.List<com.economic.dashboard.alerts.AlertRule> rules = getAlertRules(c);
        rules.add(rule);
        saveAlertRules(c, rules);
    }

    /** Removes every rule for the given series key. Returns how many were removed. */
    public static int removeAlertRulesFor(Context c, String seriesKey) {
        if (seriesKey == null) return 0;
        java.util.List<com.economic.dashboard.alerts.AlertRule> rules = getAlertRules(c);
        int before = rules.size();
        java.util.Iterator<com.economic.dashboard.alerts.AlertRule> it = rules.iterator();
        while (it.hasNext()) {
            com.economic.dashboard.alerts.AlertRule r = it.next();
            if (seriesKey.equals(r.seriesKey)) it.remove();
        }
        int removed = before - rules.size();
        if (removed > 0) saveAlertRules(c, rules);
        return removed;
    }

    // ── Watchlist + onboarding (TICKET-25) ───────────────────────────────
    /** Default watchlist order — matches the current fixed Overview order. */
    public static final String[] DEFAULT_WATCHLIST = {
            "gdp", "spread_10y3m", "cpi", "employment", "mbs_mortgage", "vix"
    };

    /** Ordered metric keys the user cares about most; falls back to the default. */
    public static java.util.List<String> getWatchlist(Context c) {
        String csv = getString(c, KEY_WATCHLIST, null);
        if (csv == null || csv.trim().isEmpty())
            return new java.util.ArrayList<>(java.util.Arrays.asList(DEFAULT_WATCHLIST));
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : csv.split(",")) {
            String t = k.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty()
                ? new java.util.ArrayList<>(java.util.Arrays.asList(DEFAULT_WATCHLIST))
                : out;
    }

    /** Persists the ordered watchlist (null/empty clears back to the default). */
    public static void setWatchlist(Context c, java.util.List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            setString(c, KEY_WATCHLIST, "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        setString(c, KEY_WATCHLIST, sb.toString());
    }

    public static boolean isOnboardingComplete(Context c) {
        return getBool(c, KEY_ONBOARDING_COMPLETE, false);
    }
    public static void setOnboardingComplete(Context c) {
        setBool(c, KEY_ONBOARDING_COMPLETE, true);
    }
}
