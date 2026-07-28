package com.economic.dashboard.workers

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.economic.dashboard.utils.NotificationHelper
import com.economic.dashboard.utils.SettingsManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Daily morning check (runs ~7:30 AM local) that reminds the user about
 * major scheduled data releases. Currently covers the BLS Employment
 * Situation ("jobs report"), which lands on the first Friday of the month
 * at 8:30 AM ET. Only fires when the user enables "Data release reminders".
 */
class ReleaseReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        try {
            if (!SettingsManager.getBool(ctx, SettingsManager.KEY_NOTIFY_RELEASES, false))
                return Result.success()

            val now = Calendar.getInstance()
            val firstFriday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY &&
                now.get(Calendar.DAY_OF_MONTH) <= 7
            if (!firstFriday) return Result.success()

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val already = SettingsManager.getString(ctx, SettingsManager.KEY_LAST_RELEASE_NOTIFIED, "")
            if (today == already) return Result.success()
            SettingsManager.setString(ctx, SettingsManager.KEY_LAST_RELEASE_NOTIFIED, today)

            NotificationHelper.notify(
                ctx, NOTIFICATION_ID_RELEASE,
                "Jobs report today",
                "The BLS Employment Situation report is due at 8:30 AM ET. " +
                    "Unemployment and payroll numbers will update on the dashboard."
            )
            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Release reminder failed: " + e.message)
            return Result.success()
        }
    }

    companion object {
        private const val TAG = "ReleaseReminderWorker"
        private const val WORK_ID = "release_reminder_daily"
        private const val NOTIFICATION_ID_RELEASE = 2002

        /** Schedules the daily check at ~7:30 AM local time. KEEP policy — idempotent. */
        @JvmStatic
        fun scheduleDaily(context: Context) {
            try {
                val next = Calendar.getInstance()
                next.set(Calendar.HOUR_OF_DAY, 7)
                next.set(Calendar.MINUTE, 30)
                next.set(Calendar.SECOND, 0)
                if (next.timeInMillis <= System.currentTimeMillis())
                    next.add(Calendar.DAY_OF_YEAR, 1)
                val delayMinutes = (next.timeInMillis - System.currentTimeMillis()) / 60000L

                val request = PeriodicWorkRequest.Builder(
                    ReleaseReminderWorker::class.java, 1, TimeUnit.DAYS
                )
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_ID, ExistingPeriodicWorkPolicy.KEEP, request
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule release reminders", e)
            }
        }
    }
}
