package dev.ryan.opencode

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ryan.opencode.service.ConnectionService
import dev.ryan.opencode.service.Notifications
import dev.ryan.opencode.ui.OpencodeRoot
import dev.ryan.opencode.ui.theme.OpencodeTheme

class MainActivity : ComponentActivity() {

    /**
     * A pairing link handed to us by the camera, a browser, or `adb shell am start`.
     *
     * Held as state rather than consumed in `onCreate`, because a cold start from
     * a link arrives in the launch intent while a warm start arrives in `onNewIntent`
     * — both routes have to work or the QR silently does nothing the second time.
     */
    private val pendingPairing = mutableStateOf<PairingLink?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairingFrom(intent)?.let { pendingPairing.value = it }
    }


    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results are read lazily where used */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannels(this)

        setContent {
            OpencodeTheme {
                var granted by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val wanted = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.filter {
                        checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (wanted.isEmpty()) granted = true
                    else permissionLauncher.launch(wanted.toTypedArray())
                }
                OpencodeRoot(
                    onPermissionsResolved = { granted = true },
                    pairingLink = pendingPairing.value,
                    onPairingLinkConsumed = { pendingPairing.value = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        pairingFrom(intent)?.let { pendingPairing.value = it }
    }

    /** `(host, code)` if this intent is a pairing link, else null. */
    private fun pairingFrom(intent: android.content.Intent?): PairingLink? {
        val data = intent?.data ?: return null
        if (data.scheme != "http") return null
        if (!data.path.orEmpty().startsWith("/auth/connect/")) return null
        val code = data.lastPathSegment?.trim().orEmpty()
        if (code.isEmpty()) return null
        val port = if (data.port > 0) data.port else 80
        return PairingLink(host = "${data.host}:$port", code = code)
    }

    // Hold the connection open in the background so the app survives being swiped away.
    private fun keepConnectionAlive() = ConnectionService.start(this)
}

/** Host and code from a pairing link. */
data class PairingLink(val host: String, val code: String)
