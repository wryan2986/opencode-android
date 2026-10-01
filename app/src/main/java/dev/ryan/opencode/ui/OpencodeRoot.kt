package dev.ryan.opencode.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ryan.opencode.ui.chat.ChatScreen
import dev.ryan.opencode.ui.settings.SettingsScreen
import dev.ryan.opencode.ui.terminal.TerminalScreen
import dev.ryan.opencode.ui.voice.VoiceScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp

val LocalAppViewModel = staticCompositionLocalOf<AppViewModel> {
    error("AppViewModel not provided")
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Chat("Chat", Icons.Filled.Chat),
    Voice("Voice", Icons.Filled.Mic),
    Terminal("Shell", Icons.Filled.Terminal),
    Settings("Setup", Icons.Filled.Tune),
}

@Composable
fun OpencodeRoot(onPermissionsResolved: () -> Unit = {}) {
    val vm: AppViewModel = viewModel(factory = AppViewModel.Factory)
    val settings by vm.settings.collectAsState()

    CompositionLocalProvider(LocalAppViewModel provides vm) {
        onPermissionsResolved()

        val sshProfiles by vm.sshProfiles.collectAsState()

        // The app is NOT gated on pairing with opencode. SSH is an independent
        // transport that needs no pairing code at all, so requiring one to get
        // anywhere made the SSH route unreachable on a fresh install — you had to
        // pair with opencode just to be allowed to use SSH. Only chat and voice
        // actually depend on the opencode API.
        val hasAnyTransport = settings.configured || sshProfiles.isNotEmpty()

        // Discovery needs a stored token to be meaningful; without one there is
        // nothing to prove a host is ours, so it is skipped rather than run blind.
        LaunchedEffect(Unit) { if (settings.configured) vm.autoConnect() }

        var tab by rememberSaveable { mutableStateOf(Tab.Chat) }
        var showFirstRun by remember { mutableStateOf(!hasAnyTransport) }
        if (showFirstRun && !hasAnyTransport) {
            FirstRunDialog(
                onPickSsh = { showFirstRun = false; tab = Tab.Terminal },
                onPickOpencode = { showFirstRun = false; tab = Tab.Settings },
                onDismiss = { showFirstRun = false },
            )
        }

        val chooserHosts by vm.chooserHosts.collectAsState()
        if (chooserHosts.size > 1) {
            AlertDialog(
                onDismissRequest = { /* stay put rather than guess */ },
                title = { Text("Which server?") },
                text = {
                    Column {
                        Text(
                            "More than one machine accepted this phone's saved key.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        chooserHosts.forEach { host ->
                            TextButton(onClick = { vm.connectToHost(host) }) {
                                Text(host, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.rediscover() }) { Text("Search again") }
                },
            )
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = {
                                tab = entry
                                if (entry == Tab.Terminal) vm.ensureTerminal()
                            },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    // These two talk to the opencode API, so without a session
                    // token there is nothing to show. The shell does not — it can
                    // run over SSH on its own.
                    Tab.Chat -> if (settings.configured) ChatScreen() else NeedsOpencode(Tab.Chat)
                    Tab.Voice -> if (settings.configured) VoiceScreen() else NeedsOpencode(Tab.Voice)
                    Tab.Terminal -> TerminalScreen()
                    Tab.Settings -> SettingsScreen()
                }
            }
        }
    }
}

/**
 * Shown on the tabs that genuinely need the opencode API, so the shell stays
 * usable on its own rather than the whole app hiding behind a setup screen.
 */
@Composable
private fun NeedsOpencode(tab: Tab) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${tab.label} needs the opencode server",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pair once in Setup, or use the Shell tab over SSH — that works " +
                "without opencode running at all.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** First run: pick a transport instead of dropping straight into a code prompt. */
@Composable
private fun FirstRunDialog(onPickSsh: () -> Unit, onPickOpencode: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect how?") },
        text = {
            Column {
                Text(
                    "You have not connected this phone yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPickSsh, modifier = Modifier.fillMaxWidth()) {
                    Text("Shell over SSH")
                }
                Text(
                    "No code needed. Copy the shown key into authorized_keys. " +
                        "Works even when opencode is down.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onPickOpencode, modifier = Modifier.fillMaxWidth()) {
                    Text("Pair with opencode")
                }
                Text(
                    "Needed for chat and voice. You pair once; after that the " +
                        "server is found automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}
