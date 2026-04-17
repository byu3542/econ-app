package com.economic.dashboard.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.economic.dashboard.api.TreasuryYieldRepository;

/**
 * Background Worker for scheduled Treasury Yield refresh.
 *
 * Lifecycle:
 * - Scheduled by WorkManager to run daily at approximately 2:30 PM MST
 * - Calls TreasuryYieldRepository.forceRefresh() to update cache
 * - If API succeeds: Cache is updated, UI automatically reflects new data
 * - If API fails: Cached data remains available (graceful degradation)
 *
 * WorkManager handles:
 * - Scheduling accuracy (~15 minutes, no special permissions needed)
 * - Device constraints (battery, network, charging state)
 * - Retries on failure (configurable via ExistingPeriodicWorkPolicy)
 * - Deduplication (only one refresh task runs at a time)
 */
public class YieldRefreshWorker extends Worker {

    private static final String TAG = "YieldRefreshWorker";

    public YieldRefreshWorker(
            @NonNull Context context,
            @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Perform the background refresh task.
     * This runs in a background thread managed by WorkManager.
     *
     * @return Result.success() if refresh completes (even if API fails)
     *         Result.retry() to reschedule this attempt
     */
    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "YieldRefreshWorker triggered at " + System.currentTimeMillis());

        try {
            TreasuryYieldRepository repository = new TreasuryYieldRepository(getApplicationContext());
            repository.forceRefresh();
            Log.d(TAG, "Yield refresh completed successfully");

            // Return success even if API call fails asynchronously
            // (cache is still available as fallback)
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Yield refresh failed with exception", e);
            // Retry this worker (WorkManager will reschedule with exponential backoff)
            return Result.retry();
        }
    }
}
