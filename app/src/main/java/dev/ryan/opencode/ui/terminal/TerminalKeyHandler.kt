package dev.ryan.opencode.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.ryan.opencode.core.TerminalLink

/**
 * Translates Android key events into the byte sequences a shell expects.
 *
 * The terminal screen has no text field, so without this the on-screen keyboard
 * opens and nothing reaches the shell. Kept separate from the emulator, which is
 * pure Kotlin and unit-tested, because this is entirely about Android's key model.
 */
class TerminalKeyHandler(private val pty: dev.ryan.opencode.core.TerminalLink) {

    /** Feed a key-down event to the PTY. Returns true if it was consumed. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val sequence = encodeFor(event.key) ?: return false
        pty.write(sequence)
        return true
    }

    fun encode(event: KeyEvent): String? = encodeFor(event.key)

    companion object {
        /**
         * Pure key -> byte-sequence mapping.
         *
         * Exposed statically so it is unit-testable on the JVM without a live
         * socket; the instance [encode] simply delegates here.
         */
        fun encodeFor(key: Key): String? = map(key)

        private fun map(key: Key): String? = when (key) {
            Key.Enter, Key.NumPadEnter -> "\r"
            Key.Backspace -> "\u007F"
            Key.Tab -> "\t"
            Key.Escape -> ESC
            Key.DirectionUp -> ESC + "[A"
            Key.DirectionDown -> ESC + "[B"
            Key.DirectionRight -> ESC + "[C"
            Key.DirectionLeft -> ESC + "[D"
            Key.MoveHome -> ESC + "[H"
            Key.MoveEnd -> ESC + "[F"
            Key.PageUp -> ESC + "[5~"
            Key.PageDown -> ESC + "[6~"
            else -> null
        }

        private const val ESC = "\u001B"
    }
}
