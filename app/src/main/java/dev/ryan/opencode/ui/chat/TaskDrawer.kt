package dev.ryan.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ryan.opencode.core.model.AgentInfo
import dev.ryan.opencode.core.model.InboxItem
import dev.ryan.opencode.core.model.Session

/**
 * Queued work, spawned sub-agents, and the agent selector.
 *
 * The three sections are one drawer because they are the same idea seen from
 * different ends: what you asked for, what it decided to do about it, and which
 * agent will handle the next thing.
 *
 * ## The steer/queue distinction is shown, not hidden
 *
 * The server takes `delivery: "steer" | "queue"` on a prompt. `steer` reaches the
 * turn that is already running; `queue` waits for it to end. Those behave very
 * differently and a user who cannot tell which they picked will conclude the app
 * ignored them, so each row is labelled.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TaskDrawer(
    queue: List<InboxItem>,
    subAgents: List<Session>,
    agents: List<AgentInfo>,
    activeAgent: String?,
    busy: Boolean,
    onCancel: (String) -> Unit,
    onOpenSubAgent: (Session) -> Unit,
    onUseAgent: (String) -> Unit,
    onBackground: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tasks") },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                // ---- queued work ----
                Text("Queued", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Steered items go into the reply that is running now. " +
                        "Queued items wait for it to finish.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (queue.isEmpty()) {
                    Text(
                        "Nothing waiting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 190.dp)) {
                        items(queue, key = { it.id }) { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .width(4.dp)
                                        .height(34.dp)
                                        .background(
                                            if (item.isSteer) Color(0xFF4ADE80) else Color(0xFF6B7280),
                                            RoundedCornerShape(2.dp),
                                        ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                    )
                                    Text(
                                        if (item.isSteer) "steering now" else "waiting its turn",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (item.isSteer) Color(0xFF4ADE80) else Color(0xFF6B7280),
                                    )
                                }
                                TextButton(onClick = { onCancel(item.id) }) {
                                    Text("Cancel", color = Color(0xFFF87171))
                                }
                            }
                            HorizontalDivider(color = Color(0xFF1F2937))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))

                // ---- spawned sub-agent threads ----
                Text("Working on", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                if (subAgents.isEmpty()) {
                    Text(
                        "No sub-tasks yet. The agent spins these up when a request " +
                            "is worth splitting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
                        items(subAgents, key = { it.id }) { s ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenSubAgent(s) }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    s.displayTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                )
                                Text(
                                    "sub-task · tap to open",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF6B7280),
                                )
                            }
                            HorizontalDivider(color = Color(0xFF1F2937))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))

                // ---- agent ----
                Text("Agent for the next turn", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    agents.forEach { a ->
                        FilterChip(
                            selected = a.name == activeAgent,
                            onClick = { onUseAgent(a.name) },
                            enabled = !busy,
                            label = { Text(a.name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                AssistChip(
                    onClick = onBackground,
                    enabled = !busy,
                    label = {
                        Text(
                            "Move running tools to background",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}