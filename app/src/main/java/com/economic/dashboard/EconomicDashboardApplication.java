package com.economic.dashboard;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import com.economic.dashboard.cache.CacheManager;
import com.economic.dashboard.news.NewsRepository;
import com.economic.dashboard.utils.NotificationHelper;
import com.economic.dashboard.utils.SettingsManager;
import com.economic.dashboard.workers.CacheCleanupWorker;
import com.economic.dashboard.workers.ReleaseReminderWorker;
import com.economic.dashboard.workers.YieldRefreshScheduler;

/**
 * Application class for global initialization.
 * Called once when the app process starts.
 *
 * Initializes:
 *   1. Saved theme (light/dark/system) — applied before any Activity inflates
 *   2. News repository context (for the news-source settings)
 *   3. Notification channel for economic alerts
 *   4. Daily Treasury Yield refresh scheduler (2:30 PM MST)
 *   5. Daily data-release reminder check (~7:30 AM local)
 *   6. Weekly cache cleanup (purges history rows older than 26 months)
 *   7. Background population/refresh of the 24-month history cache
 *      (no-op if cache is fresh — see CacheManager.STALE_AFTER_DAYS)
 */
public class EconomicDashboardApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Apply saved theme before any UI is created. Defaults to following
        // the system setting until the user picks one in Settings.
        AppCompatDelegate.setDefaultNightMode(SettingsManager.getNightMode(this));

        // Give the news repository a context so it can read the
        // news-source toggles from settings.
        NewsRepository.init(this);

        // Channel for big-move alerts and release reminders (idempotent)
        NotificationHelper.createChannel(this);

        // Schedule daily Treasury Yield refresh at 2:30 PM MST
        // Safe to call multiple times (uses KEEP policy to prevent duplicates)
        YieldRefreshScheduler.scheduleDailyYieldRefresh(this);

        // Daily morning check for scheduled data releases (jobs report)
        ReleaseReminderWorker.scheduleDaily(this);

        // Schedule weekly cache cleanup (KEEP policy — idempotent)
        CacheCleanupWorker.scheduleWeekly(this);

        // Populate/refresh the 24-month history cache in the background.
        // Skips the network entirely if the cache is < 7 days old.
        CacheManager.refreshIfStale(this, null);
    }
}
