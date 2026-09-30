package dev.ryan.opencode.core

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
import dev.ryan.opencode.core.model.PermissionRequest

/**
 * Turns opencode events into notifications.
 *
 * This is the feature that lets the phone stay in a pocket: you get told when the
 * agent needs a decision, and you can answer from the lock screen without opening
 * the app and waiting for it to reconnect.
 *
 * Kept separate from [dev.ryan.opencode.service.Notifications], which only builds
 * notification *objects*; this class owns the policy of when to fire them.
 */
class NotificationCentre(
    private val context: Context,
    private val onPermissionReply: suspend (sessionId: String, requestId: String, allow: Boolean) -> Unit,
) {
    private val manager = NotificationManagerCompat.from(context)

    private val openApp: PendingIntent
        get() = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The Approve/Deny actions are handled by a separate receiver, which cannot
     * reach live app state — so the identifiers travel in the intent itself
     * rather than through some shared holder that could go stale.
     */
    private fun actionPendingIntent(request: PermissionRequest, requestCode: Int, allow: Boolean): PendingIntent {
        val intent = Intent(context, PermissionActionReceiver::class.java).apply {
            action = PermissionActionReceiver.ACTION_REPLY
            putExtra(PermissionActionReceiver.EXTRA_SESSION_ID, request.sessionID.orEmpty())
            putExtra(PermissionActionReceiver.EXTRA_REQUEST_ID, request.id)
            putExtra(PermissionActionReceiver.EXTRA_ALLOW, allow)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun canNotify(): Boolean = manager.areNotificationsEnabled()

    fun notifyPermissionNeeded(request: PermissionRequest, detail: String) {
        if (!canNotify()) return
        // Distinct request codes so Allow and Deny get their own PendingIntents
        // rather than one replacing the other.
        val base = request.id.hashCode()
        val allow = actionPendingIntent(request, base * 2 + 1, true)
        val deny = actionPendingIntent(request, base * 2 + 2, false)

        val notification = NotificationCompat.Builder(context, ACTIVITY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle("opencode needs approval")
            .setContentText(detail.ifBlank { request.title ?: request.type })
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, "Allow", allow)
            .addAction(0, "Deny", deny)
            .build()
        show(NOTIFICATION_ID_PERMISSION, notification)
    }

    fun notifyTurnFinished(sessionTitle: String, preview: String) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, ACTIVITY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle("Finished · $sessionTitle")
            .setContentText(preview.ifBlank { "Tap to review the reply" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        show(NOTIFICATION_ID_COMPLETE, notification)
    }

    fun notifyConnectionProblem(reason: String) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, CONNECTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle("Reconnecting to your server")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .build()
        show(NOTIFICATION_ID_CONNECTION_PROBLEM, notification)
    }

    fun clearConnectionProblem() {
        manager.cancel(NOTIFICATION_ID_CONNECTION_PROBLEM)
    }

    fun clearPermission(id: Int) = manager.cancel(id)

    private fun show(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        const val CONNECTION_CHANNEL = "connection"
        const val ACTIVITY_CHANNEL = "activity"

        const val NOTIFICATION_ID_PERMISSION = 2001
        const val NOTIFICATION_ID_COMPLETE = 2002
        const val NOTIFICATION_ID_CONNECTION_PROBLEM = 2003

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CONNECTION_CHANNEL,
                    "Connection",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Keeps the connection alive in the background" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    ACTIVITY_CHANNEL,
                    "Agent activity",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Approvals and finished turns" }
            )
        }
    }
}
