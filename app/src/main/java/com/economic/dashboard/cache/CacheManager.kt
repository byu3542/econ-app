package com.economic.dashboard.cache

import android.content.Context
import android.util.Log
import com.economic.dashboard.api.HistoricalDataRepository
import com.economic.dashboard.api.TreasuryYieldRepository
import com.economic.dashboard.database.EconomicHistoryDao
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.utils.AppExecutors
import java.time.LocalDate
import java.time.ZoneId

/**
 * Central facade for all cache operations in the app.
 *
 * Responsibilities:
 *   - Staleness checks (is the 24-month history cache older than 7 days?)
 *   - Cache status reporting ("Last updated: 2 hours ago", row counts)
 *   - Manual refresh (force re-fetch all series from FRED/BLS/BEA + Treasury)
 *   - Manual clear (wipe all cached economic data)
 *   - Retention cleanup (purge rows older than 26 months — called weekly
 *     by CacheCleanupWorker)
 *
 * Threading: methods taking a callback run their work on a background thread
 * and invoke the callback on that background thread. UI callers must post
 * back to the main thread (e.g. runOnUiThread).
 *
 * The pure-logic functions (isStale, computeCutoffDate, formatAge)
 * have no Android dependencies beyond android.util.Log-free code paths,
 * so they are unit-testable on the JVM.
 */
object CacheManager {

    private const val TAG = "CacheManager"

    /** History cache is considered stale after this many days. */
    @JvmField
    val STALE_AFTER_DAYS = 7L

    /** Rows with observation dates older than this many months are purged. */
    @JvmField
    val RETENTION_MONTHS = 26

    // ─────────────────────────────────────────────────────────────────────────
    // Pure logic (unit-testable, no Android framework needed)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param lastCacheMillis epoch millis of the most recent cache write (0 = empty)
     * @param nowMillis       current epoch millis
     * @return true if the cache is empty or older than STALE_AFTER_DAYS
     */
    @JvmStatic
    fun isStale(lastCacheMillis: Long, nowMillis: Long): Boolean {
        if (lastCacheMillis <= 0) return true
        val ageMs = nowMillis - lastCacheMillis
        return ageMs >= STALE_AFTER_DAYS * 24L * 60 * 60 * 1000
    }

    /**
     * Compute the retention cutoff date: RETENTION_MONTHS before the given date.
     * Rows with date &lt; this value should be deleted.
     *
     * @param today the reference date (normally LocalDate.now())
     * @return cutoff in "YYYY-MM-DD" format, comparable lexicographically
     *         against the date column in economic_history
     */
    @JvmStatic
    fun computeCutoffDate(today: LocalDate): String =
        today.minusMonths(RETENTION_MONTHS.toLong()).toString()

