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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.ryan.opencode.core.model.InboxItem
import dev.ryan.opencode.core.model.AgentInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

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

/**
 * Lightweight per-session liveness, for sessions that are not on screen.
 *
 * The full conversation for a background session is not held — the server owns it
 * and [resync] rebuilds on open. What is tracked here is only what the UI needs to
 * decide whether to nag you: is it working, and did it say something.
 */
data class SessionRuntime(
    val sessionId: String = "",
    val busy: Boolean = false,
    val unread: Int = 0,
    val awaitingApproval: Int = 0,
    val title: String = "",
)

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

    private val _runtimes = MutableStateFlow<Map<String, SessionRuntime>>(emptyMap())

    /** Every session seen this session, keyed by id. Drives the tab strip. */
    val runtimes: StateFlow<Map<String, SessionRuntime>> = _runtimes.asStateFlow()

    private var directory: String = ""
    private var toolBuffers = mutableMapOf<String, ToolEvent>()

    fun start() {
        scope.launch {
            connection.events.collect { signal ->
                when (signal) {
                    is StreamSignal.Event -> {
                        trackBackground(signal.event)
                        onEvent(signal.event)
                    }
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
            // Keep tab titles fresh; the runtime store only ever learns an id from
            // an event, so without this every chip falls back to a truncated id.
            _runtimes.value = _runtimes.value.mapValues { (id, r) ->
                _sessions.value.firstOrNull { it.id == id }
                    ?.let { r.copy(title = it.displayTitle) } ?: r
            }
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

    /**
     * Update liveness for *any* session, including ones not on screen.
     *
     * Deliberately additive: every existing handler keeps its
     * `sessionID == _state.value.sessionId` guard untouched, so the viewed
     * conversation behaves exactly as before. This only maintains the small summary
     * the tab strip reads, and it never writes conversation content — a background
     * session is rebuilt from the server by [resync] when opened.
     *
     * Events that carry no session id are ignored rather than guessed at.
     */
    private fun trackBackground(event: dev.ryan.opencode.core.model.ServerEvent) {
        val id = runCatching {
            event.data["sessionID"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return

        val viewing = _state.value.sessionId
        val current = _runtimes.value[id] ?: SessionRuntime(sessionId = id)
        if (id == viewing) return

        _runtimes.value = _runtimes.value + (id to when {
            event.type.endsWith("execution.started") -> current.copy(busy = true)
            event.type.endsWith("execution.succeeded") ||
                event.type.endsWith("execution.failed") ||
                event.type.endsWith("execution.error") -> current.copy(busy = false)
            event.type.contains("text") || event.type.contains("reasoning") ->
                current.copy(unread = current.unread + 1)
            else -> current
        })
    }

    /** Mark a session read — called when the user switches to it. */
    fun markRead(sessionId: String) {
        _runtimes.value = _runtimes.value + (sessionId to (_runtimes.value[sessionId]
            ?: SessionRuntime(sessionId = sessionId)).copy(unread = 0))
    }

    suspend fun openSession(sessionId: String) {
        markRead(sessionId)
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
            val all = connection.api.pendingPermissions(directory)
            val openId = _state.value.sessionId
            _state.value = _state.value.copy(
                pendingApprovals = all.filter { it.sessionID == openId },
            )
            // Deliberately *not* filtered to the open session. A permission
            // request on some other conversation is exactly the thing you would
            // otherwise never learn about, and it blocks that session until
            // answered — so it needs to be visible from here, not only from inside
            // that conversation.
            _attention.value = all
                .filter { it.sessionID != openId }
                .groupBy { it.sessionID }
        }
    }

    /**
     * Sessions other than the open one that are waiting on an approval.
     *
     * This is the cheap half of multi-session awareness: it does not change the
     * event pipeline or let two conversations run in one view, but it removes the
     * failure where work stalls silently in a background conversation.
     */
    private val _attention =
        MutableStateFlow<Map<String?, List<dev.ryan.opencode.core.model.PermissionRequest>>>(emptyMap())
    val attention: StateFlow<Map<String?, List<dev.ryan.opencode.core.model.PermissionRequest>>> =
        _attention.asStateFlow()

    /** How many background sessions are blocked, for a badge. */
    val attentionCount: StateFlow<Int> = _attention
        .map { it.size }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0)

    // ---- task queue, sub-agents, agents ----

    private val _queue = MutableStateFlow<List<InboxItem>>(emptyList())
    val queue: StateFlow<List<InboxItem>> = _queue.asStateFlow()

    /**
     * Child sessions — the sub-agent threads opencode spawns for itself.
     *
     * These are real, addressable sessions with a `parentID`, so what the agent
     * fans out to is visible and openable here rather than hidden inside a tool
     * call. Filtering is client-side because the list endpoint returns both.
     */
    private val _subAgents = MutableStateFlow<List<Session>>(emptyList())
    val subAgents: StateFlow<List<Session>> = _subAgents.asStateFlow()

    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agents: StateFlow<List<AgentInfo>> = _agents.asStateFlow()

    private val _activeAgent = MutableStateFlow<String?>(null)
    val activeAgent: StateFlow<String?> = _activeAgent.asStateFlow()

    /** Refresh the queue and the spawned sub-agent list for the open session. */
    suspend fun refreshQueue() {
        val sid = _state.value.sessionId
        if (sid.isBlank()) return
        runCatching { connection.api.inbox(sid, directory) }
            .onSuccess { _queue.value = it }
            .onFailure { android.util.Log.w("ChatRepository", "inbox failed: ${it.message}") }
        _subAgents.value = _sessions.value.filter { it.parentID == sid }
    }

    suspend fun refreshAgents() {
        runCatching { connection.api.agents(directory) }
            .onSuccess { _agents.value = it }
    }

    /**
     * Rename a session.
     *
     * The client already spoke to `PATCH /api/session/{id}`; it just had no UI.
     * A blank title is refused here rather than sent, because the server will
     * happily store an empty one and the session then renders as "Untitled".
     */
    fun renameSession(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        scope.launch {
            runCatching { connection.api.renameSession(id, clean, directory) }
                .onSuccess { updated ->
                    _sessions.value = _sessions.value.map { if (it.id == id) updated else it }
                }
                .onFailure {
                    android.util.Log.w("ChatRepository", "rename failed: ${it.message}")
                }
        }
    }

    /**
     * Delete a session.
     *
     * Deliberately does *not* switch away or refresh the open conversation when
     * the deleted session is the active one: the caller decides, because quietly
     * moving the user somewhere else is worse than showing them a session that no
     * longer exists.
     */
    fun deleteSession(id: String, onDeleted: () -> Unit = {}) {
        scope.launch {
            runCatching { connection.api.deleteSession(id, directory) }
                .onSuccess {
                    _sessions.value = _sessions.value.filterNot { it.id == id }
                    _subAgents.value = _subAgents.value.filterNot { it.id == id }
                    onDeleted()
                }
                .onFailure {
                    android.util.Log.w("ChatRepository", "delete failed: ${it.message}")
                }
        }
    }

    fun cancelQueued(id: String) {
        val sid = _state.value.sessionId
        scope.launch {
            runCatching { connection.api.cancelInbox(sid, id, directory) }
            refreshQueue()
        }
    }

    /** Switch agent for subsequent turns. Takes effect from the next turn. */
    fun useAgent(name: String) {
        val sid = _state.value.sessionId
        if (sid.isBlank()) return
        scope.launch {
            runCatching { connection.api.setSessionAgent(sid, name, directory) }
                .onSuccess { _activeAgent.value = name }
                .onFailure { android.util.Log.w("ChatRepository", "agent switch failed: ${it.message}") }
        }
    }

    /** Move running tools into background observation. */
    fun sendToBackground() {
        val sid = _state.value.sessionId
        if (sid.isBlank()) return
        scope.launch {
            runCatching { connection.api.moveToBackground(sid, directory) }
                .onFailure { android.util.Log.w("ChatRepository", "background failed: ${it.message}") }
        }
    }

    /**
     * Send with an explicit delivery.
     *
     * `steer` reaches the running turn immediately, which is what makes this feel
     * conversational rather than a queue you shout into. `queue` is the safe
     * default when the user is laying up work for later.
     */
    suspend fun sendAs(text: String, delivery: String) {
        val sid = _state.value.sessionId
        if (sid.isBlank()) return
        runCatching { connection.api.prompt(sid, text, directory, delivery) }
            .onSuccess { refreshQueue() }
            .onFailure { android.util.Log.w("ChatRepository", "send($delivery) failed: ${it.message}") }
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
