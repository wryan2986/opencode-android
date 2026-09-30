package dev.ryan.opencode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ryan.opencode.MainActivity
import dev.ryan.opencode.R

/**
 * Notifications.
 *
 * The point of these is that the phone can be put down: you get told when the
 * agent needs a decision or has finished, and you can act on it without opening
 * the app and waiting for it to reconnect.
 */
object Notifications {

    const val CHANNEL_CONNECTION = "connection"
    const val CHANNEL_ACTIVITY = "activity"

    const val ID_CONNECTION = 1
    const val ID_PERMISSION = 2
    const val ID_COMPLETE = 3

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONNECTION,
                "Connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the opencode connection alive in the background" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACTIVITY,
                "Agent activity",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Approvals and finished turns" }
        )
    }

    fun openAppIntent(context: Context, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun connectionNotification(
        context: Context,
        title: String,
        detail: String,
    ): Notification = NotificationCompat.Builder(context, CHANNEL_CONNECTION)
        .setSmallIcon(R.drawable.ic_stat_opencode)
        .setContentTitle(title)
        .setContentText(detail)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openAppIntent(context))
        .build()

    /** "opencode needs permission" — with inline Approve / Deny actions. */
    fun permissionNotification(
        context: Context,
        title: String,
        detail: String,
        approveAction: PendingIntent?,
        denyAction: PendingIntent?,
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openAppIntent(context, 1))
            .apply {
                approveAction?.let { addAction(0, "Allow", it) }
                denyAction?.let { addAction(0, "Deny", it) }
            }
            .build()

    fun turnCompleteNotification(
        context: Context,
        title: String,
        detail: String,
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(context, 2))
            .build()

    fun show(context: Context, id: Int, notification: Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
