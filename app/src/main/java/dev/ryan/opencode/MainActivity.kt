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
                OpencodeRoot(onPermissionsResolved = { granted = true })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Hold the connection open in the background so the app survives being swiped away.
        ConnectionService.start(this)
    }
}
