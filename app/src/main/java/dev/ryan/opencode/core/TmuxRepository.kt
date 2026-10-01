package dev.ryan.opencode.core

import dev.ryan.opencode.core.net.OpencodeClient
import dev.ryan.opencode.core.net.PtyCapture
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** One row of `tmux list-sessions`. */
data class TmuxSession(
    val name: String,
    val windows: Int,
    val attached: Int,
    val created: Long,
) {
    val inUse: Boolean get() = attached > 0
}

/**
 * Drives tmux through the opencode PTY API.
 *
 * tmux is not a special case here — it is just a program running in a PTY, so
 * everything goes over the same verified `/api/pty` surface the shell already
 * uses. Nothing server-side had to be added or patched.
 *
 * ## The socket is the whole ballgame
 *
 * tmux scopes sessions to a unix socket. The `opencode-server.service` unit runs
 * with `PrivateTmp=true`, and tmux's default socket lives in `/tmp` — so before
 * that was disabled, every PTY got its own private `/tmp`, hence its own tmux
 * server, invisible to both your shell and the app. With `PrivateTmp` off, PTYs
 * and your shell share `/tmp/tmux-$UID/default` and a session started from the
 * phone shows up in your terminal, and vice versa. That is why this uses the
 * default socket rather than a private one.
 *
 * Caveat: a PTY inherits the service's environment, so if opencode is ever
 * started from inside a tmux client, `$TMUX` is inherited and tmux resolves to
 * that client's session instead. `tmux` is deliberately not given an explicit
 * `-S` path so that the app and a hand-typed shell agree on what "the" session
 * list means.
 */
class TmuxRepository(
    private val api: OpencodeClient,
    private val clientProvider: () -> OkHttpClient,
    private val baseProvider: () -> HttpUrl,
    private val directoryProvider: () -> String,
) {
    /**
     * Session names are interpolated into a `bash -c` string, so they are
     * restricted rather than escaped. tmux itself forbids `.` and `:` in session
     * names (it reserves them for target syntax), so this matches what tmux
     * allows while removing quotes, spaces, `$` and `;` from play entirely.
     */
    fun isValidName(name: String): Boolean = isLegalTmuxName(name)

    /** Current sessions, newest last. Empty when no tmux server is running. */
    suspend fun list(): List<TmuxSession> {
        val out = PtyCapture.shell(
            client = clientProvider(),
            base = baseProvider(),
            api = api,
            directory = directoryProvider(),
            script = LIST_SCRIPT,
        )
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseTmuxLine(it) }
            .toList()
    }

    /**
     * The `command`/`args` for a PTY that attaches to [name].
     *
     * `exec` replaces the wrapping bash so the PTY *is* tmux: without it a
     * SIGWINCH on resize hits bash rather than the client, and tmux never learns
     * the new size.
     */
    fun attachCommand(name: String): Pair<String, List<String>> {
        require(isValidName(name)) { "Illegal tmux session name: $name" }
        return "bash" to listOf("-c", "exec tmux attach -t $name")
    }

    /** Starts a detached session that outlives the PTY that asked for it. */
    suspend fun create(name: String): Boolean {
        if (!isValidName(name)) return false
        val out = PtyCapture.shell(
            client = clientProvider(),
            base = baseProvider(),
            api = api,
            directory = directoryProvider(),
            script = "tmux has-session -t $name 2>/dev/null || tmux new-session -d -s $name; echo rc=\$?",
        )
        return !out.contains("rc=1")
    }

    suspend fun kill(name: String): Boolean {
        if (!isValidName(name)) return false
        return PtyCapture.shell(
            client = clientProvider(),
            base = baseProvider(),
            api = api,
            directory = directoryProvider(),
            script = "tmux kill-session -t $name; echo rc=\$?",
        ).contains("rc=0")
    }

    private companion object {
        // `|` is safe as a separator: tmux forbids `.` and `:` in session names,
        // so no field can contain one. 2>/dev/null keeps "no server running" from
        // being parsed as a session.
        const val LIST_SCRIPT =
            "tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_attached}|#{session_created}' 2>/dev/null; true"
    }
}

/**
 * tmux forbids `.` and `:` in session names — it reserves them for target syntax
 * like `session:window.pane` — so allowing only letters, digits, `-` and `_` is
 * both what tmux allows and enough to guarantee that interpolating a name into
 * a `bash -c` string cannot escape into the shell.
 *
 * The first character must be alphanumeric, not just `-` or `_`: a name like
 * `-t` would be read by tmux as a flag rather than a session name.
 */
private val LEGAL_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

internal fun isLegalTmuxName(name: String): Boolean = LEGAL_NAME.matches(name)

/** Parse one `list-sessions` row. Returns null for anything malformed. */
internal fun parseTmuxLine(line: String): TmuxSession? {
    val parts = line.split('|')
    if (parts.size < 4) return null
    val name = parts[0]
    if (name.isEmpty()) return null
    return TmuxSession(
        name = name,
        windows = parts[1].toIntOrNull() ?: 0,
        attached = parts[2].toIntOrNull() ?: 0,
        created = parts[3].toLongOrNull() ?: 0L,
    )
}
