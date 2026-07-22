package com.economic.dashboard.cache;

import android.content.Context;
import android.content.SharedPreferences;

import com.economic.dashboard.models.EconomicDataPoint;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Lightweight snapshot cache for the live dashboard series.
 *
 * The {@link com.economic.dashboard.database.EconomicHistoryDao Room history}
 * table backs the AI Analyst and only holds 6 long-history series under their
 * FRED IDs. The dashboard, by contrast, consumes ~7 differently-named series
 * and just needs the last-seen values to paint instantly on a warm launch.
 *
 * This class serializes each series' {@code List<EconomicDataPoint>} to
 * SharedPreferences as JSON. It is intentionally simple (no Room migration,
 * no schema) because it is a display cache, not a source of truth — the
 * network refresh overwrites it on every successful fetch.
 */
public final class DashboardDataCache {

    private static final String PREFS    = "dashboard_snapshot_cache";
    private static final String KEY_TIME = "__cache_time";

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE =
            new TypeToken<List<EconomicDataPoint>>() {}.getType();

    private DashboardDataCache() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Persist a series snapshot and stamp the cache time. No-op for null/empty. */
    public static void save(Context c, String key, List<EconomicDataPoint> data) {
        if (c == null || key == null || data == null || data.isEmpty()) return;
        try {
            prefs(c).edit()
                    .putString(key, GSON.toJson(data, LIST_TYPE))
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {
            // A cache write must never crash a fetch.
        }
    }

    /** Load a cached series snapshot, or null if absent/corrupt. */
    public static List<EconomicDataPoint> load(Context c, String key) {
        if (c == null || key == null) return null;
        String json = prefs(c).getString(key, null);
        if (json == null) return null;
        try {
            return GSON.fromJson(json, LIST_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    /** True once any series has been cached at least once. */
    public static boolean hasAny(Context c) {
        return c != null && prefs(c).contains(KEY_TIME);
    }

    /** Epoch millis of the most recent cache write, or 0 if empty. */
    public static long lastUpdated(Context c) {
        return c == null ? 0 : prefs(c).getLong(KEY_TIME, 0);
    }

    /** Wipe the snapshot cache (mirrors a full data clear). */
    public static void clear(Context c) {
        if (c != null) prefs(c).edit().clear().apply();
    }
}
