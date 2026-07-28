package com.economic.dashboard.api

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import com.economic.dashboard.database.TreasuryYieldDao
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.EconomicDataPoint
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
    private val economicRepository: EconomicRepository = EconomicRepository()

    fun getYields(): LiveData<List<EconomicDataPoint>> {
        val cachedData = Transformations.map(dao.getAllYields()) { treasuryYields ->
            val points = ArrayList<EconomicDataPoint>()
            if (treasuryYields != null) {
                for (ty in treasuryYields) {
                    points.add(ty.toEconomicDataPoint())
                }
            }
            points
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
                val currentTime = System.currentTimeMillis()
                val yieldEntities = ArrayList<TreasuryYield>()
                for (point in economicDataPoints) {
                    yieldEntities.add(TreasuryYield(point, currentTime))
                }
                dao.clear()
                dao.insertAll(yieldEntities)
                Log.d(TAG, "Cache updated with " + yieldEntities.size + " records.")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating cache", e)
            }
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
