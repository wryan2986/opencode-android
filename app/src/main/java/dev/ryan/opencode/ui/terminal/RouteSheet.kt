package dev.ryan.opencode.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ryan.opencode.core.ssh.SshProfile
import dev.ryan.opencode.core.ssh.SshProfileStore

/**
 * Chooses which transport the terminal uses, and manages SSH destinations.
 *
 * The distinction is the reason this exists. "opencode" gets its PTY from the
 * opencode server, so it is unavailable exactly when opencode is broken. "ssh" is
 * an independent session and is the way out of that — you can attach to a tmux
 * session, start `opencode`, start `codex`, or drop to a shell, on a host where
 * opencode may not be running at all.
 */
@Composable
fun RouteSheet(
    profiles: List<SshProfile>,
    busy: Boolean,
    error: String?,
    hostLabel: String,
    currentIsSsh: Boolean,
    keyFingerprint: String,
    publicKey: String,
    enrollAvailable: Boolean,
    enrolling: Boolean,
    enrollMessage: String?,
    onEnroll: () -> Unit,
    onUseOpencode: () -> Unit,
    onUseSsh: (SshProfile) -> Unit,
    onCreateProfile: (String, String, String, Int) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<SshProfile?>(null) }
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        ProfileDialog(
            existing = null,
            busy = busy,
            onSave = { host, user, cmd, port ->
                onCreateProfile(host, user, cmd, port); creating = false
            },
            onDismiss = { creating = false },
        )
        return
    }
    editing?.let { profile ->
        ProfileDialog(
            existing = profile,
            busy = busy,
            onSave = { _, user, cmd, port ->
                onCreateProfile(profile.host, user, cmd, port); editing = null
            },
            onDismiss = { editing = null },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal source") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Text(
                    "opencode routes through the opencode server. SSH is independent " +
                        "of it — use it when opencode is down, or to reach another host.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "opencode",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (!currentIsSsh) Color(0xFF4ADE80) else Color.Unspecified,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!currentIsSsh) {
                        Text("in use", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4ADE80))
                    } else {
                        TextButton(onClick = onUseOpencode) { Text("Use") }
                    }
                }
                Text(
                    hostLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFF6B7280),
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SSH hosts", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { creating = true }) { Text("Add") }
                }

                if (!keyFingerprint.isBlank()) {
                    Text(
                        "This device's key: $keyFingerprint",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFF6B7280),
                    )
                }

                if (profiles.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No hosts yet — add one below first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(profile.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${profile.user}@${profile.address}" +
                                            if (profile.launchCommand.isNotBlank()) "  ·  ${profile.launchCommand}" else "",
                                        style = MaterialTheme.typography.labelSmall
                                            .copy(fontFamily = FontFamily.Monospace),
                                        color = Color(0xFF6B7280),
                                    )
                                }
                                if (currentIsSsh) {
                                    TextButton(onClick = { onUseSsh(profile) }, enabled = !busy) { Text("Use") }
                                }
                                TextButton(onClick = { editing = profile }) { Text("Edit") }
                                TextButton(onClick = { onDeleteProfile(profile.id) }) {
                                    Text("Del", color = Color(0xFFF87171))
                                }
                            }
                            HorizontalDivider(color = Color(0xFF1F2937))
                        }
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFF87171))
                }

                // The key has to reach the server before anything can connect, and
                // it cannot be sent there by the app — so it is shown for copying.
                // This is the one manual step in the whole flow.
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF1F2937))
                Spacer(Modifier.height(10.dp))
                Text("This device's public key", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Append this to ~/.ssh/authorized_keys on the server:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // Prefixed with `restrict` on purpose: the key may run commands,
                    // but not write to authorized_keys or forward ports, so a lost
                    // phone cannot escalate. Removing it would still allow that.
                    "restrict " + publicKey,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFF4ADE80),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (keyFingerprint.isBlank()) "Generating…" else keyFingerprint,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFF6B7280),
                )

                // The zero-typing path: the paired opencode session can write this
                // key itself, because it can already run any command on the host.
                if (enrollAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onEnroll, enabled = !enrolling) {
                        Text(if (enrolling) "Adding…" else "Add it for me")
                    }
                    Text(
                        "Uses the opencode connection you already have. No copying.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                enrollMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("Added")) Color(0xFF4ADE80) else Color(0xFFF87171),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ProfileDialog(
    existing: SshProfile?,
    busy: Boolean,
    onSave: (host: String, user: String, command: String, port: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf(existing?.host ?: "100.102.124.47") }
    var user by remember { mutableStateOf(existing?.user ?: "ryan") }
    var command by remember { mutableStateOf(existing?.launchCommand ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add SSH host" else "Edit ${existing.label}") },
        text = {
            Column {
                if (existing == null) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host or address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("User") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Run on connect (optional)") },
                    placeholder = { Text("bash -l  ·  opencode  ·  codex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Leave blank for a login shell. Use a tmux session name to attach " +
                        "to something already running.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(host.trim(), user.trim(), command.trim(), port.toIntOrNull() ?: 22) },
                enabled = !busy && host.isNotBlank() && user.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}