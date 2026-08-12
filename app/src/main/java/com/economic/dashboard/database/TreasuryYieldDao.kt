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
     * Synchronous version of getAllYields for use in background threads.
     * Use this in Worker tasks and background services.
     */
    @Query("SELECT * FROM treasury_yields ORDER BY date DESC, series ASC")
    fun getAllYieldsSync(): List<TreasuryYield>

    /**
     * Most recent cache timestamp across all records, for the staleness check.
     * Synchronous — background threads only. Returns 0 if no data exists.
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
