package dev.ryan.opencode.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import dev.ryan.opencode.core.TmuxSession

/**
 * Picks which tmux session the terminal should be attached to.
 *
 * The base shell remains the launcher: nothing here is required to start a new
 * session from the phone, this only attaches to the ones already running. That
 * matters after a reboot, when every session is gone and the plain shell is the
 * only way to bring one back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxSheet(
    sessions: List<TmuxSession>,
    busy: Boolean,
    attached: String?,
    onAttach: (String) -> Unit,
    onKill: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var newName by remember { mutableStateOf("") }
    val legal = remember { Regex("[^A-Za-z0-9_-]") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("tmux sessions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (busy) {
                    Text("working…", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Attach hands the terminal over to that session. Detach with Ctrl-b d, " +
                    "or use Back to shell.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9CA3AF),
            )

            Spacer(Modifier.height(12.dp))

            if (sessions.isEmpty() && !busy) {
                Text(
                    "No sessions. Start one from the shell with: tmux new -A -s work",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9CA3AF),
                )
            }

            LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
                items(sessions, key = { it.name }) { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = if (attached == s.name) Color(0xFF4ADE80) else Color.Unspecified,
                            )
                            Text(
                                buildString {
                                    append("${s.windows} window")
                                    if (s.windows != 1) append("s")
                                    append(if (s.inUse) " · in use" else " · detached")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF6B7280),
                            )
                        }
                        TextButton(onClick = { onAttach(s.name) }, enabled = !busy) {
                            Text(if (attached == s.name) "Re-attach" else "Attach")
                        }
                        TextButton(onClick = { onKill(s.name) }, enabled = !busy) {
                            Text("Kill", color = Color(0xFFF87171))
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1F2937))
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF1F2937))
            Spacer(Modifier.height(12.dp))

            Text("New session", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = legal.replace(it.take(64), "") },
                    singleLine = true,
                    placeholder = { Text("work", fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onCreate(newName.trim()); newName = "" },
                    enabled = !busy && newName.isNotBlank(),
                ) { Text("Start") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Letters, digits, - and _ only. A dash in the name is what makes it " +
                    "attachable after the next reboot — an unnamed session will not be.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6B7280),
            )
        }
    }
}

/** Small pill shown in the terminal header while a session is attached. */
@Composable
fun TmuxBadge(name: String, onDetach: () -> Unit) {
    Row(
        Modifier
            .background(Color(0xFF1F2937), RoundedCornerShape(6.dp))
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "tmux: $name",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = Color(0xFF4ADE80),
        )
        TextButton(onClick = onDetach) { Text("detach", style = MaterialTheme.typography.labelSmall) }
    }
}