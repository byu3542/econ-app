package com.economic.dashboard.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.economic.dashboard.utils.NotificationHelper;
import com.economic.dashboard.utils.SettingsManager;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Daily morning check (runs ~7:30 AM local) that reminds the user about
 * major scheduled data releases. Currently covers the BLS Employment
 * Situation ("jobs report"), which lands on the first Friday of the month
 * at 8:30 AM ET. Only fires when the user enables "Data release reminders".
 */
public class ReleaseReminderWorker extends Worker {

    private static final String TAG = "ReleaseReminderWorker";
    private static final String WORK_ID = "release_reminder_daily";
    private static final int NOTIFICATION_ID_RELEASE = 2002;

    public ReleaseReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            if (!SettingsManager.getBool(ctx, SettingsManager.KEY_NOTIFY_RELEASES, false))
                return Result.success();

            Calendar now = Calendar.getInstance();
            boolean firstFriday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
                    && now.get(Calendar.DAY_OF_MONTH) <= 7;
            if (!firstFriday) return Result.success();

            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String already = SettingsManager.getString(
                    ctx, SettingsManager.KEY_LAST_RELEASE_NOTIFIED, "");
            if (today.equals(already)) return Result.success();
            SettingsManager.setString(ctx, SettingsManager.KEY_LAST_RELEASE_NOTIFIED, today);

            NotificationHelper.notify(ctx, NOTIFICATION_ID_RELEASE,
                    "Jobs report today",
                    "The BLS Employment Situation report is due at 8:30 AM ET. "
                    + "Unemployment and payroll numbers will update on the dashboard.");
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Release reminder failed: " + e.getMessage());
            return Result.success();
        }
    }

    /** Schedules the daily check at ~7:30 AM local time. KEEP policy — idempotent. */
    public static void scheduleDaily(Context context) {
        try {
            Calendar next = Calendar.getInstance();
            next.set(Calendar.HOUR_OF_DAY, 7);
            next.set(Calendar.MINUTE, 30);
            next.set(Calendar.SECOND, 0);
            if (next.getTimeInMillis() <= System.currentTimeMillis())
                next.add(Calendar.DAY_OF_YEAR, 1);
            long delayMinutes = (next.getTimeInMillis() - System.currentTimeMillis()) / 60000L;

            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    ReleaseReminderWorker.class, 1, TimeUnit.DAYS)
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .build();

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_ID, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule release reminders", e);
        }
    }
}
