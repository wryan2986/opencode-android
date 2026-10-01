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
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.Closeable

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
) : Closeable {

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _output = MutableSharedFlow<ByteArray>(
        replay = 0,
        // A full-screen redraw can outrun a slow render. Dropping the oldest chunk
        // keeps typing responsive; the emulator recovers on the next full write.
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    private val _status = MutableStateFlow<SshStatus>(SshStatus.Idle)
    val status: StateFlow<SshStatus> = _status.asStateFlow()

    @Volatile private var client: SSHClient? = null
    @Volatile private var session: Session? = null
    private var pump: Job? = null
    private val writeLock = Any()

    /** Host keys to persist after a connect, if the pin state changed. */
    @Volatile var updatedKnownHosts: Map<String, String> = profile.knownHosts
        private set

    suspend fun connect(cols: Int, rows: Int) {
        disconnect()
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

        pump = scope.launch {
            val buf = ByteArray(READ_CHUNK)
            try {
                while (isActive) {
                    val n = inStream.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    _output.emit(buf.copyOf(n))
                }
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "ssh read ended: ${e.message}")
            } finally {
                _connected.value = false
                if (_status.value != SshStatus.HostKeyChanged) _status.value = SshStatus.Closed
            }
        }
    }

    /** Send keystrokes to the remote PTY. */
    suspend fun write(bytes: ByteArray) {
        val out = session?.outputStream ?: return
        withContext(Dispatchers.IO) {
            synchronized(writeLock) { out.write(bytes); out.flush() }
        }
    }

    fun write(text: String) {
        scope.launch { write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Tell the server the window changed, so tmux and curses resize properly. */
    suspend fun resize(cols: Int, rows: Int) {
        val s = session ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                s.allocatePTY(
                    profile.termType, cols, rows, 0, 0,
                    mapOf(PTYMode.TTY_OP_ISPEED to 115200, PTYMode.TTY_OP_OSPEED to 115200),
                )
            }
        }
    }

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