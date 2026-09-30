package dev.ryan.opencode.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.ryan.opencode.core.ConnectionManager
import dev.ryan.opencode.core.model.ConnectionState
import dev.ryan.opencode.core.net.describe
import dev.ryan.opencode.core.net.StreamSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the opencode connection alive while the app is backgrounded.
 *
 * Android will happily kill a backgrounded process holding a WebSocket, which
 * would undo the whole point of this app. A foreground service with a quiet
 * "connected" notification is the only reliable way to hold the event stream and
 * PTY socket open, so the user is not asked to babysit the app.
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var connection: ConnectionManager

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        connection = ConnectionManager.get(this)
        connection.start()
        startForegroundWith("Connecting…", "Reaching your server")

        scope.launch {
            connection.state.collectLatest { state ->
                when (state) {
                    ConnectionState.Connected ->
                        startForegroundWith("Connected", "Listening for opencode activity")
                    ConnectionState.Connecting ->
                        startForegroundWith("Connecting…", "Reaching your server")
                    ConnectionState.Reconnecting ->
                        startForegroundWith("Reconnecting…", connection.reason.value.describe())
                    ConnectionState.Failed ->
                        startForegroundWith("Offline", connection.reason.value.describe())
                    ConnectionState.Idle ->
                        startForegroundWith("Idle", "Not connected")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundWith(title: String, detail: String) {
        val notification = Notifications.connectionNotification(this, title, detail)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    Notifications.ID_CONNECTION,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(Notifications.ID_CONNECTION, notification)
            }
        }
    }

    companion object {
        private const val ACTION_STOP = "dev.ryan.opencode.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ConnectionService::class.java))
            }
        }
    }
}
