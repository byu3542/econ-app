package com.economic.dashboard.api;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.economic.dashboard.database.TreasuryYieldDao;
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.models.TreasuryYield;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for Treasury Yield data with local caching.
 *
 * Cache Logic:
 * 1. On app open, check cache age via getLastCacheTime()
 * 2. If cache < 24 hours old, return cached data (no API call)
 * 3. If cache >= 24 hours old or missing, call fetchFromAPI() and update cache
 *
 * Background Refresh:
 * - WorkManager task runs daily at ~2:30 PM MST
 * - Task calls fetchFromAPI() directly to refresh cache
 * - If API fails, cached data is still available
 */
public class TreasuryYieldRepository {

    private static final String TAG = "TreasuryYieldRepository";
    private static final long CACHE_EXPIRY_MILLIS = 24 * 60 * 60 * 1000; // 24 hours

    private final TreasuryYieldDao dao;
    private final EconomicRepository economicRepository;

    /**
     * Constructor initializes Room database and economic API repository.
     *
     * @param context Application context
     */
    public TreasuryYieldRepository(Context context) {
        YieldDatabase database = YieldDatabase.getInstance(context);
        this.dao = database.treasuryYieldDao();
        this.economicRepository = new EconomicRepository();
    }

    /**
     * Get Treasury yields with cache-first logic.
     * Returns LiveData that:
     * - Immediately serves cached data if fresh
     * - Triggers API refresh if cache is stale
     * - Updates UI reactively when cache changes
     *
     * Cache freshness check:
     * - If cache exists and is < 24 hours old: use cache (no API call)
     * - If cache is >= 24 hours old or empty: fetch from API
     *
     * @return LiveData<List<EconomicDataPoint>> of treasury yields
     */
    public LiveData<List<EconomicDataPoint>> getYields() {
        // Transform cached TreasuryYield entities back to EconomicDataPoint
        LiveData<List<EconomicDataPoint>> cachedData = Transformations.map(
                dao.getAllYields(),
                treasuryYields -> {
                    List<EconomicDataPoint> points = new ArrayList<>();
                    if (treasuryYields != null) {
                        for (TreasuryYield ty : treasuryYields) {
                            points.add(ty.toEconomicDataPoint());
                        }
                    }
                    return points;
                }
        );

        // Check cache freshness and trigger API call if needed
        checkCacheAndRefreshIfNeeded();

        return cachedData;
    }

    /**
     * Check if cache is stale and refresh from API if needed.
     * This runs asynchronously in a background thread.
     */
    private void checkCacheAndRefreshIfNeeded() {
        new Thread(() -> {
            try {
                long lastCacheTime = dao.getLastCacheTimeSync();
                long currentTime = System.currentTimeMillis();
                boolean isCacheStale = (currentTime - lastCacheTime) >= CACHE_EXPIRY_MILLIS;

                if (lastCacheTime == 0) {
                    Log.d(TAG, "No cache found. Fetching from API...");
                    fetchFromAPI();
                } else if (isCacheStale) {
                    Log.d(TAG, "Cache is stale (>" + CACHE_EXPIRY_MILLIS / 1000 / 3600 + " hours). Refreshing...");
                    fetchFromAPI();
                } else {
                    long ageMillis = currentTime - lastCacheTime;
                    long ageHours = ageMillis / 1000 / 3600;
                    Log.d(TAG, "Using fresh cache (age: " + ageHours + " hours)");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking cache freshness", e);
            }
        }).start();
    }

    /**
     * Fetch Treasury yields from API and update cache.
     * Called by:
     * - checkCacheAndRefreshIfNeeded() when cache is stale
     * - YieldRefreshWorker for scheduled daily refresh
     *
     * On API success: Cache is updated, LiveData observers notified
     * On API failure: Cached data remains available for fallback
     */
    public void fetchFromAPI() {
        economicRepository.fetchTreasuryRates(
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override
                    public void onSuccess(List<EconomicDataPoint> data) {
                        Log.d(TAG, "API fetch successful. Updating cache with " + data.size() + " yields.");
                        updateCache(data);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "API fetch failed: " + error + ". Using cached data as fallback.");
                        // Cached data remains available; UI continues to show stale data
                    }
                }
        );
    }

    /**
     * Update cache with newly fetched data.
     * Converts EconomicDataPoint objects to TreasuryYield entities with timestamp.
     *
     * @param economicDataPoints Fresh data from API
     */
    private void updateCache(List<EconomicDataPoint> economicDataPoints) {
        new Thread(() -> {
            try {
                long currentTime = System.currentTimeMillis();
                List<TreasuryYield> yieldEntities = new ArrayList<>();

                for (EconomicDataPoint point : economicDataPoints) {
                    TreasuryYield entity = new TreasuryYield(point, currentTime);
                    yieldEntities.add(entity);
                }

                dao.insertAll(yieldEntities);
                Log.d(TAG, "Cache updated with " + yieldEntities.size() + " records at " + currentTime);
            } catch (Exception e) {
                Log.e(TAG, "Error updating cache", e);
            }
        }).start();
    }

    /**
     * Force a refresh from API (used by background worker and manual refresh).
     * Useful for pull-to-refresh or manual "Update Now" button.
     */
    public void forceRefresh() {
        Log.d(TAG, "Force refresh triggered");
        fetchFromAPI();
    }

    /**
     * Clear all cached data.
     * Useful for debugging or manual cache invalidation.
     */
    public void clearCache() {
        new Thread(() -> {
            try {
                dao.clear();
                Log.d(TAG, "Cache cleared");
            } catch (Exception e) {
                Log.e(TAG, "Error clearing cache", e);
            }
        }).start();
    }

    /**
     * Get cache freshness information for debugging/display.
     * Returns age in minutes, or -1 if no cache exists.
     */
    public long getCacheAgeMinutes() {
        long lastCacheTime = dao.getLastCacheTimeSync();
        if (lastCacheTime == 0) return -1;
        return (System.currentTimeMillis() - lastCacheTime) / 1000 / 60;
    }
}
