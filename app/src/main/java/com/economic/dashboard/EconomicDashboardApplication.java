package com.economic.dashboard;

import android.app.Application;
import com.economic.dashboard.cache.CacheManager;
import com.economic.dashboard.workers.CacheCleanupWorker;
import com.economic.dashboard.workers.YieldRefreshScheduler;

/**
 * Application class for global initialization.
 * Called once when the app process starts.
 *
 * Initializes:
 *   1. Daily Treasury Yield refresh scheduler (2:30 PM MST)
 *   2. Weekly cache cleanup (purges history rows older than 26 months)
 *   3. Background population/refresh of the 24-month history cache
 *      (no-op if cache is fresh — see CacheManager.STALE_AFTER_DAYS)
 */
public class EconomicDashboardApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Schedule daily Treasury Yield refresh at 2:30 PM MST
        // Safe to call multiple times (uses KEEP policy to prevent duplicates)
        YieldRefreshScheduler.scheduleDailyYieldRefresh(this);

        // Schedule weekly cache cleanup (KEEP policy — idempotent)
        CacheCleanupWorker.scheduleWeekly(this);

        // Populate/refresh the 24-month history cache in the background.
        // Skips the network entirely if the cache is < 7 days old.
        CacheManager.refreshIfStale(this, null);
    }
}
