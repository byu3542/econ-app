package com.economic.dashboard.workers

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker.Result
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.economic.dashboard.api.TreasuryYieldRepository
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.TreasuryYield
import com.economic.dashboard.utils.NotificationHelper
import com.economic.dashboard.utils.SettingsManager
import java.util.Locale

/**
 * Background Worker for scheduled Treasury Yield refresh.
 *
 * Lifecycle:
 * - Scheduled by WorkManager to run daily at approximately 2:30 PM MST
 * - Calls TreasuryYieldRepository.forceRefresh() to update cache
 * - If API succeeds: Cache is updated, UI automatically reflects new data
 * - If API fails: Cached data remains available (graceful degradation)
 * - If the user enabled "Big rate moves" alerts, compares the two most
 *   recent 10Y readings in the cache and notifies on a 10+ bp move.
 */
class YieldRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d(TAG, "YieldRefreshWorker triggered at " + System.currentTimeMillis())

        return try {
            val repository = TreasuryYieldRepository(applicationContext)
            repository.forceRefresh()
            Log.d(TAG, "Yield refresh completed successfully")

            maybeNotifyBigMove(applicationContext)

            // Return success even if API call fails asynchronously
            // (cache is still available as fallback)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Yield refresh failed with exception", e)
            // Retry this worker (WorkManager will reschedule with exponential backoff)
            Result.retry()
        }
    }

    /**
     * Compares the two most recent cached 10-Year readings and posts a
     * notification when the day-over-day move is BIG_MOVE_BP or more.
     * Remembers the last-notified date so the same move never alerts twice.
     */
    private fun maybeNotifyBigMove(ctx: Context) {
        try {
            if (!SettingsManager.getBool(ctx, SettingsManager.KEY_NOTIFY_BIG_MOVES, false)) return

            val rows: List<TreasuryYield> =
                YieldDatabase.getInstance(ctx).treasuryYieldDao().getAllYieldsSync()
            if (rows.isEmpty()) return

            // Rows are ordered date DESC — find the newest two distinct dates for 10 Year
            var latest: Double? = null
            var previous: Double? = null
            var latestDate: String? = null
            for (y in rows) {
                if ("10 Year" != y.series) continue
                if (latest == null) {
                    latest = y.value
                    latestDate = y.date
                } else if (y.date != latestDate) {
                    previous = y.value
                    break
                }
            }
            if (latest == null || previous == null || latestDate == null) return

            val moveBp = (latest - previous) * 100.0
            if (Math.abs(moveBp) < BIG_MOVE_BP) return

            val alreadyNotified = SettingsManager.getString(ctx, SettingsManager.KEY_LAST_MOVE_NOTIFIED, "")
            if (latestDate == alreadyNotified) return
            SettingsManager.setString(ctx, SettingsManager.KEY_LAST_MOVE_NOTIFIED, latestDate)

            val direction = if (moveBp > 0) "up" else "down"
            NotificationHelper.notify(
                ctx, NOTIFICATION_ID_BIG_MOVE,
                "Big move in the 10Y Treasury",
                String.format(
                    Locale.US, "10Y yield %s %.0f bp to %.2f%% (%s).",
                    direction, Math.abs(moveBp), latest, latestDate
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Big-move check failed: " + e.message)
        }
    }

    companion object {
        private const val TAG = "YieldRefreshWorker"

        /** Notify when the 10Y moves at least this many basis points day-over-day. */
        private const val BIG_MOVE_BP = 10.0
        private const val NOTIFICATION_ID_BIG_MOVE = 2001
    }
}
