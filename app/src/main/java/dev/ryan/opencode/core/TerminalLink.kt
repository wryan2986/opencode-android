package dev.ryan.opencode.core

import dev.ryan.opencode.core.model.DisconnectReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A terminal's byte pipe, whatever is behind it.
 *
 * This is the seam that lets the terminal screen be transport-agnostic.
 * [dev.ryan.opencode.core.net.PtySocket] implements it over the opencode
 * WebSocket API; [dev.ryan.opencode.core.ssh.SshTerminal] implements it over SSH.
 * `TerminalEmulator` is pure-Kotlin VT100 and takes bytes, so it never learns
 * which one it is attached to.
 *
 * The two implementations differ in one important way and it shows up here: the
 * opencode PTY can replay from a byte offset across a reconnect, while an SSH
 * session cannot — dropping the connection means the shell is gone. So
 * [outputSince] is a *recovery buffer* for both, not a server-side cursor, and a
 * reconnect on the SSH route starts a fresh screen rather than resuming one.
 */
interface TerminalLink {
    val connected: StateFlow<Boolean>

    /** Output and lifecycle events. */
    val signals: Flow<TerminalSignal>

    /**
     * Bytes emitted at or after [offset], oldest first.
     *
     * The first read always uses offset 0 so the opening prompt is not lost: the
     * signal flow has no replay, so output produced before the UI subscribed is
     * otherwise gone — and since bash then blocks on input, nothing else ever
     * arrives to trigger a redraw and the screen stays blank.
     */
    fun outputSince(offset: Long): List<ByteArray>

    /** Bytes handed to the emulator so far. The resume point. */
    fun consumedOffset(): Long

    fun write(data: String)
    fun writeBytes(data: ByteArray)

    /** Answer a device report the shell asked for (cursor position, attributes). */
    fun reply(bytes: ByteArray)

    /** Non-suspending: callers are UI callbacks, and both transports dispatch. */
    fun resize(cols: Int, rows: Int)

    /** Hang up, keeping server-side state where the transport allows it. */
    fun detach()

    /** Come back after a drop. */
    fun reattach()
}

sealed interface TerminalSignal {
    data object Attaching : TerminalSignal
    data object Opened : TerminalSignal
    data class Output(val bytes: ByteArray) : TerminalSignal {
        override fun equals(other: Any?) = other is Output && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    data class Dropped(val reason: DisconnectReason) : TerminalSignal
}