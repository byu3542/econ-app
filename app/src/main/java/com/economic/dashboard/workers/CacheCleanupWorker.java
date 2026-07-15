package com.economic.dashboard.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.economic.dashboard.cache.CacheManager;

import java.util.concurrent.TimeUnit;

/**
 * Weekly maintenance worker for the economic data cache.
 *
 * Each run:
 *   1. Deletes economic_history rows older than 26 months
 *      (24-month window + 2-month buffer for edge cases)
 *   2. Logs cache statistics (row count, series count, date range)
 *
 * Scheduling:
 *   - Runs every 7 days via WorkManager (periodic, KEEP policy → no duplicates)
 *   - requiresDeviceIdle constraint pushes execution to off-peak times
 *     (overnight, device unused) without needing exact alarms or permissions
 *   - Survives app restarts and device reboots (WorkManager persists jobs)
 */
public class CacheCleanupWorker extends Worker {

    private static final String TAG = "CacheCleanupWorker";
    private static final String WORK_ID = "cache_cleanup_weekly";

    public CacheCleanupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Weekly cache cleanup started");
        int deleted = CacheManager.purgeOldData(getApplicationContext());
        if (deleted < 0) {
            // DB error — let WorkManager retry with backoff
            return Result.retry();
        }
        Log.d(TAG, "Weekly cache cleanup finished. Rows purged: " + deleted);
        return Result.success();
    }

    /**
     * Schedule the weekly cleanup. Safe to call on every app start —
     * KEEP policy means an already-scheduled job is left untouched.
     */
    public static void scheduleWeekly(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiresDeviceIdle(true)   // run while device is unused (off-peak)
                    .setRequiresBatteryNotLow(true)
                    .build();

            PeriodicWorkRequest request =
                    new PeriodicWorkRequest.Builder(CacheCleanupWorker.class, 7, TimeUnit.DAYS)
                            .setConstraints(constraints)
                            .build();

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_ID,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request);

            Log.d(TAG, "Weekly cache cleanup scheduled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule cache cleanup", e);
        }
    }
}
