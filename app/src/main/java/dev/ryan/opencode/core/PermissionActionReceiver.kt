package dev.ryan.opencode.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ryan.opencode.core.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the Approve / Deny notification actions.
 *
 * Runs in its own process-lifetime-agnostic scope: the user may tap this while the
 * app has been killed, so it must not depend on any live UI or ViewModel. It
 * re-reads the saved credentials, replays them, and cancels the notification.
 */
class PermissionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        if (sessionId.isBlank() || requestId.isBlank()) return

        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(appContext)
                    .settings.let { flow ->
                        var value: dev.ryan.opencode.core.store.AppSettings? = null
                        flow.collect { value = it; return@collect }
                        value
                    } ?: return@launch

                if (!settings.configured) return@launch

                val connection = ConnectionManager.get(appContext)
                connection.restoreFrom(settings)

                val response = if (allow) "once" else "reject"
                connection.api.replyPermission(
                    sessionId = sessionId,
                    requestId = requestId,
                    response = response,
                    remember = false,
                    directory = settings.directory,
                )
                NotificationCentre(appContext, { _, _, _ -> })
                    .clearPermission(NotificationCentre.NOTIFICATION_ID_PERMISSION)
                Log.i(TAG, "permission $requestId -> ${if (allow) "allowed" else "denied"}")
            } catch (e: Exception) {
                Log.w(TAG, "permission reply failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "dev.ryan.opencode.PERMISSION_REPLY"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_ALLOW = "allow"
        private const val TAG = "PermissionAction"
    }
}
