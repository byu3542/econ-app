package com.economic.dashboard.cache

import android.content.Context
import android.content.SharedPreferences

import com.economic.dashboard.models.EconomicDataPoint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import java.lang.reflect.Type

/**
 * Lightweight snapshot cache for the live dashboard series.
 *
 * The [com.economic.dashboard.database.EconomicHistoryDao] Room history
 * table backs the AI Analyst and only holds 6 long-history series under their
 * FRED IDs. The dashboard, by contrast, consumes ~7 differently-named series
 * and just needs the last-seen values to paint instantly on a warm launch.
 *
 * This class serializes each series' `List<EconomicDataPoint>` to
 * SharedPreferences as JSON. It is intentionally simple (no Room migration,
 * no schema) because it is a display cache, not a source of truth — the
 * network refresh overwrites it on every successful fetch.
 */
object DashboardDataCache {

    private const val PREFS = "dashboard_snapshot_cache"
    private const val KEY_TIME = "__cache_time"

    private val GSON = Gson()
    private val LIST_TYPE: Type =
        object : TypeToken<List<EconomicDataPoint>>() {}.type

    private fun prefs(c: Context): SharedPreferences {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Persist a series snapshot and stamp the cache time. No-op for null/empty. */
    @JvmStatic
    fun save(c: Context?, key: String?, data: List<EconomicDataPoint>?) {
        if (c == null || key == null || data == null || data.isEmpty()) return
        try {
            prefs(c).edit()
                .putString(key, GSON.toJson(data, LIST_TYPE))
                .putLong(KEY_TIME, System.currentTimeMillis())
                .apply()
        } catch (ignored: Exception) {
            // A cache write must never crash a fetch.
        }
    }

    /** Load a cached series snapshot, or null if absent/corrupt. */
    @JvmStatic
    fun load(c: Context?, key: String?): List<EconomicDataPoint>? {
        if (c == null || key == null) return null
        val json = prefs(c).getString(key, null) ?: return null
        return try {
            GSON.fromJson(json, LIST_TYPE)
        } catch (e: Exception) {
            null
        }
    }

    /** True once any series has been cached at least once. */
    @JvmStatic
    fun hasAny(c: Context?): Boolean {
        return c != null && prefs(c).contains(KEY_TIME)
    }

    /** Epoch millis of the most recent cache write, or 0 if empty. */
    @JvmStatic
    fun lastUpdated(c: Context?): Long {
        return if (c == null) 0 else prefs(c).getLong(KEY_TIME, 0)
    }

    /** Wipe the snapshot cache (mirrors a full data clear). */
    @JvmStatic
    fun clear(c: Context?) {
        if (c != null) prefs(c).edit().clear().apply()
    }
}
