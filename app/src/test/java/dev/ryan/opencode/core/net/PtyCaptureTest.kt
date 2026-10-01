package dev.ryan.opencode.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class PtyCaptureTest {

    /**
     * The PTY WebSocket multiplexes terminal bytes with control frames, and a
     * control frame arrives *first*. Left in place it corrupts the first parsed
     * row — this is the `{"cursor":0}probe_alpha` case, which parses cleanly as a
     * session and is therefore worse than an outright failure.
     */
    @Test
    fun `strips a leading cursor control frame`() {
        val raw = "\u0000{\"cursor\":0}alpha|1|0|1759000000"
        assertEquals("alpha|1|0|1759000000", PtyCapture.stripControl(raw))
    }

    @Test
    fun `strips control frames anywhere in the stream`() {
        val raw = "alpha|1|0|1\n\u0000{\"cursor\":4274}\nbeta|2|1|2"
        assertEquals("alpha|1|0|1\n\nbeta|2|1|2", PtyCapture.stripControl(raw))
    }

    @Test
    fun `leaves plain output untouched`() {
        val raw = "alpha|1|0|1759000000\nbeta|2|1|1759000001"
        assertEquals(raw, PtyCapture.stripControl(raw))
    }

    @Test
    fun `does not eat brace characters that are not control frames`() {
        // A NUL is required, so ordinary braces survive.
        assertEquals("{\"a\":1}", PtyCapture.stripControl("{\"a\":1}"))
    }

    @Test
    fun `strips ansi colour codes`() {
        val raw = "\u001B[1;32malpha\u001B[0m|1|0|1"
        assertEquals("alpha|1|0|1", PtyCapture.stripAnsi(raw))
    }

    @Test
    fun `strips osc sequences too`() {
        // The sequence is removed; the text around it is not.
        assertEquals("beforeafter", PtyCapture.stripAnsi("before\u001B]0;title\u0007after"))
    }
}