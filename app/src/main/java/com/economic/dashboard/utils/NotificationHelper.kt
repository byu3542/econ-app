package com.economic.dashboard.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.economic.dashboard.R
import com.economic.dashboard.ui.MainActivity

/** Creates the notification channel and posts economic alerts. */
object NotificationHelper {

    @JvmField
    val CHANNEL_ID = "econ_alerts"

    /** Idempotent — safe to call on every app start. */
    @JvmStatic
    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Economic alerts", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Big rate moves and data release reminders"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }

    /** Posts a notification if permission is granted; silently skips otherwise. */
    @JvmStatic
    fun notify(ctx: Context, id: Int, title: String?, text: String?) {
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            return  // user hasn't granted notifications
        }
        val open = Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dashboard)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
        try {
            NotificationManagerCompat.from(ctx).notify(id, builder.build())
        } catch (ignored: SecurityException) {
        }
    }
}