    /**
     * Human-readable age string for the cache status indicator.
     * Examples: "just now", "35 minutes ago", "2 hours ago", "3 days ago".
     *
     * @param ageMs milliseconds since the last cache write; negative treated as 0
     */
    @JvmStatic
    fun formatAge(ageMsIn: Long): String {
        val ageMs = if (ageMsIn < 0) 0 else ageMsIn
        val minutes = ageMs / 60_000
        if (minutes < 1) return "just now"
        if (minutes < 60) return minutes.toString() + (if (minutes == 1L) " minute ago" else " minutes ago")
        val hours = minutes / 60
        if (hours < 24) return hours.toString() + (if (hours == 1L) " hour ago" else " hours ago")
        val days = hours / 24
        return days.toString() + (if (days == 1L) " day ago" else " days ago")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache status
    // ─────────────────────────────────────────────────────────────────────────

    /** Snapshot of the history cache's state, for UI display and logging. */
    class CacheStatus(
        @JvmField val lastUpdatedMillis: Long, // 0 if cache empty
        @JvmField val rowCount: Int,
        @JvmField val seriesCount: Int,
        @JvmField val oldestDate: String?,      // null if empty
        @JvmField val newestDate: String?       // null if empty
    ) {
        fun isEmpty(): Boolean = rowCount == 0

        /** e.g. "Data updated 2 hours ago" or "No cached data" */
        fun toDisplayString(): String {
            if (isEmpty()) return "No cached data"
            return "Data updated " + formatAge(System.currentTimeMillis() - lastUpdatedMillis)
        }
    }

    fun interface StatusCallback {
        /** Invoked on a background thread. */
        fun onStatus(status: CacheStatus)
    }

    fun interface OperationCallback {
        /** Invoked on a background thread when the operation finishes. */
        fun onComplete(success: Boolean)
    }

    /**
     * Read cache statistics off the main thread and deliver them via callback.
     */
    @JvmStatic
    fun getStatus(context: Context, callback: StatusCallback) {
        AppExecutors.diskIO().execute {
            try {
                val dao: EconomicHistoryDao = YieldDatabase.getInstance(context).economicHistoryDao()
                val status = CacheStatus(
                    dao.getLastCacheTimeSync(),
                    dao.getCount(),
                    dao.getSeriesCount(),
                    dao.getOldestDate(),
                    dao.getNewestDate()
                )
                callback.onStatus(status)
            } catch (e: Exception) {
                Log.e(TAG, "getStatus failed", e)
                callback.onStatus(CacheStatus(0, 0, 0, null, null))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Refresh / clear
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Refresh the 24-month history cache only if it is empty or stale.
     * Cheap to call on every app launch — skips the network entirely when fresh.
     */
    @JvmStatic
    fun refreshIfStale(context: Context, callback: OperationCallback?) {
        HistoricalDataRepository.refreshIfStale(context) { anySucceeded ->
            callback?.onComplete(anySucceeded)
        }
    }

    /**
     * Force-refresh ALL cached economic data regardless of age:
     * the 24-month history (FRED/BLS/BEA) and the treasury yield snapshot.
     * Used by the "Refresh Data" menu action.
     */
    @JvmStatic
    fun forceRefreshAll(context: Context, callback: OperationCallback?) {
        Log.d(TAG, "Force refresh requested")
        // Treasury yield snapshot (fire and forget; it manages its own threading)
        try {
            TreasuryYieldRepository(context.applicationContext).forceRefresh()
        } catch (e: Exception) {
            Log.e(TAG, "Treasury force refresh failed", e)
        }
        // 24-month history — drives the completion callback
        HistoricalDataRepository.forceRefresh(context) { anySucceeded ->
            Log.d(TAG, "Force refresh complete. Success: $anySucceeded")
            callback?.onComplete(anySucceeded)
        }
    }

    /**
     * Wipe all cached economic data (history + treasury yields).
     * Used by the "Clear Cache" menu action. Data will be re-fetched from the
     * APIs on next refresh, so this is always safe.
     */
    @JvmStatic
    fun clearAllCaches(context: Context, callback: OperationCallback?) {
        AppExecutors.diskIO().execute {
            var ok = true
            try {
                val db = YieldDatabase.getInstance(context)
                db.economicHistoryDao().clearAll()
                db.treasuryYieldDao().clear()
                DashboardDataCache.clear(context) // dashboard snapshot cache
                Log.d(TAG, "All caches cleared")
            } catch (e: Exception) {
                Log.e(TAG, "clearAllCaches failed", e)
                ok = false
            }
            callback?.onComplete(ok)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retention cleanup (called by CacheCleanupWorker)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delete history rows older than RETENTION_MONTHS and log cache stats.
     * Must be called from a background thread (CacheCleanupWorker.doWork()
     * already runs on one).
     *
     * @return number of rows deleted, or -1 on error
     */
    @JvmStatic
    fun purgeOldData(context: Context): Int {
        return try {
            val dao = YieldDatabase.getInstance(context).economicHistoryDao()
            val cutoff = computeCutoffDate(LocalDate.now(ZoneId.systemDefault()))
            val deleted = dao.deleteOlderThan(cutoff)
            Log.d(TAG, "Purged " + deleted + " rows older than " + cutoff
                + ". Remaining: " + dao.getCount() + " rows across "
                + dao.getSeriesCount() + " series ("
                + dao.getOldestDate() + " → " + dao.getNewestDate() + ")")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "purgeOldData failed", e)
            -1
        }
    }
}
