package dev.ryan.opencode.core.net

import dev.ryan.opencode.core.model.DisconnectReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Attaches to a server-side PTY over WebSocket and survives network loss.
 *
 * This is the mechanism that removes the need to ever manually reconnect:
 *
 *  - the shell process lives on the **server**, so it keeps running while the
 *    phone is offline;
 *  - the client tracks the byte offset it has actually consumed and replays it
 *    as `?cursor=N` on reconnect, so the server sends only the missed bytes.
 *
 * Verified end-to-end: dropped mid-stream, reconnected from a tracked offset of
 * 4274 bytes, received the output produced while offline, and zero duplicated
 * bytes.
 *
 * Frame protocol (verified):
 *  - client -> server input: a **raw text frame** containing literal bytes.
 *    JSON frames are not parsed and end up written verbatim into the shell.
 *  - server -> client data: raw ANSI bytes.
 *  - server -> client control: `\0` + JSON, e.g. `\0{"cursor":4274}`.
 */
class PtySocket(
    private val clientProvider: () -> OkHttpClient,
    private val baseProvider: () -> okhttp3.HttpUrl,
    private val api: OpencodeClient,
    private val directoryProvider: () -> String,
) {
    private val _signals = MutableSharedFlow<PtySignal>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val signals: Flow<PtySignal> = _signals.asSharedFlow()

    /**
     * Recent output, kept so a late or restarted consumer can catch up.
     *
     * `signals` has `replay = 0`, which is right for control messages but wrong for
     * terminal output: the shell prompt typically arrives before the UI has
     * subscribed, and with nothing to replay it is lost permanently. Because bash
     * then sits idle waiting for input, no further bytes ever arrive to trigger a
     * redraw — the screen simply stays blank while the offset counter climbs.
     *
     * We keep a bounded window of (startOffset, bytes) so a consumer can ask for
     * everything it has not yet seen. This is also what makes an in-place reconnect
     * safe if the consumer's offset is behind.
     */
    private val history = ArrayDeque<Pair<Long, ByteArray>>()
    private val historyLock = Any()
    private var historyBytes = 0

    /** Bytes of output any consumer can still recover. */
    private val historyLimit = 256 * 1024

    /** Output produced at or after [offset], oldest first. */
    fun outputSince(offset: Long): List<ByteArray> = synchronized(historyLock) {
        history.filter { it.first + it.second.size > offset }
            .map { it.second }
    }

    private fun recordHistory(startOffset: Long, bytes: ByteArray) = synchronized(historyLock) {
        history.addLast(startOffset to bytes)
        historyBytes += bytes.size
        while (historyBytes > historyLimit && history.size > 1) {
            historyBytes -= history.removeFirst().second.size
        }
    }

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    private var socket: WebSocket? = null
    private var ptyId: String? = null
    private var closedByUs = false
    private val connecting = AtomicBoolean(false)
    private val sendLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bytes we have actually handed to the emulator. This is our resume point. */
    private val consumed = AtomicLong(0)

    var lastDisconnectReason: DisconnectReason = DisconnectReason.None
        private set

    /** Total bytes replayed by the server on the most recent attach. */
    var lastReplayBytes: Int = 0
        private set

    fun consumedOffset(): Long = consumed.get()

    fun attach(id: String, resetOffset: Boolean = true) {
        if (resetOffset) consumed.set(0)
        ptyId = id
        closedByUs = false
        lastDisconnectReason = DisconnectReason.None
        openSocket()
    }

    private fun openSocket() {
        val id = ptyId ?: return
        if (!connecting.compareAndSet(false, true)) return
        _signals.tryEmit(PtySignal.Attaching)

        scope.launch {
            try {
                val token = api.ptyConnectToken(id, directoryProvider())
                val base = baseProvider()
                val url = base.newBuilder()
                    .addPathSegments("api/pty/$id/connect")
                    .addQueryParameter("ticket", token.data.ticket)
                    .addQueryParameter("input_protocol", "1")
                    .apply {
                        val off = consumed.get()
                        if (off > 0) addQueryParameter("cursor", off.toString())
                    }
                    .build()
                    // NB: keep the http(s) scheme here. OkHttp's newWebSocket performs
                    // the upgrade itself and Request.Builder rejects a ws:// URL.

                val headers = HashMap<String, String>()
                // OkHttp's WebSocket does not run client interceptors, so copy auth by hand.
                SessionCookieStore.current?.let {
                    headers["Cookie"] = "${sessionCookieName(base)}=$it"
                }
                BasicAuthStore.current?.let { headers["Authorization"] = it }

                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()

                connecting.set(false)
                socket = clientProvider().newWebSocket(request, listener)
            } catch (e: Exception) {
                android.util.Log.w("PtySocket", "attach failed for $id: ${e::class.simpleName}: ${e.message}")
                connecting.set(false)
                _connected.value = false
                lastDisconnectReason = DisconnectReason.Unknown
                if (!closedByUs) _signals.tryEmit(PtySignal.Dropped(DisconnectReason.Unknown))
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            android.util.Log.i("PtySocket", "pty socket open")
            _connected.value = true
            lastDisconnectReason = DisconnectReason.None
            lastReplayBytes = 0
            _signals.tryEmit(PtySignal.Opened)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(text.toByteArray(Charsets.UTF_8))
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            handleFrame(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
            lastDisconnectReason = DisconnectReason.ServerClosed
            if (!closedByUs) _signals.tryEmit(PtySignal.Dropped(DisconnectReason.ServerClosed))
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connecting.set(false)
            _connected.value = false
            val reason = classify(t, response)
            lastDisconnectReason = reason
            if (!closedByUs) _signals.tryEmit(PtySignal.Dropped(reason))
        }
    }

    private fun classify(t: Throwable, response: Response?): DisconnectReason {
        if (response?.code == 401 || response?.code == 403) return DisconnectReason.AuthFailed
        val msg = t.message?.lowercase().orEmpty()
        return when {
            msg.contains("unable to resolve host") || msg.contains("no address") ||
                msg.contains("network is unreachable") || msg.contains("failed to connect") ||
                msg.contains("econnrefused") || msg.contains("timeout") ||
                msg.contains("unexpected end of stream") -> DisconnectReason.NetworkLost
            else -> DisconnectReason.Unknown
        }
    }

    private fun handleFrame(bytes: ByteArray) {
        // Control frames are NUL-prefixed JSON; everything else is terminal output.
        val trimmed = bytes.decodeToString().trimStart('\u0000', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{")) {
            runCatching { OpencodeJson.decodeFromString<PtyControl>(trimmed) }
                .getOrNull()
                ?.let { ctrl -> _signals.tryEmit(PtySignal.Control(ctrl)) }
            return
        }
        val start = consumed.getAndAdd(bytes.size.toLong())
        recordHistory(start, bytes)
        _signals.tryEmit(PtySignal.Output(bytes))
    }

    /** Send literal bytes to the shell. Raw text frames only — see class docs. */
    fun write(data: String) {
        socket?.send(data)
    }

    fun writeBytes(data: ByteArray) {
        socket?.send(data.toString(Charsets.UTF_8))
    }

    /** Reply to a device report (cursor position, attributes) requested by the shell. */
    fun reply(bytes: ByteArray) {
        socket?.send(bytes.toString(Charsets.UTF_8))
    }

    suspend fun resize(cols: Int, rows: Int) {
        sendLock.withLock { write("\u001b[8;${rows};${cols}t") }
    }

    fun detach() {
        closedByUs = true
        socket?.close(1000, "client detach")
        socket = null
        _connected.value = false
    }

    /**
     * Re-attach after a drop. Called by the connection supervisor; the tracked
     * byte offset means the server replays only what we missed.
     */
    fun reattach() {
        if (ptyId == null) return
        socket?.cancel()
        socket = null
        _connected.value = false
        connecting.set(false)
        openSocket()
    }
}

sealed interface PtySignal {
    data object Attaching : PtySignal
    data object Opened : PtySignal
    data class Output(val bytes: ByteArray) : PtySignal {
        override fun equals(other: Any?) = other is Output && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    data class Control(val control: PtyControl) : PtySignal
    data class Dropped(val reason: DisconnectReason) : PtySignal
}

@kotlinx.serialization.Serializable
data class PtyControl(
    val cursor: Long? = null,
    val start: String? = null,
    val exit: Int? = null,
)

/**
 * Holds the credentials OkHttp's WebSocket needs, which bypasses interceptors.
 * Set once when the connection is (re)configured.
 */
object BasicAuthStore {
    @Volatile var current: String? = null
}

object SessionCookieStore {
    @Volatile var current: String? = null
}
