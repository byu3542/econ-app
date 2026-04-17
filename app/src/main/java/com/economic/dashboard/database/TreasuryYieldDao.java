package com.economic.dashboard.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.economic.dashboard.models.TreasuryYield;

import java.util.List;

/**
 * Data Access Object for TreasuryYield entities.
 * Provides methods to insert, retrieve, and clear cached Treasury yield data.
 * All queries return LiveData for reactive, lifecycle-aware data updates.
 */
@Dao
public interface TreasuryYieldDao {

    /**
     * Insert or replace all Treasury yield records.
     * Uses REPLACE strategy to handle duplicates by timestamp.
     *
     * @param yields List of TreasuryYield entities to cache
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<TreasuryYield> yields);

    /**
     * Retrieve all cached Treasury yield records.
     * Returns LiveData so UI automatically updates when cache changes.
     *
     * @return LiveData<List<TreasuryYield>> of all cached yields
     */
    @Query("SELECT * FROM treasury_yields ORDER BY date DESC, series ASC")
    LiveData<List<TreasuryYield>> getAllYields();

    /**
     * Retrieve the most recent cache timestamp across all records.
     * Used to check if cache is stale (> 24 hours old).
     *
     * @return LiveData<Long> of the maximum cachedAtMillis value, or null if no data
     */
    @Query("SELECT MAX(cachedAtMillis) FROM treasury_yields")
    LiveData<Long> getLastCacheTime();

    /**
     * Get count of cached records.
     * Useful for checking if cache is empty.
     *
     * @return LiveData<Integer> count of cached records
     */
    @Query("SELECT COUNT(*) FROM treasury_yields")
    LiveData<Integer> getCacheCount();

    /**
     * Synchronous version of getAllYields for use in background threads.
     * Use this in Worker tasks and background services.
     *
     * @return List<TreasuryYield> of all cached yields (blocking call)
     */
    @Query("SELECT * FROM treasury_yields ORDER BY date DESC, series ASC")
    List<TreasuryYield> getAllYieldsSync();

    /**
     * Synchronous version of getLastCacheTime for background operations.
     * Returns 0 if no data exists.
     *
     * @return long timestamp of last cache, or 0 if empty
     */
    @Query("SELECT MAX(cachedAtMillis) FROM treasury_yields")
    long getLastCacheTimeSync();

    /**
     * Clear all cached Treasury yield data.
     * Used for cache invalidation or when user requests a fresh fetch.
     */
    @Query("DELETE FROM treasury_yields")
    void clear();
}
