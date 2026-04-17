package com.economic.dashboard;

import android.app.Application;
import com.economic.dashboard.workers.YieldRefreshScheduler;

/**
 * Application class for global initialization.
 * Called once when the app process starts.
 *
 * Initializes the daily Treasury Yield refresh scheduler.
 */
public class EconomicDashboardApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Schedule daily Treasury Yield refresh at 2:30 PM MST
        // Safe to call multiple times (uses KEEP policy to prevent duplicates)
        YieldRefreshScheduler.scheduleDailyYieldRefresh(this);
    }
}
