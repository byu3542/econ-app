package com.economic.dashboard.workers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
     * Safe to call multiple times; uses ExistingPeriodicWorkPolicy.UPDATE, which
     * is idempotent for an unchanged spec and rolls out a changed one.
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
            // - Requires a network: the worker's only job is an HTTP fetch, and
            //   WorkManager's defaults impose NO network constraint, so without
            //   this it burned runs (and retry backoff) while offline.
            val refreshWorkRequest = PeriodicWorkRequest.Builder(
                YieldRefreshWorker::class.java,
                1,
                TimeUnit.DAYS
            )
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            // UPDATE, not KEEP. KEEP ignores the request whenever work under
            // this id already exists, which meant an already-installed app kept
            // running whatever spec it first enqueued — a worker fixed in a
            // later release, or one enqueued without the network constraint
            // above, could never be corrected. UPDATE swaps in the new spec
            // while preserving the existing schedule, and is still idempotent,
            // so calling this on every Application.onCreate stays safe.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                YIELD_REFRESH_WORK_ID,
                ExistingPeriodicWorkPolicy.UPDATE,
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

        val nextRefreshTime = nextRefreshZonedDateTime(now)

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
        return nextRefreshZonedDateTime(now).toInstant().toEpochMilli()
    }

    /**
     * Next 2:30 PM in [now]'s zone: today's 2:30 PM if still ahead, else
     * tomorrow's. The one copy of the target-time rule — both public
     * entry points above derive from this.
     */
    private fun nextRefreshZonedDateTime(now: ZonedDateTime): ZonedDateTime {
        val targetToday = now.toLocalDate()
            .atTime(LocalTime.of(14, 30))
            .atZone(now.zone)
        return if (targetToday.isAfter(now)) targetToday else targetToday.plusDays(1)
    }
}
