package com.meetdheeran.notch.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.meetdheeran.notch.MainActivity

/**
 * Android insists a foreground service shows a notification. Notch's is silent, minimum importance and hidden from
 * the lock screen, with a "Hide" action that opens the channel switch: turning the channel off hides it for good while
 * the island keeps running.
 */
object BackgroundNotice {
    const val CHANNEL = "background"
    const val ID = 1200

    fun start(service: Service) = service.startForeground(ID, build(service))

    fun stop(service: Service) = service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

    fun openChannelSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    private fun build(ctx: Context): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Island running", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                description = "Turn this off to hide the notification; the island keeps working."
            },
        )
        val app = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val hide = PendingIntent.getActivity(
            ctx, 2,
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Notch is running")
            .setContentText("The island is on. Tap Hide to never see this again.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(app)
            .addAction(Notification.Action.Builder(null, "Hide", hide).build())
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }
}
