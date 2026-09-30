package dev.ryan.opencode.core

import dev.ryan.opencode.core.model.EventTypes
import dev.ryan.opencode.core.model.ExecutionEvent
import dev.ryan.opencode.core.model.PermissionEvent
import dev.ryan.opencode.core.model.PermissionRequest
import dev.ryan.opencode.core.model.Message
import dev.ryan.opencode.core.model.Project
import dev.ryan.opencode.core.model.RenamedEvent
import dev.ryan.opencode.core.model.ServerEvent
import dev.ryan.opencode.core.model.Session
import dev.ryan.opencode.core.model.StepEvent
import dev.ryan.opencode.core.model.TextEvent
import dev.ryan.opencode.core.model.ToolContent
import dev.ryan.opencode.core.model.ToolEvent
import dev.ryan.opencode.core.net.StreamSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the chat screen renders. Built by folding the event stream onto the
 * message history the server holds.
 */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val created: Long) : ChatItem

    data class Assistant(
        override val key: String,
        val agent: String = "",
        val model: String = "",
        val text: String = "",
        val reasoning: String = "",
        val tools: List<ToolActivity> = emptyList(),
        val streaming: Boolean = false,
        val finished: Boolean = false,
        val finishReason: String = "",
        val cost: Double = 0.0,
    ) : ChatItem

    data class ToolActivity(
        val id: String,
        val name: String,
        val input: String = "",
        val output: String = "",
        val status: ToolStatus = ToolStatus.Running,
        val exitCode: Int? = null,
    )

    enum class ToolStatus { Running, Done, Failed }
}

/** Live state for one session. */
data class ChatState(
    val sessionId: String = "",
    val items: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
    val pendingApprovals: List<dev.ryan.opencode.core.model.PermissionRequest> = emptyList(),
    val turnError: String? = null,
)

/**
 * Folds server events into a renderable conversation.
 *
 * Because the server owns the conversation, this class holds no authoritative
 * state — after any gap (reconnect, cold start, backgrounded app) we simply
 * re-read `GET /session/{id}/message` and rebuild. That is why there is no
 * "reconnect your session" UX to get wrong.
 */
