package dev.ryan.opencode.core.net

import dev.ryan.opencode.core.model.AgentInfo
import dev.ryan.opencode.core.model.CommandInfo
import dev.ryan.opencode.core.model.ConnectTokenEnvelope
import dev.ryan.opencode.core.model.Message
import dev.ryan.opencode.core.model.MessagePage
import dev.ryan.opencode.core.model.PairingCode
import dev.ryan.opencode.core.model.PairToken
import dev.ryan.opencode.core.model.PermissionListEnvelope
import dev.ryan.opencode.core.model.Project
import dev.ryan.opencode.core.model.PtyCreateEnvelope
import dev.ryan.opencode.core.model.PtyInfo
import dev.ryan.opencode.core.model.PtyListEnvelope
import dev.ryan.opencode.core.model.ServerInfo
import dev.ryan.opencode.core.model.Session
import dev.ryan.opencode.core.model.SessionPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin typed wrapper over the opencode v2 REST API.
 *
 * Two response conventions coexist on the server and both are handled here:
 * bare payloads (`/api/info`, `/api/project`) and enveloped (`{data: ...}`).
 */
class OpencodeClient(
    private val http: OkHttpClientProvider,
) {
    interface OkHttpClientProvider {
        val client: okhttp3.OkHttpClient
        val base: HttpUrl
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val b = http.base.newBuilder()
        path.trim('/').split('/').forEach { b.addPathSegment(it) }
        // `location[directory]` — the server expects a deepObject, not a flat value.
        params["location[directory]"]?.let { b.addQueryParameter("location[directory]", it) }
        params.filterKeys { it != "location[directory]" }.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val res = http.client.await(Request.Builder().url(url(path, params)).get().build())
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, res.errorMessage())
            text
        }

    private suspend fun post(
        path: String,
        body: String? = null,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url(path, params)).post(
            (body ?: "{}").toRequestBody(jsonMedia)
        )
        headers.forEach { (k, v) -> rb.header(k, v) }
        val res = http.client.await(rb.build())
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw ApiException(res.code, res.errorMessage())
        text
    }

    private suspend fun patch(path: String, body: String): String = withContext(Dispatchers.IO) {
        val res = http.client.await(
            Request.Builder().url(url(path)).patch(body.toRequestBody(jsonMedia)).build()
        )
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw ApiException(res.code, res.errorMessage())
        text
    }

    private suspend fun delete(path: String, params: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val res = http.client.await(Request.Builder().url(url(path, params)).delete().build())
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, res.errorMessage())
            text
        }

    // ---- bootstrap ----

    suspend fun info(): ServerInfo = OpencodeJson.decodeFromString(get("/api/info"))

    /** Bare array response — not enveloped. */
    suspend fun projects(): List<Project> =
        OpencodeJson.decodeFromString(get("/api/project"))

    suspend fun agents(directory: String): List<AgentInfo> =
        OpencodeJson.decodeFromString(get("/api/agent", mapOf("location[directory]" to directory)))

    suspend fun commands(directory: String): List<CommandInfo> =
        OpencodeJson.decodeFromString(get("/api/command", mapOf("location[directory]" to directory)))

    // ---- pairing ----

    /** Requires basic auth — this is how the phone trades its password for a session token. */
    suspend fun pair(basicUser: String, basicPassword: String): PairingCode {
        val rb = Request.Builder().url(url("/api/pair")).post("{}".toRequestBody(jsonMedia))
            .header("Authorization", okhttp3.Credentials.basic(basicUser, basicPassword))
        val res = http.client.await(rb.build())
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw ApiException(res.code, res.errorMessage())
        return OpencodeJson.decodeFromString(text)
    }

    suspend fun redeemPairingCode(code: String): String {
        val res = http.client.await(
            Request.Builder().url(url("/auth/connect/${java.net.URLEncoder.encode(code, "UTF-8")}")).get().build()
        )
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw ApiException(res.code, res.errorMessage())
        return OpencodeJson.decodeFromString<PairToken>(text).token
    }

    // ---- sessions ----

    suspend fun sessions(directory: String): List<Session> {
        val raw = get("/api/session", mapOf("location[directory]" to directory))
        return runCatching { OpencodeJson.decodeFromString<SessionPage>(raw).data }
            .recoverCatching { OpencodeJson.decodeFromString<List<Session>>(raw) }
            .getOrElse { e ->
                android.util.Log.w("OpencodeClient", "sessions decode failed: ${e.message}")
                emptyList()
            }
    }

    suspend fun createSession(directory: String, title: String? = null): Session {
        val body = buildString {
            append("{")
            if (title != null) append("\"title\":${OpencodeJson.encodeToString(kotlinx.serialization.serializer(), title)},")
            append("\"location\":{\"directory\":${OpencodeJson.encodeToString(kotlinx.serialization.serializer(), directory)}}")
            append("}")
        }
        val raw = post("/api/session", body, mapOf("location[directory]" to directory))
        return OpencodeJson.decodeFromString<Map<String, Session>>(raw)["data"]
            ?: throw ApiException(-1, "Malformed session response")
    }

    suspend fun renameSession(id: String, title: String, directory: String): Session {
        val body = OpencodeJson.encodeToString(
            kotlinx.serialization.serializer(),
            mapOf("title" to title)
        )
        val raw = patch("/api/session/$id", body)
        return OpencodeJson.decodeFromString<Map<String, Session>>(raw)["data"]
            ?: throw ApiException(-1, "Malformed session response")
    }

    suspend fun deleteSession(id: String, directory: String) {
        delete("/api/session/$id", mapOf("location[directory]" to directory))
    }

    suspend fun messages(sessionId: String, directory: String, limit: Int? = null): MessagePage {
        val params = buildMap {
            put("location[directory]", directory)
            limit?.let { put("limit", it.toString()) }
        }
        return OpencodeJson.decodeFromString(get("/api/session/$sessionId/message", params))
    }

    suspend fun message(sessionId: String, messageId: String, directory: String): Message {
        val raw = get(
            "/api/session/$sessionId/message/$messageId",
            mapOf("location[directory]" to directory)
        )
        return OpencodeJson.decodeFromString<Map<String, Message>>(raw)["data"]
            ?: throw ApiException(-1, "Malformed message response")
    }

    /** Fire-and-forget prompt. Streaming arrives over the event stream, not this call. */
    suspend fun prompt(sessionId: String, text: String, directory: String): Message {
        val body = OpencodeJson.encodeToString(
            kotlinx.serialization.serializer(),
            PromptBody(text = text)
        )
        val raw = post("/api/session/$sessionId/prompt", body, mapOf("location[directory]" to directory))
        return OpencodeJson.decodeFromString<Map<String, Message>>(raw)["data"]
            ?: throw ApiException(-1, "Malformed prompt response")
    }

    /** Hard stop for the current turn — this is the barge-in primitive for voice mode. */
    suspend fun interrupt(sessionId: String, directory: String) {
        post("/api/session/$sessionId/interrupt", "{}", mapOf("location[directory]" to directory))
    }

    suspend fun setModel(sessionId: String, providerId: String, modelId: String, directory: String) {
        post(
            "/api/session/$sessionId/model",
            OpencodeJson.encodeToString(
                kotlinx.serialization.serializer(),
                ModelSelection(providerID = providerId, modelID = modelId)
            ),
            mapOf("location[directory]" to directory)
        )
    }

    suspend fun setAgent(sessionId: String, agent: String, directory: String) {
        post(
            "/api/session/$sessionId/agent",
            OpencodeJson.encodeToString(kotlinx.serialization.serializer(), AgentSelection(agent)),
            mapOf("location[directory]" to directory)
        )
    }

    suspend fun forkSession(sessionId: String, directory: String): Session {
        val raw = post(
            "/api/session/$sessionId/fork", "{}",
            mapOf("location[directory]" to directory)
        )
        return OpencodeJson.decodeFromString<Map<String, Session>>(raw)["data"]
            ?: throw ApiException(-1, "Malformed fork response")
    }

    // ---- permissions ----

    suspend fun pendingPermissions(directory: String): List<dev.ryan.opencode.core.model.PermissionRequest> {
        val raw = get("/api/permission/request", mapOf("location[directory]" to directory))
        return runCatching { OpencodeJson.decodeFromString<PermissionListEnvelope>(raw).data }
            .recoverCatching { OpencodeJson.decodeFromString<List<dev.ryan.opencode.core.model.PermissionRequest>>(raw) }
            .getOrDefault(emptyList())
    }

    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        response: String,
        remember: Boolean,
        directory: String,
    ) {
        post(
            "/api/session/$sessionId/permission/$requestId/reply",
            OpencodeJson.encodeToString(
                kotlinx.serialization.serializer(),
                PermissionReply(response = response, remember = remember)
            ),
            mapOf("location[directory]" to directory)
        )
    }

    // ---- pty ----

    suspend fun listPtys(directory: String): List<PtyInfo> =
        OpencodeJson.decodeFromString<PtyListEnvelope>(
            get("/api/pty", mapOf("location[directory]" to directory))
        ).data

    suspend fun createPty(
        directory: String,
        command: String = "bash",
        args: List<String> = listOf("-l"),
        cols: Int = 80,
        rows: Int = 24,
        title: String = "shell",
    ): PtyInfo {
        val body = OpencodeJson.encodeToString(
            kotlinx.serialization.serializer(),
            PtyCreateRequest(command, args, directory, title, size = dev.ryan.opencode.core.model.PtySize(cols, rows))
        )
        return OpencodeJson.decodeFromString<PtyCreateEnvelope>(
            post("/api/pty", body, mapOf("location[directory]" to directory))
        ).data
    }

    /**
     * Mint a WebSocket ticket.
     *
     * Verified quirk: the server rejects this with 403 unless the request carries
     * `x-opencode-ticket: 1` **and** no `Origin` header (a cross-origin request is
     * refused 401 as a CSRF guard). OkHttp never sends Origin, so we are fine.
     * The ticket only lives 60s, so mint a new one on every connect.
     */
    suspend fun ptyConnectToken(ptyId: String, directory: String): ConnectTokenEnvelope {
        val raw = post(
            "/api/pty/$ptyId/connect-token", "{}",
            mapOf("location[directory]" to directory),
            headers = mapOf("x-opencode-ticket" to "1"),
        )
        return OpencodeJson.decodeFromString(raw)
    }

    suspend fun deletePty(ptyId: String) {
        runCatching { delete("/api/pty/$ptyId") }
    }
}

@kotlinx.serialization.Serializable
data class PromptBody(val text: String)

@kotlinx.serialization.Serializable
data class ModelSelection(val providerID: String, val modelID: String)

@kotlinx.serialization.Serializable
data class AgentSelection(val agent: String)

@kotlinx.serialization.Serializable
data class PermissionReply(val response: String, val remember: Boolean = false)

@kotlinx.serialization.Serializable
data class PtyCreateRequest(
    val command: String,
    val args: List<String>,
    val cwd: String,
    val title: String,
    val size: dev.ryan.opencode.core.model.PtySize,
)

class ApiException(val status: Int, override val message: String) : Exception(message) {
    val isAuthFailure: Boolean get() = status == 401
}
