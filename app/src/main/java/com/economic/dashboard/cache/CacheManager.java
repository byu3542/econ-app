package com.economic.dashboard.cache;

import android.content.Context;
import android.util.Log;

import com.economic.dashboard.api.HistoricalDataRepository;
import com.economic.dashboard.api.TreasuryYieldRepository;
import com.economic.dashboard.database.EconomicHistoryDao;
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.utils.AppExecutors;

import java.time.LocalDate;
import java.time.ZoneId;

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
 * The pure-logic static methods (isStale, computeCutoffDate, formatAge)
 * have no Android dependencies beyond android.util.Log-free code paths,
 * so they are unit-testable on the JVM.
 */
public final class CacheManager {

    private static final String TAG = "CacheManager";

    /** History cache is considered stale after this many days. */
    public static final long STALE_AFTER_DAYS = 7;

    /** Rows with observation dates older than this many months are purged. */
    public static final int RETENTION_MONTHS = 26;

    private CacheManager() {} // static utility — no instances

    // ─────────────────────────────────────────────────────────────────────────
    // Pure logic (unit-testable, no Android framework needed)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param lastCacheMillis epoch millis of the most recent cache write (0 = empty)
     * @param nowMillis       current epoch millis
     * @return true if the cache is empty or older than STALE_AFTER_DAYS
     */
    public static boolean isStale(long lastCacheMillis, long nowMillis) {
        if (lastCacheMillis <= 0) return true;
        long ageMs = nowMillis - lastCacheMillis;
        return ageMs >= STALE_AFTER_DAYS * 24L * 60 * 60 * 1000;
    }

    /**
     * Compute the retention cutoff date: RETENTION_MONTHS before the given date.
     * Rows with date &lt; this value should be deleted.
     *
     * @param today the reference date (normally LocalDate.now())
     * @return cutoff in "YYYY-MM-DD" format, comparable lexicographically
     *         against the date column in economic_history
     */
    public static String computeCutoffDate(LocalDate today) {
        return today.minusMonths(RETENTION_MONTHS).toString();
    }

    /**
     * Human-readable age string for the cache status indicator.
     * Examples: "just now", "35 minutes ago", "2 hours ago", "3 days ago".
     *
     * @param ageMs milliseconds since the last cache write; negative treated as 0
     */
    public static String formatAge(long ageMs) {
        if (ageMs < 0) ageMs = 0;
        long minutes = ageMs / 60_000;
        if (minutes < 1)   return "just now";
        if (minutes < 60)  return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        long hours = minutes / 60;
        if (hours < 24)    return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache status
    // ─────────────────────────────────────────────────────────────────────────

    /** Snapshot of the history cache's state, for UI display and logging. */
    public static class CacheStatus {
        public final long   lastUpdatedMillis; // 0 if cache empty
        public final int    rowCount;
        public final int    seriesCount;
        public final String oldestDate;        // null if empty
        public final String newestDate;        // null if empty

        CacheStatus(long lastUpdatedMillis, int rowCount, int seriesCount,
                    String oldestDate, String newestDate) {
            this.lastUpdatedMillis = lastUpdatedMillis;
            this.rowCount    = rowCount;
            this.seriesCount = seriesCount;
            this.oldestDate  = oldestDate;
            this.newestDate  = newestDate;
        }

        public boolean isEmpty() { return rowCount == 0; }

        /** e.g. "Data updated 2 hours ago" or "No cached data" */
        public String toDisplayString() {
            if (isEmpty()) return "No cached data";
            return "Data updated " + formatAge(System.currentTimeMillis() - lastUpdatedMillis);
        }
    }

    public interface StatusCallback {
        /** Invoked on a background thread. */
        void onStatus(CacheStatus status);
    }

    public interface OperationCallback {
        /** Invoked on a background thread when the operation finishes. */
        void onComplete(boolean success);
    }

    /**
     * Read cache statistics off the main thread and deliver them via callback.
     */
    public static void getStatus(Context context, StatusCallback callback) {
        AppExecutors.getInstance().diskIO().execute(() -> {
            try {
                EconomicHistoryDao dao =
                        YieldDatabase.getInstance(context).economicHistoryDao();
                CacheStatus status = new CacheStatus(
                        dao.getLastCacheTimeSync(),
                        dao.getCount(),
                        dao.getSeriesCount(),
                        dao.getOldestDate(),
                        dao.getNewestDate());
                callback.onStatus(status);
            } catch (Exception e) {
                Log.e(TAG, "getStatus failed", e);
                callback.onStatus(new CacheStatus(0, 0, 0, null, null));
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Refresh / clear
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Refresh the 24-month history cache only if it is empty or stale.
     * Cheap to call on every app launch — skips the network entirely when fresh.
     */
    public static void refreshIfStale(Context context, OperationCallback callback) {
        HistoricalDataRepository.refreshIfStale(context, anySucceeded -> {
            if (callback != null) callback.onComplete(anySucceeded);
        });
    }

    /**
     * Force-refresh ALL cached economic data regardless of age:
     * the 24-month history (FRED/BLS/BEA) and the treasury yield snapshot.
     * Used by the "Refresh Data" menu action.
     */
    public static void forceRefreshAll(Context context, OperationCallback callback) {
        Log.d(TAG, "Force refresh requested");
        // Treasury yield snapshot (fire and forget; it manages its own threading)
        try {
            new TreasuryYieldRepository(context.getApplicationContext()).forceRefresh();
        } catch (Exception e) {
            Log.e(TAG, "Treasury force refresh failed", e);
        }
        // 24-month history — drives the completion callback
        HistoricalDataRepository.forceRefresh(context, anySucceeded -> {
            Log.d(TAG, "Force refresh complete. Success: " + anySucceeded);
            if (callback != null) callback.onComplete(anySucceeded);
        });
    }

    /**
     * Wipe all cached economic data (history + treasury yields).
     * Used by the "Clear Cache" menu action. Data will be re-fetched from the
     * APIs on next refresh, so this is always safe.
     */
    public static void clearAllCaches(Context context, OperationCallback callback) {
        AppExecutors.getInstance().diskIO().execute(() -> {
            boolean ok = true;
            try {
                YieldDatabase db = YieldDatabase.getInstance(context);
                db.economicHistoryDao().clearAll();
                db.treasuryYieldDao().clear();
                Log.d(TAG, "All caches cleared");
            } catch (Exception e) {
                Log.e(TAG, "clearAllCaches failed", e);
                ok = false;
            }
            if (callback != null) callback.onComplete(ok);
        });
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
    public static int purgeOldData(Context context) {
        try {
            EconomicHistoryDao dao =
                    YieldDatabase.getInstance(context).economicHistoryDao();
            String cutoff = computeCutoffDate(LocalDate.now(ZoneId.systemDefault()));
            int deleted = dao.deleteOlderThan(cutoff);
            Log.d(TAG, "Purged " + deleted + " rows older than " + cutoff
                    + ". Remaining: " + dao.getCount() + " rows across "
                    + dao.getSeriesCount() + " series ("
       