package com.economic.dashboard.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.economic.dashboard.models.EconomicHistoryEntry;

import java.util.List;

/**
 * DAO for the economic_history table.
 *
 * All methods are synchronous — callers must run them on a background thread.
 * (Use executor, WorkManager, or a plain new Thread, consistent with this project's
 * existing EconomicRepository pattern.)
 */
@Dao
public interface EconomicHistoryDao {

    /**
     * Upsert a batch of history entries.
     * REPLACE conflict strategy updates the row if (seriesId, date) already exists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EconomicHistoryEntry> entries);

    /**
     * Return all entries for a given series, sorted oldest → newest.
     * Used by HistoricalContextBuilder to compute ranges and trends.
     */
    @Query("SELECT * FROM economic_history WHERE seriesId = :seriesId ORDER BY date ASC")
    List<EconomicHistoryEntry> getSeriesSync(String seriesId);

    /**
     * Return the most recent cachedAtMillis across ALL series.
     * Used to decide whether a full refresh is needed (7-day staleness check).
     *
     * @return 0 if the table is empty.
     */
    @Query("SELECT COALESCE(MAX(cachedAtMillis), 0) FROM economic_history")
    long getLastCacheTimeSync();

    /**
     * Return the most recent cachedAtMillis for a single series.
     * Useful for per-series staleness if series are fetched independently.
     *
     * @return 0 if no rows exist for this series.
     */
    @Query("SELECT COALESCE(MAX(cachedAtMillis), 0) FROM economic_history WHERE seriesId = :seriesId")
    long getLastCacheTimeForSeries(String seriesId);

    /**
     * Total number of cached rows across all series.
     * A value of 0 means the cache is completely empty.
     */
    @Query("SELECT COUNT(*) FROM economic_history")
    int getCount();

    /**
     * Wipe all cached history. Called when a manual refresh is forced
     * or on database migration fallback.
     */
    @Query("DELETE FROM economic_history")
    void clearAll();

    /**
     * Wipe entries for a single series (partial refresh).
     */
    @Query("DELETE FROM economic_history WHERE seriesId = :seriesId")
    void deleteSeries(String seriesId);
}
