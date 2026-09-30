package dev.ryan.opencode.core.model

import kotlinx.serialization.Serializable

/**
 * Typed payloads for the opencode v2 event stream, captured live from a real
 * tool-using turn on 2026-09-30.
 *
 * Two structural facts that the UI depends on:
 *
 *  1. **Events are location-scoped.** Every event carries a top-level
 *     `location: {directory}` alongside `data`. The app must filter by the
 *     active directory or it will render another project's turns.
 *  2. **`GET /session/{id}/message` returns newest-first**, so it must be
 *     reversed for display.
 */
@Serializable
data class EventEnvelopeRaw(
    val id: String = "",
    val created: Long = 0,
    val type: String = "",
    val location: LocationRef? = null,
    val data: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

@Serializable
data class ExecutionEvent(
    val sessionID: String = "",
)

@Serializable
data class TextEvent(
    val sessionID: String = "",
    val assistantMessageID: String = "",
    val ordinal: Int = 0,
    val delta: String? = null,
    val text: String? = null,
    val state: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class StepEvent(
    val sessionID: String = "",
    val assistantMessageID: String = "",
    val agent: String? = null,
    val model: MessageModel? = null,
    val finish: String? = null,
    val cost: Double = 0.0,
    val tokens: TokenUsage = TokenUsage(),
    val started: Long? = null,
)

@Serializable
data class ToolEvent(
    val sessionID: String = "",
    val assistantMessageID: String = "",
    val id: String = "",
    val name: String = "",
    val text: String? = null,
    val input: kotlinx.serialization.json.JsonObject? = null,
    val content: List<ToolContent> = emptyList(),
    val metadata: kotlinx.serialization.json.JsonObject? = null,
    val executed: Boolean = false,
)

@Serializable
data class ToolContent(
    val type: String = "",
    val text: String? = null,
)

@Serializable
data class RenamedEvent(
    val sessionID: String = "",
    val title: String = "",
)

@Serializable
data class UsageEvent(
    val sessionID: String = "",
    val cost: Double = 0.0,
    val tokens: TokenUsage = TokenUsage(),
)

@Serializable
data class InboxEvent(
    val sessionID: String = "",
    val inboxID: String = "",
)

/** Event type constants, so typos don't become silent no-ops. */
object EventTypes {
    const val SERVER_CONNECTED = "server.connected"
    const val EXECUTION_STARTED = "session.execution.started"
    const val EXECUTION_SUCCEEDED = "session.execution.succeeded"
    const val EXECUTION_ERROR = "session.execution.error"

    const val TEXT_STARTED = "session.text.started"
    const val TEXT_DELTA = "session.text.delta"
    const val TEXT_ENDED = "session.text.ended"

    const val REASONING_STARTED = "session.reasoning.started"
    const val REASONING_DELTA = "session.reasoning.delta"
    const val REASONING_ENDED = "session.reasoning.ended"

    const val TOOL_INPUT_STARTED = "session.tool.input.started"
    const val TOOL_INPUT_ENDED = "session.tool.input.ended"
    const val TOOL_CALLED = "session.tool.called"
    const val TOOL_PROGRESS = "session.tool.progress"
    const val TOOL_SUCCESS = "session.tool.success"
    const val TOOL_ERROR = "session.tool.error"

    const val STEP_STARTED = "session.step.started"
    const val STEP_STREAMED = "session.step.streamed"
    const val STEP_ENDED = "session.step.ended"

    const val SESSION_RENAMED = "session.renamed"
    const val SESSION_USAGE = "session.usage.updated"
    const val SESSION_IDLE = "session.idle"
    const val PERMISSION_REQUESTED = "permission.requested"
    const val PERMISSION_REPLIED = "permission.replied"
    const val PROJECT_UPDATED = "project.updated"
    const val SESSION_DELETED = "session.deleted"
}

@Serializable
data class PermissionEvent(
    val id: String = "",
    val sessionID: String = "",
    val type: String = "",
    val title: String? = null,
    val metadata: kotlinx.serialization.json.JsonObject? = null,
    val time: kotlinx.serialization.json.JsonObject? = null,
) {
    fun toRequest(): PermissionRequest = PermissionRequest(
        id = id,
        sessionID = sessionID,
        type = type,
        title = title,
        metadata = metadata,
        time = time,
    )
}
