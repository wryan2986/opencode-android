package dev.ryan.opencode.core.ssh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ryan.opencode.core.TerminalSignal
import dev.ryan.opencode.core.model.DisconnectReason
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.Closeable
import java.security.Security

/**
 * A real shell on a remote host, over SSH, independent of opencode.
 *
 * ## Why this exists separately from the opencode PTY
 *
 * The opencode transport gets its PTY from `POST /api/pty`, so the terminal is
 * only as available as the opencode server. If opencode is wedged — or not
 * installed on that host at all — there is no shell, and the terminal is exactly
 * the tool you would reach for. This path has no such dependency: it is an SSH
 * session with a PTY, and it works on any host you can ssh to.
 *
 * ## What is deliberately absent
 *
 * No password auth and no agent forwarding. The key from [SshKeyStore] is the
 * only credential, and it is expected to be scoped in `authorized_keys` with
 * `restrict` so it can run commands but cannot write to the server or forward
 * ports. A lost phone is then revoked by deleting one line.
 */
class SshTerminal(
    private val profile: SshProfile,
    private val keyStore: SshKeyStore,
) : Closeable, dev.ryan.opencode.core.TerminalLink {

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Bounded history of output, so a UI that subscribes late still sees the
     * opening prompt.
     *
     * The output flow has `replay = 0`, which is correct for control messages and
     * wrong for terminal bytes: the shell prompt arrives before the UI is
     * listening, and since bash then blocks on input, nothing else ever arrives to
     * trigger a redraw. The screen stays blank while the offset counter climbs.
     *
     * Unlike the opencode PTY there is no server-side cursor to resume from — SSH
     * cannot replay a session it no longer holds — so this is a recovery buffer
     * for reconnects of *this* object, not a resume mechanism. A real reconnect
     * gets a fresh screen.
     */
    private val history = ArrayDeque<Pair<Long, ByteArray>>()
    private val historyLock = Any()
    private var historyBytes = 0
    private var historyLimit = 256 * 1024
    private val consumed = java.util.concurrent.atomic.AtomicLong(0)

    override fun outputSince(offset: Long): List<ByteArray> = synchronized(historyLock) {
        history.filter { it.first + it.second.size > offset }.map { it.second }
    }

    override fun consumedOffset(): Long = consumed.get()

    private fun recordHistory(start: Long, bytes: ByteArray) {
        synchronized(historyLock) {
            history.addLast(start to bytes)
            historyBytes += bytes.size
            while (historyBytes > historyLimit && history.size > 1) {
                historyBytes -= history.removeFirst().second.size
            }
        }
    }

    private fun note(bytes: ByteArray) {
        val start = consumed.getAndAdd(bytes.size.toLong())
        recordHistory(start, bytes)
    }

    private val _signals = MutableSharedFlow<TerminalSignal>(
        replay = 0,
        // A full-screen redraw can outrun a slow render. Dropping the oldest chunk
        // keeps typing responsive; the emulator recovers on the next full write.
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val signals: SharedFlow<TerminalSignal> = _signals.asSharedFlow()

    private val _status = MutableStateFlow<SshStatus>(SshStatus.Idle)
    val status: StateFlow<SshStatus> = _status.asStateFlow()

    /** Report a dropped connection the way the terminal supervisor expects. */
    private fun reportDrop(reason: dev.ryan.opencode.core.model.DisconnectReason) {
        _connected.value = false
        _signals.tryEmit(TerminalSignal.Dropped(reason))
    }

    @Volatile private var client: SSHClient? = null
    @Volatile private var session: Session? = null
    private var pump: Job? = null
    private val writeLock = Any()

    /** Host keys to persist after a connect, if the pin state changed. */
    @Volatile var updatedKnownHosts: Map<String, String> = profile.knownHosts
        private set

    /**
     * Make BouncyCastle available to sshj, exactly once per process.
     *
     * sshj resolves `"ECDSA"` through the BC provider explicitly and throws
     * `NoSuchAlgorithmException: ECDSA KeyFactory not available` without it — the
     * first touch of `KeyType` fails, so this has to happen before anything else in
     * the SSH path, not lazily inside a try block.
     *
     * Verified against real sshd: with the provider absent, key exchange succeeds
     * and authentication then fails with `Exhausted available authentication
     * methods`, which reads like a credential problem rather than a missing JCE
     * provider. Registering BC last keeps the platform's own implementations in
     * front, so this only supplies what the system does not.
     */
    private fun ensureCryptoProvider() {
        if (Security.getProvider("BC") != null) return
        runCatching {
            Security.addProvider(
                org.bouncycastle.jce.provider.BouncyCastleProvider(),
            )
        }.onFailure {
            android.util.Log.e(TAG, "BouncyCastle unavailable; SSH will not authenticate", it)
        }
    }

    suspend fun connect(cols: Int, rows: Int) {
        disconnect()
        ensureCryptoProvider()
        _status.value = SshStatus.Connecting

        val pair = keyStore.keyPair()
            ?: throw SSHException("No SSH key on this device yet — generate one in Settings.")

        val ssh = SSHClient()
        ssh.setConnectTimeout(CONNECT_TIMEOUT_MS)
        ssh.setTimeout(IO_TIMEOUT_MS)

        val hostKey = HostKeyStore(
            known = profile.knownHosts,
            pin = profile.pinHostKey,
            onDecision = { decision ->
                Log.i(TAG, "host key $decision for ${profile.host}")
                _status.value = when (decision) {
                    HostKeyStore.Decision.CHANGED -> SshStatus.HostKeyChanged
                    HostKeyStore.Decision.PINNED_NEW -> SshStatus.Pinned
                    else -> SshStatus.Connected
                }
            },
        )
        ssh.addHostKeyVerifier(hostKey)

        try {
            withContext(Dispatchers.IO) {
                ssh.connect(profile.host, profile.port)
                ssh.authPublickey(profile.user, keyProviderFor(pair))
            }
        } catch (e: Exception) {
            ssh.disconnect()
            _status.value = SshStatus.Failed(e.message ?: e::class.simpleName.orEmpty())
            throw e
        }
        client = ssh
        updatedKnownHosts = hostKey.updatedKnown

        lastCols = cols
        lastRows = rows
        val s = ssh.startSession()
        session = s
        val modes = mapOf(
            PTYMode.ECHO to 1,
            PTYMode.TTY_OP_ISPEED to 115200,
            PTYMode.TTY_OP_OSPEED to 115200,
        )
        s.allocatePTY(profile.termType, cols, rows, 0, 0, modes)

        // Attach to tmux when the profile asks for it, otherwise start a shell.
        // `exec` matters for the same reason as in the opencode PTY: the process
        // must own the terminal so a later resize reaches the right client.
        val remote = buildString {
            append("exec ")
            when {
                profile.tmuxSession.isNotBlank() -> append("tmux attach -t ").append(profile.tmuxSession)
                profile.launchCommand.isNotBlank() -> append(profile.launchCommand)
                else -> append("bash -l")
            }
        }
        val cmd = s.exec(remote)
        val inStream = cmd.inputStream
        _connected.value = true
        _status.value = SshStatus.Connected
        _signals.tryEmit(TerminalSignal.Opened)

        pump = scope.launch {
            val buf = ByteArray(READ_CHUNK)
            try {
                while (isActive) {
                    val n = inStream.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val chunk = buf.copyOf(n)
                    note(chunk)
                    _signals.emit(TerminalSignal.Output(chunk))
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "ssh read ended: ${e.message}")
                    reportDrop(
                        if (e is java.io.IOException) DisconnectReason.NetworkLost
                        else DisconnectReason.Unknown,
                    )
                }
            } finally {
                _connected.value = false
                if (_status.value != SshStatus.HostKeyChanged) _status.value = SshStatus.Closed
            }
        }
    }

    override fun write(data: String) {
        scope.launch { send(data.toByteArray(Charsets.UTF_8)) }
    }

    override fun writeBytes(data: ByteArray) {
        scope.launch { send(data) }
    }

    override fun reply(bytes: ByteArray) {
        scope.launch { send(bytes) }
    }

    /** Send keystrokes to the remote PTY. */
    private suspend fun send(bytes: ByteArray) {
        val out = session?.outputStream ?: return
        withContext(Dispatchers.IO) {
            synchronized(writeLock) { out.write(bytes); out.flush() }
        }
    }

    /** Tell the server the window changed, so tmux and curses resize properly. */
    override fun resize(cols: Int, rows: Int) {
        scope.launch {
            lastCols = cols
            lastRows = rows
            runCatching { resizeRemote(cols, rows) }
        }
    }

    /**
     * Send a window-size change.
     *
     * This must be `changeWindowDimensions`, **not** a second `allocatePTY`. A PTY
     * can only be allocated once per channel, and calling it again makes sshd tear
     * the connection down with `Protocol error: you already have a pty` — verified
     * against real sshd. Since `resize` fires on every rotation, getting this wrong
     * would kill the session the first time the phone turned.
     *
     * Going through the API also means tmux and curses get a real SIGWINCH rather
     * than silently rendering at the size they started with.
     */
    private suspend fun resizeRemote(cols: Int, rows: Int) {
        val s = session ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                (s as? net.schmizz.sshj.connection.channel.direct.SessionChannel)
                    ?.changeWindowDimensions(cols, rows, 0, 0)
            }
        }.onFailure {
            // A closed session here is expected on teardown; anything else is worth
            // seeing because it means resizes are not reaching the terminal.
            Log.w(TAG, "resize to ${cols}x$rows failed: ${it.message}")
        }
    }

    /**
     * Close the SSH session.
     *
     * This kills the remote shell and any process inside it — including a tmux
     * client, which will detach but leave its tmux session running on the server.
     * That is the behaviour you want: detach, not destroy.
     */
    override fun detach() {
        scope.launch {
            disconnect()
            _status.value = SshStatus.Idle
        }
    }

    /**
     * Come back after a drop.
     *
     * A new session is opened rather than resumed, because SSH has no equivalent of
     * the opencode PTY's byte cursor. Anything the previous session was doing at a
     * raw terminal level is gone; if it was inside tmux, the tmux session survives
     * on the server and reattaching picks it up.
     */
    override fun reattach() {
        scope.launch {
            _signals.tryEmit(TerminalSignal.Attaching)
            runCatching { connect(lastCols, lastRows) }
                .onFailure { reportDrop(DisconnectReason.Unknown) }
        }
    }

    private var lastCols = 80
    private var lastRows = 24

    suspend fun disconnect() {
        pump?.cancelAndJoin()
        pump = null
        runCatching { session?.close() }
        runCatching { client?.disconnect() }
        session = null
        client = null
        _connected.value = false
        if (_status.value.isLive()) _status.value = SshStatus.Idle
    }

    override fun close() {
        scope.launch { disconnect() }
        scopeJob.cancel()
    }

    /**
     * Hand sshj the key.
     *
     * [KeyPairWrapper] takes a JCE `KeyPair` directly, so there is no PEM
     * serialisation and no file on disk. sshj picks the signature algorithm from
     * `KeyType.fromKey`, which resolves ECDSA P-256 to `ecdsa-sha2-nistp256` and
     * hashes with SHA-256 — both what sshd expects for this key.
     */
    private fun keyProviderFor(pair: java.security.KeyPair) =
        net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper(pair)

    companion object {
        private const val TAG = "SshTerminal"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IO_TIMEOUT_MS = 20_000
        private const val READ_CHUNK = 8192
    }
}

sealed interface SshStatus {
    fun isLive(): Boolean = this is Connected

    data object Idle : SshStatus
    data object Connecting : SshStatus
    data object Connected : SshStatus
    data object Closed : SshStatus
    data object Pinned : SshStatus
    data object HostKeyChanged : SshStatus
    data class Failed(val reason: String) : SshStatus
}