class ChatRepository(
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
    private val notifications: NotificationCentre? = null,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    /** Live assistant text for the in-flight turn — the voice engine consumes this. */
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private var directory: String = ""
    private var toolBuffers = mutableMapOf<String, ToolEvent>()

    fun start() {
        scope.launch {
            connection.events.collect { signal ->
                when (signal) {
                    is StreamSignal.Event -> onEvent(signal.event)
                    is StreamSignal.Open -> refreshSessions()
                    else -> Unit
                }
            }
        }
    }

    fun setDirectory(dir: String) {
        directory = dir
    }

    suspend fun bootstrap() {
        runCatching { _projects.value = connection.api.projects() }
            .onFailure { android.util.Log.w("ChatRepository", "projects failed: ${it.message}") }
        refreshSessions()
    }

    suspend fun refreshSessions() {
        if (directory.isBlank()) return
        runCatching {
            _sessions.value = connection.api.sessions(directory).sortedByDescending { it.time.updated }
            android.util.Log.i("ChatRepository", "loaded ${_sessions.value.size} sessions in $directory")
            // Auto-open the newest session here rather than in a LaunchedEffect:
            // opening changes the session id, which would cancel the very effect
            // doing the opening.
            if (_state.value.sessionId.isBlank()) {
                _sessions.value.firstOrNull()?.let { openSession(it.id) }
            }
        }.onFailure {
            android.util.Log.w("ChatRepository", "sessions failed: ${it.message}")
        }
    }

    suspend fun openSession(sessionId: String) {
        _state.value = ChatState(sessionId = sessionId)
        toolBuffers = mutableMapOf()
        _liveText.value = ""
        resync(sessionId)
    }

    /**
     * Rebuild the whole conversation from the server. Called on open, on reconnect,
     * and whenever we detect we may have missed events.
     */
    suspend fun resync(sessionId: String = _state.value.sessionId) {
        if (sessionId.isBlank() || directory.isBlank()) return
        runCatching {
            val page = connection.api.messages(sessionId, directory)
            // The API returns newest-first; display order is oldest-first.
            val chronological = page.data.asReversed()
            val items = chronological.mapNotNull { it.toItem() }
            android.util.Log.i("ChatRepository", "resync $sessionId: ${page.data.size} msgs -> ${items.size} items")
            _state.value = _state.value.copy(
                sessionId = sessionId,
                items = items,
                busy = false,
            )
        }.onFailure {
            android.util.Log.w("ChatRepository", "resync failed for $sessionId: ${it.message}")
        }
    }

    private fun Message.toItem(): ChatItem? = when (type) {
        "user" -> ChatItem.User(id, text.orEmpty(), time.created)
        "assistant" -> ChatItem.Assistant(
            key = id,
            agent = agent.orEmpty(),
            model = model?.id.orEmpty(),
            text = textContent,
            reasoning = reasoning,
            tools = content.filter { it.type == "tool" }.map { part ->
                val st = part.state
                ChatItem.ToolActivity(
                    id = part.id.orEmpty().ifBlank { part.name.orEmpty() },
                    name = part.name?.takeIf { it.isNotBlank() } ?: "tool",
                    input = st?.input?.toString().orEmpty(),
                    output = st?.output.orEmpty(),
                    exitCode = st?.exitCode,
                    status = when {
                        st == null -> ChatItem.ToolStatus.Done
                        st.failed -> ChatItem.ToolStatus.Failed
                        st.status == "running" || st.status == "pending" -> ChatItem.ToolStatus.Running
                        else -> ChatItem.ToolStatus.Done
                    },
                )
            },
            streaming = false,
            finished = time.completed != null,
            finishReason = finish.orEmpty(),
            cost = cost,
        )
        else -> null
    }

    // ---- event folding ----

    private fun onEvent(event: ServerEvent) {
        // Ignore other locations; the server multiplexes every project onto one stream.
        val eventDir = event.location?.directory
        if (eventDir != null && directory.isNotBlank() && eventDir != directory) return

        when (event.type) {
            EventTypes.TEXT_DELTA -> {
                val t = event.decode<TextEvent>() ?: return
                appendText(t)
            }

            EventTypes.TEXT_ENDED -> {
                val t = event.decode<TextEvent>() ?: return
                // The full text is authoritative; reconcile any dropped deltas.
                t.text?.let { full ->
                    if (full != currentText(t.assistantMessageID)) setAssistantText(t.assistantMessageID, full, streamed = true)
                }
                markTextEnded(t.assistantMessageID)
            }

            EventTypes.TEXT_STARTED -> {
                val t = event.decode<TextEvent>() ?: return
                ensureAssistant(t.assistantMessageID)
            }

            EventTypes.REASONING_DELTA -> {
                val t = event.decode<TextEvent>() ?: return
                ensureAssistant(t.assistantMessageID)
                appendReasoning(t.assistantMessageID, t.delta.orEmpty())
            }

            EventTypes.REASONING_ENDED -> {
                val t = event.decode<TextEvent>() ?: return
                t.text?.let { full ->
                    if (full != currentReasoning(t.assistantMessageID)) setAssistantReasoning(t.assistantMessageID, full)
                }
            }

            EventTypes.TOOL_INPUT_STARTED, EventTypes.TOOL_CALLED -> {
                val t = event.decode<ToolEvent>() ?: return
                toolBuffers[t.id] = t
                ensureAssistant(t.assistantMessageID)
                upsertTool(
                    t.assistantMessageID, ChatItem.ToolActivity(
                        id = t.id,
                        name = t.name.ifBlank { "tool" },
                        input = t.text ?: t.input?.toString().orEmpty(),
                        status = ChatItem.ToolStatus.Running,
                    )
                )
            }

            EventTypes.TOOL_SUCCESS -> {
                val t = event.decode<ToolEvent>() ?: return
                val output = t.content.filter { it.type == "text" }.joinToString("\n") { it.text.orEmpty() }
                val prior = toolBuffers[t.id]
                upsertTool(
                    t.assistantMessageID,
                    ChatItem.ToolActivity(
                        id = t.id,
                        name = t.name.ifBlank { prior?.name.orEmpty().ifBlank { "tool" } },
                        input = prior?.text ?: prior?.input?.toString().orEmpty(),
                        output = output,
                        status = ChatItem.ToolStatus.Done,
                    ),
                    append = true
                )
            }

            EventTypes.TOOL_ERROR -> {
                val t = event.decode<ToolEvent>() ?: return
                upsertTool(
                    t.assistantMessageID,
                    ChatItem.ToolActivity(
                        id = t.id,
                        name = t.name.ifBlank { toolBuffers[t.id]?.name.orEmpty() },
                        input = toolBuffers[t.id]?.text.orEmpty(),
                        output = t.text.orEmpty(),
                        status = ChatItem.ToolStatus.Failed,
                    ),
                    append = true
                )
            }

            EventTypes.STEP_ENDED -> {
                val t = event.decode<StepEvent>() ?: return
                markAssistantFinished(t.assistantMessageID, t.finish.orEmpty(), t.cost)
            }

            EventTypes.EXECUTION_STARTED -> {
                val t = event.decode<ExecutionEvent>() ?: return
                if (t.sessionID == _state.value.sessionId) {
                    _state.value = _state.value.copy(busy = true, turnError = null)
                }
            }

            EventTypes.EXECUTION_SUCCEEDED -> {
                val t = event.decode<ExecutionEvent>() ?: return
                if (t.sessionID == _state.value.sessionId) {
                    _state.value = _state.value.copy(busy = false)
                    notifyTurnFinished()
                }
            }

            EventTypes.SESSION_RENAMED -> {
                val t = event.decode<RenamedEvent>() ?: return
                _sessions.value = _sessions.value.map {
                    if (it.id == t.sessionID) it.copy(title = t.title) else it
                }
            }

            // A finished turn is authoritative on the server; resync rather than
            // guess, which also repairs anything we missed while disconnected.
            EventTypes.EXECUTION_ERROR -> {
                _state.value = _state.value.copy(busy = false, turnError = "Turn failed on the server")
            }

            EventTypes.SESSION_IDLE -> {
                _state.value = _state.value.copy(busy = false)
            }

            EventTypes.PERMISSION_REQUESTED -> {
                val req = event.decode<PermissionEvent>() ?: return
                if (req.sessionID != _state.value.sessionId) return
                val request = req.toRequest()
                val detail = request.title?.takeIf { it.isNotBlank() }
                    ?: request.type.takeIf { it.isNotBlank() }
                    ?: "The agent is waiting for your decision"
                _state.value = _state.value.copy(
                    pendingApprovals = _state.value.pendingApprovals.filterNot { it.id == request.id } + request
                )
                // This is the whole point of running headless: the phone can be in
                // a pocket while the agent waits for a human decision.
                notifications?.notifyPermissionNeeded(request, detail)
            }

            EventTypes.PERMISSION_REPLIED -> {
                val req = event.decode<PermissionEvent>() ?: return
                _state.value = _state.value.copy(
                    pendingApprovals = _state.value.pendingApprovals.filterNot { it.id == req.id }
                )
            }
        }
    }

    // ---- mutable helpers ----

    private fun ensureAssistant(messageId: String) {
        val items = _state.value.items
        if (items.any { it is ChatItem.Assistant && it.key == messageId }) return
        _state.value = _state.value.copy(
            items = items + ChatItem.Assistant(key = messageId, streaming = true)
        )
    }

    private fun currentText(messageId: String): String =
        (_state.value.items.lastOrNull { it is ChatItem.Assistant && it.key == messageId } as? ChatItem.Assistant)?.text.orEmpty()

    private fun currentReasoning(messageId: String): String =
        (_state.value.items.lastOrNull { it is ChatItem.Assistant && it.key == messageId } as? ChatItem.Assistant)?.reasoning.orEmpty()

    private fun mutateAssistant(messageId: String, block: (ChatItem.Assistant) -> ChatItem.Assistant) {
        var found = false
        val updated = _state.value.items.map { item ->
            if (item is ChatItem.Assistant && item.key == messageId) { found = true; block(item) } else item
        }
        if (found) _state.value = _state.value.copy(items = updated)
    }

    private fun appendText(t: TextEvent) {
        val delta = t.delta.orEmpty()
        if (delta.isEmpty()) return
        ensureAssistant(t.assistantMessageID)
        mutateAssistant(t.assistantMessageID) {
            it.copy(text = it.text + delta, streaming = true)
        }
        if (t.assistantMessageID == currentStreamingId()) {
            _liveText.value = currentText(t.assistantMessageID)
        }
    }

    private fun setAssistantText(messageId: String, text: String, streamed: Boolean) {
        mutateAssistant(messageId) { it.copy(text = text, streaming = streamed) }
        if (messageId == currentStreamingId()) _liveText.value = text
    }

    private fun setAssistantReasoning(messageId: String, text: String) {
        mutateAssistant(messageId) { it.copy(reasoning = text) }
    }

    private fun appendReasoning(messageId: String, delta: String) {
        if (delta.isEmpty()) return
        mutateAssistant(messageId) { it.copy(reasoning = it.reasoning + delta) }
    }

    private fun markTextEnded(messageId: String) {
        mutateAssistant(messageId) { it.copy(streaming = false) }
    }

    private fun markAssistantFinished(messageId: String, finish: String, cost: Double) {
        mutateAssistant(messageId) { it.copy(streaming = false, finished = true, finishReason = finish, cost = cost) }
    }

    private fun upsertTool(messageId: String, tool: ChatItem.ToolActivity, append: Boolean = false) {
        ensureAssistant(messageId)
        mutateAssistant(messageId) { a ->
            val existing = a.tools.indexOfFirst { it.id == tool.id }
            val tools = when {
                existing >= 0 -> a.tools.toMutableList().also { it[existing] = tool }
                append -> a.tools + tool
                else -> a.tools + tool
            }
            a.copy(tools = tools)
        }
    }

    private fun currentStreamingId(): String? =
        (_state.value.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)
            ?.takeIf { it.streaming }?.key

    private fun notifyTurnFinished() {
        val centre = notifications ?: return
        val last = _state.value.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
            ?: return
        val preview = last.text.take(180).replace(Regex("\\s+"), " ").trim()
        if (preview.isBlank()) return
        val title = _sessions.value.firstOrNull { it.id == _state.value.sessionId }?.displayTitle
            ?: "opencode"
        centre.notifyTurnFinished(title, preview)
    }

    // ---- commands ----

    /** Answer an inline permission prompt. */
    suspend fun replyPermission(requestId: String, allow: Boolean) {
        val sid = _state.value.sessionId
        if (sid.isBlank()) return
        _state.value = _state.value.copy(
            pendingApprovals = _state.value.pendingApprovals.filterNot { it.id == requestId }
        )
        runCatching {
            connection.api.replyPermission(
                sessionId = sid,
                requestId = requestId,
                response = if (allow) "once" else "reject",
                remember = false,
                directory = directory,
            )
        }.onFailure { _state.value = _state.value.copy(turnError = it.message) }
    }

    /**
     * Re-read pending approvals from the server.
     *
     * The event stream is the fast path, but approvals can also be raised by a TUI
     * session or another client while this app was backgrounded. Polling on resume
     * is cheap and closes that gap.
     */
    suspend fun refreshPermissions() {
        if (directory.isBlank()) return
        runCatching {
            val pending = connection.api.pendingPermissions(directory)
                .filter { it.sessionID == _state.value.sessionId }
            _state.value = _state.value.copy(pendingApprovals = pending)
        }
    }

    suspend fun send(text: String) {
        val sid = _state.value.sessionId
        if (sid.isBlank() || text.isBlank()) return
        // Optimistic append so the composer feels instant even on a slow link.
        _state.value = _state.value.copy(
            items = _state.value.items + ChatItem.User("local-${System.nanoTime()}", text, System.currentTimeMillis()),
            busy = true,
        )
        runCatching { connection.api.prompt(sid, text, directory) }
            .onFailure { e ->
                _state.value = _state.value.copy(busy = false, turnError = e.message)
            }
    }

    suspend fun interrupt() {
        val sid = _state.value.sessionId
        if (sid.isBlank()) return
        runCatching { connection.api.interrupt(sid, directory) }
    }

    suspend fun createSession(title: String? = null): Session? {
        val created = runCatching { connection.api.createSession(directory, title) }.getOrNull()
        if (created != null) {
            refreshSessions()
            openSession(created.id)
        }
        return created
    }
}
