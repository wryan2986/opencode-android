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
