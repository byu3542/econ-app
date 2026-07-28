package com.economic.dashboard.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.economic.dashboard.models.TreasuryYield

/**
 * Data Access Object for TreasuryYield entities.
 * Provides methods to insert, retrieve, and clear cached Treasury yield data.
 * All queries return LiveData for reactive, lifecycle-aware data updates.
 */
@Dao
interface TreasuryYieldDao {

    /**
     * Insert or replace all Treasury yield records.
     * Uses REPLACE strategy to handle duplicates by timestamp.
     *
     * @param yields List of TreasuryYield entities to cache
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(yields: List<TreasuryYield>)

    /**
     * Retrieve all cached Treasury yield records.
     * Returns LiveData so UI automatically updates when cache changes.
     */
    @Query("SELECT * FROM treasury_yields ORDER BY date DESC, series ASC")
    fun getAllYields(): LiveData<List<TreasuryYield>>

    /**
     * Retrieve the most recent cache timestamp across all records.
     * Used to check if cache is stale (> 24 hours old).
     */
    @Query("SELECT MAX(cachedAtMillis) FROM treasury_yields")
    fun getLastCacheTime(): LiveData<Long>

    /**
     * Get count of cached records.
     * Useful for checking if cache is empty.
     */
    @Query("SELECT COUNT(*) FROM treasury_yields")
    fun getCacheCount(): LiveData<Int>

    /**
     * Synchronous version of getAllYields for use in background threads.
     * Use this in Worker tasks and background services.
     */
    @Query("SELECT * FROM treasury_yields ORDER BY date DESC, series ASC")
    fun getAllYieldsSync(): List<TreasuryYield>

    /**
     * Synchronous version of getLastCacheTime for background operations.
     * Returns 0 if no data exists.
     */
    @Query("SELECT MAX(cachedAtMillis) FROM treasury_yields")
    fun getLastCacheTimeSync(): Long

    /**
     * Clear all cached Treasury yield data.
     * Used for cache invalidation or when user requests a fresh fetch.
     */
    @Query("DELETE FROM treasury_yields")
    fun clear()
}
