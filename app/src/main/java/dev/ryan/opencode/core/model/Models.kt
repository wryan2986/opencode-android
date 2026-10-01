package dev.ryan.opencode.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Models for the opencode v2 HTTP API.
 *
 * Verified against opencode 2.0.20 on 2026-09-30. Note the API mixes enveloped
 * (`{data: ...}`) and bare responses; see [dev.ryan.opencode.core.net.OpencodeClient].
 * Everything here is deliberately tolerant of unknown/added fields.
 */

@Serializable
data class Envelope<T>(val data: T? = null)

@Serializable
data class ServerInfo(
    val version: String = "",
    val pid: Int = 0,
    val urls: List<String> = emptyList(),
)

@Serializable
data class Project(
    val id: String = "",
    val canonical: String = "",
    val time: ProjectTime = ProjectTime(),
    val sandboxes: List<JsonElement> = emptyList(),
) {
    val name: String get() = canonical.trimEnd('/').substringAfterLast('/').ifEmpty { canonical }
}

@Serializable
data class ProjectTime(
    val created: Long = 0,
    val updated: Long = 0,
    val active: Long = 0,
)

@Serializable
data class LocationRef(val directory: String? = null)

@Serializable
data class Session(
    val id: String = "",
    val projectID: String = "",
    val title: String? = null,
    val parentID: String? = null,
    val cost: Double = 0.0,
    val tokens: TokenUsage = TokenUsage(),
    val time: SessionTime = SessionTime(),
    val location: LocationRef? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled session"
    val isChild: Boolean get() = parentID != null
}

@Serializable
data class SessionTime(
    val created: Long = 0,
    val updated: Long = 0,
)

@Serializable
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: CacheUsage = CacheUsage(),
) {
    val total: Long get() = input + output + reasoning
}

@Serializable
data class CacheUsage(val read: Long = 0, val write: Long = 0)

/**
 * A message in a session. The v2 API uses a tagged union on [type]:
 *  - `user`      -> [text]
 *  - `assistant` -> [content] parts, [model], [agent]
 *  - `idle`      -> [outcome]; a synthetic marker emitted when a turn finishes
 */
@Serializable
data class Message(
    val id: String = "",
    val sessionID: String? = null,
    val type: String = "",
    val time: MessageTime = MessageTime(),
    // user
    val text: String? = null,
    // assistant
    val agent: String? = null,
    val model: MessageModel? = null,
    val content: List<ContentPart> = emptyList(),
    val finish: String? = null,
    val cost: Double = 0.0,
    val tokens: TokenUsage = TokenUsage(),
    // idle
    val outcome: String? = null,
    val error: JsonObject? = null,
) {
    val isUser: Boolean get() = type == "user"
    val isAssistant: Boolean get() = type == "assistant"
    val isIdle: Boolean get() = type == "idle"
    val failed: Boolean get() = outcome == "error" || error != null

    /** Visible text of an assistant message (reasoning excluded). */
    val textContent: String
        get() = content.filter { it.type == "text" }.joinToString("") { it.text.orEmpty() }

    val reasoning: String
        get() = content.filter { it.type == "reasoning" }.joinToString("\n") { it.text.orEmpty() }

    val completed: Boolean get() = time.completed != null
}

@Serializable
data class MessageTime(
    val created: Long = 0,
    val streamed: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class MessageModel(
    val id: String? = null,
    val providerID: String? = null,
)

/**
 * One part of an assistant message.
 *
 * For `type = "tool"`, `id`, `name` and `executed` sit at the **top level** of the
 * part — the payload lives in [state]. Reading them out of `state` (as an earlier
 * version did) silently yielded a generic "tool" label for every historical call.
 */
@Serializable
data class ContentPart(
    val type: String = "",
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val executed: Boolean? = null,
    val state: ToolPartState? = null,
    val time: JsonObject? = null,
)

@Serializable
data class ToolPartState(
    val status: String = "",
    val input: JsonObject? = null,
    val content: List<ToolContent> = emptyList(),
    val metadata: JsonObject? = null,
) {
    val output: String get() = content.filter { it.type == "text" }.joinToString("\n") { it.text.orEmpty() }
    val exitCode: Int?
        get() = metadata?.get("exit")?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        }
    val failed: Boolean get() = status == "error" || (exitCode != null && exitCode != 0)
}

