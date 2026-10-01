package dev.ryan.opencode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ryan.opencode.core.model.ConnectionState
import dev.ryan.opencode.core.net.DirectTransport
import dev.ryan.opencode.ui.LocalAppViewModel
import kotlinx.coroutines.launch

/**
 * Displays the pairing code in uppercase for legibility while leaving the stored
 * value untouched — the server's code is mixed-case and case-sensitive.
 */
private val UppercaseVisualTransformation = VisualTransformation { text ->
    TransformedText(AnnotatedString(text.text.uppercase(), text.spanStyles), androidx.compose.ui.text.input.OffsetMapping.Identity)
}

private val PRESETS = listOf(
    "Tailscale" to "100.102.124.47:4096",
    "Cloudflare tunnel" to "https://example.trycloudflare.com",
    "LAN" to "192.168.1.10:4096",
)

/**
 * Onboarding and configuration.
 *
 * The intended flow is **pairing**, not password entry: run `opencode pair` on the
 * server (or let the app mint a code) and the phone trades that one-time code for a
 * session token. The server password never has to be stored on the device.
 */
@Composable
fun SettingsScreen(onComplete: () -> Unit = {}) {
    val vm = LocalAppViewModel.current
    val settings by vm.settings.collectAsState()
    val connection by vm.connectionState.collectAsState()
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(settings.host) }
    var directory by remember { mutableStateOf(settings.directory) }
    var basicUser by remember { mutableStateOf(settings.basicUser) }
    var basicPassword by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var linkHost by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(settings.host) { if (settings.host.isNotBlank()) host = settings.host }
    LaunchedEffect(settings.directory) { directory = settings.directory }

    fun connect() {
        busy = true; error = null; status = "Connecting…"
        scope.launch {
            vm.saveSettings { store ->
                store.setHost(host)
                store.setDirectory(directory)
                store.setBasicUser(basicUser)
                if (basicPassword.isNotBlank()) store.setBasicPassword(basicPassword)
            }
            busy = false
            status = null
        }
    }

    fun pairWithCode(code: String) {
        if (code.isBlank()) return
        busy = true; error = null
        scope.launch {
            runCatching {
                vm.connection.applyForPairing(host, basicUser, basicPassword, code.trim())
            }.onSuccess {
                busy = false
                onComplete()
            }.onFailure {
                busy = false
                error = it.message ?: "Pairing failed"
            }
        }
    }

    if (showScanner) {
        QrScannerDialog(
            onCode = { scanned ->
                showScanner = false
                val parsed = parsePairingLink(scanned)
                if (parsed != null) {
                    linkHost = parsed.first
                    pairingCode = parsed.second
                    vm.pairUsingCode(parsed.first, parsed.second)
                } else {
                    // Not a pairing link — accept a bare code too, since that is
                    // what the installer also prints as text.
                    pairingCode = scanned.trim()
                    if (host.isNotBlank()) pairWithCode(scanned.trim())
                }
            },
            onDismiss = { showScanner = false },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Connect to your server", style = MaterialTheme.typography.titleMedium)
        Text(
            "The shell and your opencode sessions run on the server, so the app stays useful " +
                "even when the phone's connection drops.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        Text("Server address", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("e.g. 100.102.124.47:4096", fontFamily = FontFamily.Monospace) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PRESETS.forEach { (label, value) ->
                OutlinedButton(onClick = { host = value }) { Text(label, style = MaterialTheme.typography.labelSmall) }
            }
        }

        Text("Working directory", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = directory,
            onValueChange = { directory = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("/home/ryan", fontFamily = FontFamily.Monospace) },
        )

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        // Pairing is a one-time setup step. Once a token is stored, discovery
        // re-finds the server on its own and this whole section is a fallback —
        // which is why it sits below "find my server" rather than being the
        // first thing on screen.
        Text("Find my server", style = MaterialTheme.typography.titleSmall)
        Text(
            "Uses the key already saved on this phone to look for the server. No code needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.autoConnect() },
                enabled = !vm.autoConnecting.collectAsState().value && settings.configured,
            ) {
                Text(if (vm.autoConnecting.collectAsState().value) "Searching…" else "Search")
            }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    !settings.configured -> "Pair once below to enable this."
                    else -> "Last known: ${settings.host}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        Text("Sign in", style = MaterialTheme.typography.titleSmall)
        Text(
            "For chat and voice only — the shell works over SSH without any of this. " +
                "One-time setup: mint a code against the serve instance and type it " +
                "here; after that, discovery reconnects on its own. " +
                "Your password is never stored on the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Scanning is the fast path; typing stays because a code is only valid for
        // five minutes and one use, so the QR may well be stale by the time the
        // camera is up. Both feed the same field.
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showScanner = true }) { Text("Scan QR") }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = pairingCode,
                // NB: the code is case-sensitive and the server generates mixed case,
                // so we only *display* it uppercase — never transform the stored value.
                // Only strip whitespace: server codes legitimately contain - and _
                onValueChange = { pairingCode = it.filterNot { c -> c.isWhitespace() } },
                visualTransformation = UppercaseVisualTransformation,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Pairing code") },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { pairWithCode(pairingCode) },
                enabled = !busy && pairingCode.isNotBlank(),
            ) { Text("Pair") }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Option B — sign in with the server password",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = showAdvanced, onCheckedChange = { showAdvanced = it })
            Spacer(Modifier.width(8.dp))
            Text("Use password instead", style = MaterialTheme.typography.labelMedium)
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = basicUser,
                onValueChange = { basicUser = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = basicPassword,
                onValueChange = { basicPassword = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(6.dp))
        Button(
            onClick = { connect() },
            enabled = !busy && host.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (settings.configured) "Reconnect" else "Connect")
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (settings.configured) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Connected", style = MaterialTheme.typography.titleSmall)
            Text(
                when (connection) {
                    ConnectionState.Connected -> "Live"
                    ConnectionState.Connecting -> "Connecting…"
                    ConnectionState.Reconnecting -> "Reconnecting…"
                    ConnectionState.Failed -> "Failed"
                    ConnectionState.Idle -> "Idle"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (connection == ConnectionState.Connected) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "token stored securely · directory ${settings.directory}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Text("Voice output", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.ttsEnabled,
                    onCheckedChange = { v -> vm.saveSettings { it.setTtsEnabled(v) } },
                )
                Spacer(Modifier.width(8.dp))
                Text("Read replies aloud when a turn finishes", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.bargeInEnabled,
                    onCheckedChange = { v -> vm.saveSettings { it.setBargeIn(v) } },
                )
                Spacer(Modifier.width(8.dp))
                Text("Interrupt when I talk over it", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.notificationsEnabled,
                    onCheckedChange = { v -> vm.saveSettings { it.setNotificationsEnabled(v) } },
                )
                Spacer(Modifier.width(8.dp))
                Text("Notify me when a turn needs approval or finishes", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { vm.saveSettings { it.clearCredentials() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign out") }
        }
    }
}

/**
 * Split `http://host:port/auth/connect/CODE` into `(host:port, code)`.
 *
 * Returns null for anything that is not that shape, so the caller can fall back
 * to treating the text as a bare code. The host is never trusted — pairing still
 * fails unless that host minted the code.
 */
internal fun parsePairingLink(text: String): Pair<String, String>? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
    val scheme = trimmed.substringBefore("://")
    val withoutScheme = trimmed.substringAfter("://")
    val slash = withoutScheme.indexOf('/')
    if (slash < 0) return null
    val host = withoutScheme.substring(0, slash)
    val path = withoutScheme.substring(slash)
    if (!path.startsWith("/auth/connect/")) return null
    val code = path.removePrefix("/auth/connect/").trim()
    if (code.isEmpty() || host.isEmpty()) return null
    // Carry the scheme's default port when the link omits one. The installer
    // always prints an explicit port, so this only matters for a hand-made link —
    // and getting it wrong here points pairing at the wrong port and fails with a
    // bare auth error.
    val withPort = when {
        host.contains(':') -> host
        scheme == "https" -> "$host:443"
        else -> "$host:80"
    }
    return withPort to code
}
