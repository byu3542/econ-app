package com.economic.dashboard.workers

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Shared "next HH:mm local, 1-day periodic work, KEEP" routine that
 * DailyBriefWorker and ReleaseReminderWorker used to each carry verbatim.
 * Callers keep their own try/catch and log severity around this call.
 * (YieldRefreshScheduler stays separate: zoned target time, network
 * constraint, UPDATE policy.)
 */
internal fun scheduleDailyAt(
    context: Context,
    workerClass: Class<out ListenableWorker>,
    workId: String,
    hour: Int,
    minute: Int
) {
    val next = Calendar.getInstance()
    next.set(Calendar.HOUR_OF_DAY, hour)
    next.set(Calendar.MINUTE, minute)
    next.set(Calendar.SECOND, 0)
    if (next.timeInMillis <= System.currentTimeMillis())
        next.add(Calendar.DAY_OF_YEAR, 1)
    val delayMinutes = (next.timeInMillis - System.currentTimeMillis()) / 60000L

    val request = PeriodicWorkRequest.Builder(workerClass, 1, TimeUnit.DAYS)
        .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        workId, ExistingPeriodicWorkPolicy.KEEP, request
    )
}
