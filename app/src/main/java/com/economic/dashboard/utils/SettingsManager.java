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
    // Notifications
    public static final String KEY_NOTIFY_BIG_MOVES = "notify_big_moves";
    public static final String KEY_NOTIFY_RELEASES  = "notify_releases";
    // Internal bookkeeping (not shown in UI)
    public static final String KEY_LAST_MOVE_NOTIFIED    = "last_move_notified";
    public static final String KEY_LAST_RELEASE_NOTIFIED = "last_release_notified";

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
    public static boolean gridlinesEnabled(Context c) {
        return getBool(c, KEY_CHART_GRIDLINES, true);
    }
}
