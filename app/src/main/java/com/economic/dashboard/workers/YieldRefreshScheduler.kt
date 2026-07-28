package com.economic.dashboard.workers

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Utility class to schedule the daily Treasury Yield refresh task.
 *
 * Scheduling Logic:
 * 1. Target time: 2:30 PM MST (America/Denver timezone)
 * 2. Calculate delay from now until next 2:30 PM
 * 3. If past 2:30 PM today, schedule for tomorrow's 2:30 PM
 * 4. Set to repeat daily after first execution
 * 5. Use unique work queue to prevent duplicate tasks
 *
 * Timing Note:
 * - WorkManager without SCHEDULE_EXACT_ALARM fires within ~15 minutes of scheduled time
 * - Actual execution may be 2:15 PM to 2:45 PM depending on device state and WorkManager constraints
 */
object YieldRefreshScheduler {

    private const val TAG = "YieldRefreshScheduler"
    private const val YIELD_REFRESH_WORK_ID = "yield_refresh_daily"

    /**
     * Schedule daily Treasury Yield refresh at 2:30 PM MST.
     * Safe to call multiple times; uses ExistingPeriodicWorkPolicy.KEEP to prevent duplicates.
     *
     * @param context Application context
     */
    @JvmStatic
    fun scheduleDailyYieldRefresh(context: Context) {
        try {
            // Calculate initial delay until 2:30 PM MST
            val initialDelayMillis = calculateDelayUntilNextRefreshTime()
            val initialDelayMinutes = initialDelayMillis / 1000 / 60

            Log.d(TAG, "Scheduling yield refresh. Initial delay: $initialDelayMinutes minutes")

            // Create a periodic work request:
            // - Runs every 24 hours
            // - First execution after initialDelayMinutes
            // - Uses WorkManager's default constraints (battery, network, etc.)
            val refreshWorkRequest = PeriodicWorkRequest.Builder(
                YieldRefreshWorker::class.java,
                1,
                TimeUnit.DAYS
            )
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .build()

            // Enqueue with KEEP policy: if work already exists, don't replace it
            // This prevents duplicate scheduled tasks
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                YIELD_REFRESH_WORK_ID,
                ExistingPeriodicWorkPolicy.KEEP,
                refreshWorkRequest
            )

            Log.d(TAG, "Yield refresh scheduled successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule yield refresh", e)
        }
    }

    /**
     * Cancel the scheduled daily refresh task.
     * Useful for testing or app settings (user preference to disable auto-refresh).
     *
     * @param context Application context
     */
    @JvmStatic
    fun cancelYieldRefresh(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(YIELD_REFRESH_WORK_ID)
            Log.d(TAG, "Yield refresh cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel yield refresh", e)
        }
    }

    /**
     * Calculate milliseconds until the next 2:30 PM MST.
     *
     * Logic:
     * 1. Get current time in MST (America/Denver)
     * 2. Create target time for today at 2:30 PM
     * 3. If target time is in the future, use it
     * 4. Otherwise, use target time for tomorrow
     *
     * @return Milliseconds until next 2:30 PM MST
     */
    private fun calculateDelayUntilNextRefreshTime(): Long {
        // Use America/Denver for MST (Mountain Standard Time)
        val mstZone = ZoneId.of("America/Denver")
        val now = ZonedDateTime.now(mstZone)

        // Target time: 2:30 PM (14:30)
        val targetTime = LocalTime.of(14, 30)

        // Create ZonedDateTime for today at 2:30 PM MST
        val targetToday = now.toLocalDate()
            .atTime(targetTime)
            .atZone(mstZone)

        // If 2:30 PM has already passed today, schedule for tomorrow
        val nextRefreshTime = if (targetToday.isAfter(now)) targetToday else targetToday.plusDays(1)

        // Calculate delay in milliseconds
        val delayMillis = nextRefreshTime.toInstant().toEpochMilli() -
            now.toInstant().toEpochMilli()

        val delayMinutes = delayMillis / 1000 / 60
        Log.d(TAG, "Next refresh scheduled for: $nextRefreshTime (in $delayMinutes minutes)")

        return delayMillis
    }

    /**
     * Get the timestamp of when the next refresh is scheduled.
     * Useful for displaying to the user or testing.
     *
     * @return Milliseconds since epoch when next refresh will occur
     */
    @JvmStatic
    fun getNextRefreshTimestamp(): Long {
        val mstZone = ZoneId.of("America/Denver")
        val now = ZonedDateTime.now(mstZone)
        val targetTime = LocalTime.of(14, 30)
        val targetToday = now.toLocalDate()
            .atTime(targetTime)
            .atZone(mstZone)
        val nextRefresh = if (targetToday.isAfter(now)) targetToday else targetToday.plusDays(1)
        return nextRefresh.toInstant().toEpochMilli()
    }
}
