package dev.ryan.opencode.ui.terminal

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Key encoding for the shell.
 *
 * Getting these wrong is destructive rather than cosmetic: a Ctrl+C that arrives as
 * a literal "c" means the user cannot interrupt a runaway command, which is the one
 * thing a terminal must always allow.
 *
 * `encodeFor` takes a plain [Key], so this runs on the JVM with no Android stubs.
 */
class TerminalKeyHandlerTest {

    private fun encode(key: Key): String? = TerminalKeyHandler.encodeFor(key)

    @Test
    fun `enter sends carriage return`() {
        assertEquals("\r", encode(Key.Enter))
        assertEquals("\r", encode(Key.NumPadEnter))
    }

    @Test
    fun `backspace sends DEL so the shell deletes backwards`() {
        // Terminals expect 0x7F; 0x08 is commonly ignored or beeps.
        assertEquals("", encode(Key.Backspace))
    }

    @Test
    fun `escape is a bare ESC`() {
        assertEquals("", encode(Key.Escape))
    }

    @Test
    fun `arrow keys use CSI sequences`() {
        assertEquals("[A", encode(Key.DirectionUp))
        assertEquals("[B", encode(Key.DirectionDown))
        assertEquals("[C", encode(Key.DirectionRight))
        assertEquals("[D", encode(Key.DirectionLeft))
    }

    @Test
    fun `navigation keys use CSI and tilde forms`() {
        assertEquals("[H", encode(Key.MoveHome))
        assertEquals("[F", encode(Key.MoveEnd))
        assertEquals("[5~", encode(Key.PageUp))
        assertEquals("[6~", encode(Key.PageDown))
    }

    @Test
    fun `tab is a literal tab`() {
        assertEquals("\t", encode(Key.Tab))
    }

    @Test
    fun `printable keys are not claimed so the IME path handles them`() {
        // If these returned a value, letters would be sent twice or not at all.
        assertNull(encode(Key.A))
        assertNull(encode(Key.NumLock))
        assertNull(encode(Key.NumLock))
    }
}
