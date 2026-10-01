package dev.ryan.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ryan.opencode.core.ChatItem
import dev.ryan.opencode.core.model.ConnectionState
import dev.ryan.opencode.core.net.describe
import dev.ryan.opencode.ui.LocalAppViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding

@Composable
fun ChatScreen() {
    val vm = LocalAppViewModel.current
    val chatState by vm.chat.state.collectAsState()
    val sessions by vm.chat.sessions.collectAsState()
    val connection by vm.connectionState.collectAsState()
    val reason by vm.disconnectReason.collectAsState()
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var composer by remember { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Approvals can be raised while we were backgrounded (or by the TUI), so
    // re-check on every resume.
    LaunchedEffect(Unit) { vm.onChatResumed() }

    // Keep the newest content in view while a turn streams.
    LaunchedEffect(chatState.items.size) {
        if (chatState.items.isNotEmpty()) listState.animateScrollToItem(chatState.items.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        ConnectionBanner(connection, reason)

        SessionBar(
            title = sessions.firstOrNull { it.id == chatState.sessionId }?.displayTitle
                ?: if (chatState.sessionId.isBlank()) "No session" else "Session",
            busy = chatState.busy,
            expanded = showSessions,
            sessions = sessions,
            activeId = chatState.sessionId,
            onToggle = { showSessions = !showSessions },
            onSelect = { session ->
                showSessions = false
                scope.launch {
                    vm.chat.openSession(session.id)
                    vm.saveSettings { it.setLastSessionId(session.id) }
                }
            },
            onNew = {
                showSessions = false
                scope.launch { vm.chat.createSession() }
            },
            onRefresh = { scope.launch { vm.chat.resync() } },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Pending approvals are pinned above the transcript: the agent is
            // blocked on this, so it must not be buried under chat history.
            items(chatState.pendingApprovals, key = { "perm-${it.id}" }) { request ->
                PermissionCard(
                    request = request,
                    onAllow = { scope.launch { vm.chat.replyPermission(request.id, allow = true) } },
                    onDeny = { scope.launch { vm.chat.replyPermission(request.id, allow = false) } },
                )
            }
            items(chatState.items, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.User -> UserBubble(item)
                    is ChatItem.Assistant -> AssistantBlock(item)
                }
            }
            if (chatState.busy) {
                item(key = "busy") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Working…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            chatState.turnError?.let { err ->
                item(key = "err") {
                    Text(err, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Composer(
            value = composer,
            busy = chatState.busy,
            onChange = { composer = it },
            onSend = {
                val text = composer.trim()
                if (text.isNotEmpty()) {
                    scope.launch { vm.chat.send(text) }
                    composer = ""
                }
            },
            onStop = { scope.launch { vm.chat.interrupt() } },
        )
    }
}

@Composable
private fun ConnectionBanner(state: ConnectionState, reason: dev.ryan.opencode.core.model.DisconnectReason) {
    if (state == ConnectionState.Connected) return
    val (bg, fg) = when (state) {
        ConnectionState.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == ConnectionState.Connecting || state == ConnectionState.Reconnecting) {
            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = when (state) {
                ConnectionState.Connecting -> "Connecting…"
                ConnectionState.Reconnecting -> "Reconnecting — ${reason.describe()}"
                ConnectionState.Failed -> "Can't reach the server: ${reason.describe()}"
                ConnectionState.Idle -> "Not connected"
                ConnectionState.Connected -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = fg,
        )
    }
}

@Composable
private fun SessionBar(
    title: String,
    busy: Boolean,
    expanded: Boolean,
    sessions: List<dev.ryan.opencode.core.model.Session>,
    activeId: String,
    onToggle: () -> Unit,
    onSelect: (dev.ryan.opencode.core.model.Session) -> Unit,
    onNew: () -> Unit,
    onRefresh: () -> Unit,
) {
    Box {
        Row(
            Modifier.fillMaxWidth()
                // The toggle lives on the title column only. It used to wrap the
                // whole Row, which meant tapping Resync or + also opened the
                // session menu — the buttons were fighting the bar they sit in.
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onToggle() },
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${sessions.size} session${if (sessions.size == 1) "" else "s"} · tap to switch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Resync", Modifier.size(19.dp)) }
            IconButton(onClick = onNew) { Icon(Icons.Filled.Add, "New session", Modifier.size(21.dp)) }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = onToggle) {
            // Never truncate silently: a capped list that gives no hint reads as
            // "those are all my sessions", which is how people conclude their
            // history has gone missing.
            sessions.sortedByDescending { it.time.updated }.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.displayTitle, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${s.time.updated.asRelative()} · ${s.tokens.total} tok",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onSelect(s) },
                    trailingIcon = if (s.id == activeId) {
                        { Text("•", color = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun UserBubble(item: ChatItem.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                item.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantBlock(item: ChatItem.Assistant) {
    var reasoningOpen by remember(item.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.agent.ifBlank { "assistant" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold,
            )
            if (item.model.isNotBlank()) {
                Text(" · ${item.model}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.streaming) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
            }
        }

        if (item.reasoning.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            // Collapsed by default: reasoning is context, not the answer, and a
            // three-line peephole was unreadable.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().clickable { reasoningOpen = !reasoningOpen },
            ) {
                Column(Modifier.padding(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Reasoning",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (reasoningOpen) "hide" else "show",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (reasoningOpen) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item.tools.forEach { tool -> ToolCard(tool) }

        if (item.text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(item.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ToolCard(tool: ChatItem.ToolActivity) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (tool.status) {
        ChatItem.ToolStatus.Running -> MaterialTheme.colorScheme.tertiary
        ChatItem.ToolStatus.Done -> MaterialTheme.colorScheme.secondary
        ChatItem.ToolStatus.Failed -> MaterialTheme.colorScheme.error
    }
    Spacer(Modifier.height(5.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(statusColor, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(7.dp))
                Text(tool.name, style = MaterialTheme.typography.labelMedium)
                if (tool.status == ChatItem.ToolStatus.Running) {
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                }
            }
            if (tool.input.isNotBlank() && expanded) {
                Spacer(Modifier.height(5.dp))
                Text(
                    tool.input,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tool.output.isNotBlank() && expanded) {
                Spacer(Modifier.height(5.dp))
                Text(
                    tool.output.take(2000),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    busy: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // Edge-to-edge: the composer has to lift itself above the IME. Without this
    // it renders underneath the keyboard and the app looks like it has no input
    // at all — which reads as broken rather than as a layout bug.
    Row(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message opencode…", fontSize = 15.sp) },
            maxLines = 5,
            shape = RoundedCornerShape(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { if (busy) onStop() else onSend() },
            modifier = Modifier.size(46.dp),
        ) {
            Icon(
                if (busy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (busy) "Stop" else "Send",
            )
        }
    }
}

private fun Long.asRelative(): String {
    if (this <= 0) return "never"
    val delta = System.currentTimeMillis() - this
    return when {
        delta < 60_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}