/**
 * `GET /api/session` returns `{data: [...], cursor: {...}}`.
 *
 * Note the cursor values are opaque base64 tokens, not nulls — decoding this as a
 * bare `Map<String, List<Session>>` fails on the cursor object and silently
 * collapses the whole list to empty.
 */
@Serializable
data class SessionPage(
    val data: List<Session> = emptyList(),
    val cursor: PageCursor = PageCursor(),
)

@Serializable
data class MessagePage(
    val data: List<Message> = emptyList(),
    val cursor: PageCursor = PageCursor(),
)

@Serializable
data class PageCursor(
    val previous: String? = null,
    val next: String? = null,
)

/** An event pushed over the SSE stream at `GET /api/event`. */
@Serializable
data class ServerEvent(
    val id: String = "",
    val created: Long = 0,
    val type: String = "",
    /** Events are location-scoped; filter on this or you will render another project's turns. */
    val location: LocationRef? = null,
    val data: JsonObject = JsonObject(emptyMap()),
) {
    inline fun <reified T> decode(): T? = runCatching {
        dev.ryan.opencode.core.net.OpencodeJson.decodeFromString<T>(data.toString())
    }.getOrNull()
}

// ---- PTY ----

@Serializable
data class PtyInfo(
    val id: String = "",
    val title: String? = null,
    val command: String = "",
    val args: List<String> = emptyList(),
    val cwd: String = "",
    val status: String = "",
    val pid: Int = 0,
    val exitCode: Int? = null,
)

@Serializable
data class PtySize(val cols: Int = 80, val rows: Int = 24)

@Serializable
data class PtyListEnvelope(
    val location: LocationRef? = null,
    val data: List<PtyInfo> = emptyList(),
)

@Serializable
data class PtyCreateEnvelope(
    val location: LocationRef? = null,
    val data: PtyInfo = PtyInfo(),
)

@Serializable
data class ConnectToken(
    val ticket: String = "",
    @SerialName("expires_in") val expiresIn: Int = 60,
)

@Serializable
data class ConnectTokenEnvelope(
    val location: LocationRef? = null,
    val data: ConnectToken = ConnectToken(),
)

@Serializable
data class PairingCode(val code: String = "", @SerialName("expires_in") val expiresIn: Int = 300)

@Serializable
data class PairToken(val token: String = "")

@Serializable
data class AgentInfo(
    val name: String = "",
    val description: String? = null,
    val mode: String? = null,
)

@Serializable
data class CommandInfo(
    val name: String = "",
    val description: String? = null,
    val agent: String? = null,
)

@Serializable
data class SlashCommand(
    val name: String = "",
    val description: String = "",
    val agent: String? = null,
)

@Serializable
data class PermissionRequest(
    val id: String = "",
    val sessionID: String? = null,
    val type: String = "",
    val title: String? = null,
    val metadata: JsonObject? = null,
    val time: JsonObject? = null,
)

@Serializable
data class PermissionListEnvelope(val data: List<PermissionRequest> = emptyList())

/** Live connection state, surfaced in the UI. */
enum class ConnectionState {
    Idle,
    Connecting,
    Connected,
    Reconnecting,
    Failed,
}

/** Why the connection dropped — drives whether we retry and what we tell the user. */
enum class DisconnectReason {
    None,
    NetworkLost,
    NetworkChanged,
    ServerClosed,
    AuthFailed,
    Unknown,
}

/**
 * One item waiting in the session inbox.
 *
 * [delivery] is the field that matters: `steer` means the server injects this into
 * the turn already running — the conversational "actually, do this instead"
 * primitive — while `queue` means it waits for the current turn to finish. A UI
 * that hides the difference makes a steered message look like it was ignored.
 */
@kotlinx.serialization.Serializable
data class InboxItem(
    val id: String = "",
    val type: String = "user",
    val text: String = "",
    val delivery: String = "queue",
    val created: Long = 0,
) {
    val isSteer: Boolean get() = delivery == "steer"

    /** One line, collapsed — the queue is read at a glance, not studied. */
    val summary: String get() = text.trim().replace(Regex("\\s+"), " ").take(90)
}
