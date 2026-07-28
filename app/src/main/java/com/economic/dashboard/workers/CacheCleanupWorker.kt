package com.economic.dashboard.workers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.economic.dashboard.cache.CacheManager
import java.util.concurrent.TimeUnit

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
class CacheCleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d(TAG, "Weekly cache cleanup started")
        val deleted = CacheManager.purgeOldData(applicationContext)
        if (deleted < 0) {
            // DB error — let WorkManager retry with backoff
            return Result.retry()
        }
        Log.d(TAG, "Weekly cache cleanup finished. Rows purged: $deleted")
        return Result.success()
    }

    companion object {
        private const val TAG = "CacheCleanupWorker"
        private const val WORK_ID = "cache_cleanup_weekly"

        /**
         * Schedule the weekly cleanup. Safe to call on every app start —
         * KEEP policy means an already-scheduled job is left untouched.
         */
        @JvmStatic
        fun scheduleWeekly(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiresDeviceIdle(true) // run while device is unused (off-peak)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val request = PeriodicWorkRequest.Builder(CacheCleanupWorker::class.java, 7, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_ID,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

                Log.d(TAG, "Weekly cache cleanup scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule cache cleanup", e)
            }
        }
    }
}
