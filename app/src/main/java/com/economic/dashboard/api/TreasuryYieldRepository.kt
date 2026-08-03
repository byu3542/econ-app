package com.economic.dashboard.api

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.economic.dashboard.analyst.EconomicSeriesRegistry
import com.economic.dashboard.database.EconomicHistoryDao
import com.economic.dashboard.database.TreasuryYieldDao
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.models.EconomicHistoryEntry
import com.economic.dashboard.models.TreasuryYield
import com.economic.dashboard.utils.AppExecutors

/**
 * Repository for Treasury Yield data with local caching.
 *
 * Cache Logic:
 * 1. On app open, check cache age via getLastCacheTime()
 * 2. If cache < 24 hours old, return cached data (no API call)
 * 3. If cache >= 24 hours old or missing, call fetchFromAPI() and update cache
 */
class TreasuryYieldRepository(context: Context) {

    private val dao: TreasuryYieldDao = YieldDatabase.getInstance(context).treasuryYieldDao()
    private val historyDao: EconomicHistoryDao =
        YieldDatabase.getInstance(context).economicHistoryDao()
    private val economicRepository: EconomicRepository = EconomicRepository()

    fun getYields(): LiveData<List<EconomicDataPoint>> {
        val cachedData = MediatorLiveData<List<EconomicDataPoint>>()
        cachedData.addSource(dao.getAllYields()) { treasuryYields ->
            val points = ArrayList<EconomicDataPoint>()
            if (treasuryYields != null) {
                for (ty in treasuryYields) {
                    points.add(ty.toEconomicDataPoint())
                }
            }
            cachedData.value = points
        }
        checkCacheAndRefreshIfNeeded()
        return cachedData
    }

    private fun checkCacheAndRefreshIfNeeded() {
        AppExecutors.diskIO().execute {
            try {
                val lastCacheTime = dao.getLastCacheTimeSync()
                val currentTime = System.currentTimeMillis()
                val isCacheStale = (currentTime - lastCacheTime) >= CACHE_EXPIRY_MILLIS
                if (lastCacheTime == 0L) {
                    Log.d(TAG, "No cache found. Fetching from API...")
                    fetchFromAPI()
                } else if (isCacheStale) {
                    Log.d(TAG, "Cache is stale. Refreshing...")
                    fetchFromAPI()
                } else {
                    Log.d(TAG, "Using fresh cache (age: " + (currentTime - lastCacheTime) / 1000 / 3600 + " hours)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking cache freshness", e)
            }
        }
    }

    fun fetchFromAPI() {
        economicRepository.fetchTreasuryRates(object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
            override fun onSuccess(data: List<EconomicDataPoint>) {
                Log.d(TAG, "API fetch successful. Updating cache with " + data.size + " yields.")
                updateCache(data)
            }

            override fun onError(error: String) {
                Log.e(TAG, "API fetch failed: $error. Using cached data as fallback.")
            }
        })
    }

    private fun updateCache(economicDataPoints: List<EconomicDataPoint>) {
        AppExecutors.diskIO().execute {
            try {
                updateCacheBlocking(economicDataPoints)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating cache", e)
            }
        }
    }

    /** Cache write on the calling thread. Must not be called from the main thread. */
    @Throws(Exception::class)
    private fun updateCacheBlocking(economicDataPoints: List<EconomicDataPoint>) {
        val currentTime = System.currentTimeMillis()
        val yieldEntities = ArrayList<TreasuryYield>()
        for (point in economicDataPoints) {
            yieldEntities.add(TreasuryYield(point, currentTime))
        }
        dao.clear()
        dao.insertAll(yieldEntities)
        Log.d(TAG, "Cache updated with " + yieldEntities.size + " records.")

        mirrorToHistory(economicDataPoints, currentTime)
    }

    /**
     * Fetch + cache write, entirely on the calling thread, returning the points
     * so the caller can mirror them elsewhere. Throws if the fetch fails, so a
     * background caller can distinguish "refreshed" from "did nothing".
     *
     * [forceRefresh] remains the fire-and-forget form for UI callers.
     */
    @Throws(Exception::class)
    fun refreshBlocking(): List<EconomicDataPoint> {
        val points = economicRepository.fetchTreasuryRatesSync()
        updateCacheBlocking(points)
        return points
    }

    /**
     * INTENTIONAL DUPLICATION: the same curve points are also written to
     * economic_history under the UST_* series ids.
     *
     * treasury_yields is a snapshot table — updateCache() clears it on every
     * refresh, so it only ever holds the latest ~30 days. economic_history is
     * an accumulating cache the AI Analyst queries uniformly (get_series,
     * [CHART:...]) alongside FRED/BLS/BEA series; without this mirror the yield
     * curve would be the one dataset the Analyst could not reach through its
     * normal path. There is no extra network call — both writes come from the
     * single XML response fetchTreasuryRates() already downloaded, which is why
     * HistoricalDataRepository skips ApiSource.TREASURY entries entirely.
     *
     * Failures are swallowed: the treasury_yields write above is the one the UI
     * depends on, and the Analyst mirror must never take it down with it.
     */
    private fun mirrorToHistory(points: List<EconomicDataPoint>, cachedAtMillis: Long) {
        try {
            val rows = ArrayList<EconomicHistoryEntry>()
            for (p in points) {
                val entry = EconomicSeriesRegistry.treasuryEntryForMaturity(p.series) ?: continue
                if (p.date.length < 10) continue // needs a full "yyyy-MM-dd" key
                rows.add(
                    EconomicHistoryEntry(
                        entry.id, entry.chartLabel, entry.category,
                        p.date, p.value, entry.unit, cachedAtMillis
                    )
                )
            }
            if (rows.isEmpty()) {
                Log.w(TAG, "No curve points mapped to UST_* history rows")
                return
            }
            historyDao.insertAll(rows)
            Log.d(TAG, "Mirrored " + rows.size + " curve points into economic_history")
        } catch (e: Exception) {
            Log.e(TAG, "Error mirroring curve into economic_history", e)
        }
    }

    fun forceRefresh() {
        Log.d(TAG, "Force refresh triggered")
        fetchFromAPI()
    }

    fun clearCache() {
        AppExecutors.diskIO().execute {
            try {
                dao.clear()
                Log.d(TAG, "Cache cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing cache", e)
            }
        }
    }

    fun getCacheAgeMinutes(): Long {
        val lastCacheTime = dao.getLastCacheTimeSync()
        if (lastCacheTime == 0L) return -1
        return (System.currentTimeMillis() - lastCacheTime) / 1000 / 60
    }

    companion object {
        private const val TAG = "TreasuryYieldRepository"
        private const val CACHE_EXPIRY_MILLIS = 24 * 60 * 60 * 1000L // 24 hours
    }
}
