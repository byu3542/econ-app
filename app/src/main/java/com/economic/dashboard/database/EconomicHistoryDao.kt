package com.economic.dashboard.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.economic.dashboard.models.EconomicHistoryEntry

/**
 * DAO for the economic_history table.
 *
 * All methods are synchronous — callers must run them on a background thread.
 * (Use executor, WorkManager, or a plain new Thread, consistent with this project's
 * existing EconomicRepository pattern.)
 */
/** Result row for [EconomicHistoryDao.getLastCacheTimesPerSeries]. */
data class SeriesCacheTime(val seriesId: String, val maxTime: Long)

@Dao
interface EconomicHistoryDao {

    /**
     * Upsert a batch of history entries.
     * REPLACE conflict strategy updates the row if (seriesId, date) already exists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<EconomicHistoryEntry>)

    /**
     * Return all entries for a given series, sorted oldest → newest.
     * Used by HistoricalContextBuilder to compute ranges and trends.
     */
    @Query("SELECT * FROM economic_history WHERE seriesId = :seriesId ORDER BY date ASC")
    fun getSeriesSync(seriesId: String): List<EconomicHistoryEntry>

    /**
     * Return the most recent cachedAtMillis across ALL series.
     * Used to decide whether a full refresh is needed (7-day staleness check).
     *
     * @return 0 if the table is empty.
     */
    @Query("SELECT COALESCE(MAX(cachedAtMillis), 0) FROM economic_history")
    fun getLastCacheTimeSync(): Long

    /**
     * Return the most recent cachedAtMillis for a single series.
     * Useful for per-series staleness if series are fetched independently.
     *
     * @return 0 if no rows exist for this series.
     */
    @Query("SELECT COALESCE(MAX(cachedAtMillis), 0) FROM economic_history WHERE seriesId = :seriesId")
    fun getLastCacheTimeForSeries(seriesId: String): Long

    /**
     * Newest cachedAtMillis per series in one query — the batched form of
     * [getLastCacheTimeForSeries] for the per-launch staleness sweep over the
     * whole catalog (series absent from the table are simply absent here;
     * callers default them to 0).
     */
    @Query("SELECT seriesId, MAX(cachedAtMillis) AS maxTime FROM economic_history GROUP BY seriesId")
    fun getLastCacheTimesPerSeries(): List<SeriesCacheTime>

    /**
     * Total number of cached rows across all series.
     * A value of 0 means the cache is completely empty.
     */
    @Query("SELECT COUNT(*) FROM economic_history")
    fun getCount(): Int

    /**
     * Wipe all cached history. Called when a manual refresh is forced
     * or on database migration fallback.
     */
    @Query("DELETE FROM economic_history")
    fun clearAll()

    /**
     * Wipe entries for a single series (partial refresh).
     */
    @Query("DELETE FROM economic_history WHERE seriesId = :seriesId")
    fun deleteSeries(seriesId: String)

    // ── Cleanup & cache statistics (used by CacheManager / CacheCleanupWorker) ──

    /**
     * Delete rows whose observation date is older than the cutoff.
     * Dates are stored as "YYYY-MM-DD" strings, so lexicographic comparison
     * is equivalent to chronological comparison.
     *
     * @param cutoffDate e.g. "2024-04-12" (26 months before today)
     * @return number of rows deleted
     */
    @Query("DELETE FROM economic_history WHERE date < :cutoffDate")
    fun deleteOlderThan(cutoffDate: String): Int

    /** Oldest observation date in the cache, or null if empty. */
    @Query("SELECT MIN(date) FROM economic_history")
    fun getOldestDate(): String?

    /** Newest observation date in the cache, or null if empty. */
    @Query("SELECT MAX(date) FROM economic_history")
    fun getNewestDate(): String?

    /** Number of distinct series currently cached. */
    @Query("SELECT COUNT(DISTINCT seriesId) FROM economic_history")
    fun getSeriesCount(): Int
}
