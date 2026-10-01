package dev.ryan.opencode.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Runs a command in a throwaway server-side PTY and reads back what it printed.
 *
 * The terminal screen is the wrong tool for asking a question of the box: it renders
 * ANSI, wraps at the PTY width, and needs a human watching it. This creates a
 * throwaway PTY, drains its output as text, and deletes it.
 *
 * Two things make the output clean enough to parse:
 *  - the PTY is widened via [OpencodeClient.updatePty] immediately after creation,
 *    because `POST /api/pty` silently drops the requested size and the default is
 *    24x80 — long lines would otherwise wrap mid-field and corrupt the parse;
 *  - the command echoes a sentinel that we stop on, so we never have to guess when
 *    the command finished.
 */
object PtyCapture {

    private const val END = "__OPENCODE_CAPTURE_END__"
    private const val WIDE_COLS = 500
    private const val WIDE_ROWS = 60

    /**
     * Run [script] through `bash -c` and return its stdout, ANSI stripped.
     *
     * Returns whatever was captured even on timeout, so a command that prints
     * before hanging still yields its partial output rather than nothing.
     */
    suspend fun shell(
        client: OkHttpClient,
        base: HttpUrl,
        api: OpencodeClient,
        directory: String,
        script: String,
        timeoutMs: Long = 10_000,
    ): String = withContext(Dispatchers.IO) {
        var ptyId: String? = null
        var socket: WebSocket? = null
        try {
            // The server appends `-l` to args, so `-c` takes our script and `-l`
            // lands in $0. That is harmless and is the only way to get a shell
            // without an interactive prompt in the captured output.
            val scriptWithEnd = "$script" + "; printf '\\n$END:%s\\n' \$?"
            val info = api.createPty(
                directory = directory,
                command = "bash",
                args = listOf("-c", scriptWithEnd),
                cols = WIDE_COLS,
                rows = WIDE_ROWS,
                title = "capture",
            )
            ptyId = info.id
            api.updatePty(info.id, directory, WIDE_COLS, WIDE_ROWS)

            val token = api.ptyConnectToken(info.id, directory)
            val url = base.newBuilder()
                .addPathSegments("api/pty/${info.id}/connect")
                .addQueryParameter("ticket", token.data.ticket)
                .addQueryParameter("input_protocol", "1")
                .build()

            val headers = HashMap<String, String>()
            // OkHttp's WebSocket bypasses client interceptors, so auth is copied by hand.
            SessionCookieStore.current?.let { headers["Cookie"] = "${sessionCookieName(base)}=$it" }
            BasicAuthStore.current?.let { headers["Authorization"] = it }

            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }

            val done = CompletableDeferred<String>()
            val buffer = StringBuilder()
            val listener = object : WebSocketListener() {
                private fun feed(text: String) {
                    val value = synchronized(buffer) {
                        buffer.append(text)
                        if (buffer.contains(END)) buffer.toString() else null
                    }
                    if (value != null && !done.isCompleted) done.complete(value)
                }

                override fun onMessage(webSocket: WebSocket, text: String) = feed(text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                    feed(bytes.utf8())

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!done.isCompleted) done.complete(synchronized(buffer) { buffer.toString() })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!done.isCompleted) done.complete(synchronized(buffer) { buffer.toString() })
                }
            }

            socket = client.newWebSocket(builder.build(), listener)
            val raw = withTimeoutOrNull(timeoutMs) { done.await() } ?: buffer.toString()
            stripControl(stripAnsi(raw.substringBefore("\n$END:")))
        } finally {
            runCatching { socket?.cancel() }
            ptyId?.let { runCatching { api.deletePty(it) } }
        }
    }

    /**
     * Remove the server's control frames.
     *
     * The PTY WebSocket multiplexes two kinds of frame on one socket: raw terminal
     * bytes, and a control frame that is a NUL byte followed by JSON, e.g.
     * `\0{"cursor":4274}`. Verified — a fresh attach delivers `\0{"cursor":0}`
     * immediately, ahead of any command output. Parsed without stripping, that
     * turns the first `tmux list-sessions` row into a session literally named
     * `{"cursor":0}probe_alpha`.
     *
     * PtySocket already handles these for the emulator; a capture helper has to
     * do it too, or every parsed field is off by one frame.
     */
    internal fun stripControl(text: String): String =
        CONTROL.replace(text, "")

    /** Strip CSI/OSC escape sequences so parsed fields are clean text. */
    fun stripAnsi(text: String): String =
        ESCAPE.replace(text, "").replace('\r', '\n')

    // CSI (ESC [ ... final-byte) and OSC (ESC ] ... BEL).
    private val ESCAPE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")

    // NUL + JSON control frame, per the PTY wire protocol.
    private val CONTROL = Regex("\u0000\\{[^{}]*}")
}