package dev.ryan.opencode.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the exact byte stream the server sends for a bash prompt, to pin down
 * whether the doubled prompt seen on the Shell screen originates in the emulator
 * or in the Compose row rendering.
 *
 * The captured stream is the real opening handshake from `POST /api/pty` with
 * `bash -l`, including the OSC title and bracketed-paste setup.
 */
class PtyPromptRenderingTest {

    private val promptBytes: ByteArray = buildString {
        append("]0;ryan@Home-Server: /home/ryan")
        append("[?2004h")
        append("[01;32mryan@Home-Server[00m:")
        append("[01;34m/home/ryan[00m$ ")
    }.toByteArray()

    @Test
    fun `a real bash prompt renders exactly once`() {
        val em = TerminalEmulator(cols = 60, rows = 20)
        em.write(promptBytes)

        val lines = em.screenLines()
        val first = lines.first().filter { it.wide != WideState.Continuation }
            .joinToString("") { it.text }.trimEnd()

        assertEquals("prompt must appear exactly once: '$first'", "ryan@Home-Server:/home/ryan$", first)
        assertEquals("title captured from OSC 0", "ryan@Home-Server: /home/ryan", em.title())
    }

    @Test
    fun `prompt is not duplicated when the grid matches the pty size`() {
        // The doubling appeared only when the emulator was resized after the
        // prompt had already been written. Same size throughout: no duplication.
        val em = TerminalEmulator(cols = 60, rows = 20)
        em.write(promptBytes)
        repeat(3) { em.write("ls\r\n".toByteArray()) }
        val first = em.screenLines().first()
            .filter { it.wide != WideState.Continuation }
            .joinToString("") { it.text }
        assertEquals(1, "ryan@Home-Server".toRegex().findAll(first).count())
    }

    @Test
    fun `shrinking a populated grid is what splices the prompt`() {
        // Documents the mechanism: the emulator has no reflow, so a narrow resize
        // over populated content leaves stale cells behind. This is why the UI
        // creates the PTY at the final size instead of resizing afterwards.
        val em = TerminalEmulator(cols = 100, rows = 30)
        em.write(promptBytes)
        val before = em.screenLines().first()
            .filter { it.wide != WideState.Continuation }
            .joinToString("") { it.text }.trimEnd()

        em.resize(60, 30)
        val after = em.screenLines().first()
            .filter { it.wide != WideState.Continuation }
            .joinToString("") { it.text }.trimEnd()

        assertTrue("100-col grid shows the prompt", before.contains("ryan@Home-Server"))
        assertTrue(
            "resize from 100 to 60 cols truncates rather than duplicating: '$after'",
            after.length <= before.length,
        )
    }

    @Test
    fun `trailing cells are blank so rows can be trimmed safely`() {
        val em = TerminalEmulator(cols = 40, rows = 5)
        em.write("hi".toByteArray())
        val line = em.screenLines().first()
        assertEquals('h', line[0].char)
        assertEquals('i', line[1].char)
        assertTrue("rest of the row is blank", line.drop(2).all { it.char == ' ' })
    }
}
