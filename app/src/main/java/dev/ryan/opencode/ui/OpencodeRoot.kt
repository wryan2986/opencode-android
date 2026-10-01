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
        if (!settings.configured) {
            SettingsScreen(onComplete = { /* connection manager picks settings up reactively */ })
            return@CompositionLocalProvider
        }

        // We already hold a token, so go and find the server rather than asking
        // the user where it is. One match connects silently; more than one pops
        // the chooser; none falls through to Settings with a failed state.
        LaunchedEffect(Unit) { vm.autoConnect() }

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

        var tab by rememberSaveable { mutableStateOf(Tab.Chat) }

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
                    Tab.Chat -> ChatScreen()
                    Tab.Voice -> VoiceScreen()
                    Tab.Terminal -> TerminalScreen()
                    Tab.Settings -> SettingsScreen()
                }
            }
        }
    }
}
