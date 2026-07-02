package com.economic.dashboard.api;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.economic.dashboard.database.TreasuryYieldDao;
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.models.TreasuryYield;
import com.economic.dashboard.utils.AppExecutors;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for Treasury Yield data with local caching.
 *
 * Cache Logic:
 * 1. On app open, check cache age via getLastCacheTime()
 * 2. If cache < 24 hours old, return cached data (no API call)
 * 3. If cache >= 24 hours old or missing, call fetchFromAPI() and update cache
 */
public class TreasuryYieldRepository {

    private static final String TAG = "TreasuryYieldRepository";
    private static final long CACHE_EXPIRY_MILLIS = 24 * 60 * 60 * 1000; // 24 hours

    private final TreasuryYieldDao dao;
    private final EconomicRepository economicRepository;

    public TreasuryYieldRepository(Context context) {
        YieldDatabase database = YieldDatabase.getInstance(context);
        this.dao = database.treasuryYieldDao();
        this.economicRepository = new EconomicRepository();
    }

    public LiveData<List<EconomicDataPoint>> getYields() {
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
        checkCacheAndRefreshIfNeeded();
        return cachedData;
    }

    private void checkCacheAndRefreshIfNeeded() {
        AppExecutors.getInstance().diskIO().execute(() -> {
            try {
                long lastCacheTime = dao.getLastCacheTimeSync();
                long currentTime = System.currentTimeMillis();
                boolean isCacheStale = (currentTime - lastCacheTime) >= CACHE_EXPIRY_MILLIS;
                if (lastCacheTime == 0) {
                    Log.d(TAG, "No cache found. Fetching from API...");
                    fetchFromAPI();
                } else if (isCacheStale) {
                    Log.d(TAG, "Cache is stale. Refreshing...");
                    fetchFromAPI();
                } else {
                    Log.d(TAG, "Using fresh cache (age: " + (currentTime - lastCacheTime)/1000/3600 + " hours)");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking cache freshness", e);
            }
        });
    }

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
                    }
                }
        );
    }

    private void updateCache(List<EconomicDataPoint> economicDataPoints) {
        AppExecutors.getInstance().diskIO().execute(() -> {
            try {
                long currentTime = System.currentTimeMillis();
                List<TreasuryYield> yieldEntities = new ArrayList<>();
                for (EconomicDataPoint point : economicDataPoints) {
                    yieldEntities.add(new TreasuryYield(point, currentTime));
                }
                dao.clear();
                dao.insertAll(yieldEntities);
                Log.d(TAG, "Cache updated with " + yieldEntities.size() + " records.");
            } catch (Exception e) {
                Log.e(TAG, "Error updating cache", e);
            }
        });
    }

    public void forceRefresh() {
        Log.d(TAG, "Force refresh triggered");
        fetchFromAPI();
    }

    public void clearCache() {
        AppExecutors.getInstance().diskIO().execute(() -> {
            try { dao.clear(); Log.d(TAG, "Cache cleared"); }
            catch (Exception e) { Log.e(TAG, "Error clearing cache", e); }
        });
    }

    public long getCacheAgeMinutes() {
        long lastCacheTime = dao.getLastCacheTimeSync();
        if (lastCacheTime == 0) return -1;
        return (System.currentTimeMillis() - lastCacheTime) / 1000 / 60;
    }
}
