package dev.ryan.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxRepositoryTest {

    // ---- name validation ----

    @Test
    fun `accepts ordinary session names`() {
        assertTrue(isLegalTmuxName("work"))
        assertTrue(isLegalTmuxName("api-2"))
        assertTrue(isLegalTmuxName("build_3"))
        assertTrue(isLegalTmuxName("A"))
    }

    @Test
    fun `rejects empty and oversized names`() {
        assertFalse(isLegalTmuxName(""))
        assertTrue(isLegalTmuxName("a".repeat(64)))
        assertFalse(isLegalTmuxName("a".repeat(65)))
    }

    /**
     * tmux itself forbids `.` and `:` in session names because they are target
     * syntax (`session:0.1`). Refusing them here keeps the app from trying to
     * attach to something it cannot name.
     */
    @Test
    fun `rejects tmux target punctuation`() {
        assertFalse(isLegalTmuxName("work.main"))
        assertFalse(isLegalTmuxName("work:0"))
        assertFalse(isLegalTmuxName(":"))
        assertFalse(isLegalTmuxName("work:0.1"))
    }

    /**
     * Session names reach a `bash -c` string. Anything with a shell metacharacter
     * must be refused outright — no escaping, no quoting, just a deny.
     */
    @Test
    fun `rejects shell metacharacters`() {
        val nasty = listOf(
            "work; rm -rf ~",
            "work && curl evil.test",
            "\$(id)",
            "`id`",
            "work|named",
            "work name",
            "work'quote",
            "work\\nid",
            "work\tid",
            "../../etc",
            "work\nid",
        )
        nasty.forEach { assertFalse("should reject: $it", isLegalTmuxName(it)) }
    }

    @Test
    fun `rejects leading dash so a name cannot become a flag`() {
        assertFalse(isLegalTmuxName("-t"))
        assertFalse(isLegalTmuxName("--version"))
    }

    // ---- list parsing ----

    @Test
    fun `parses a well-formed row`() {
        val s = parseTmuxLine("work|3|1|1759000000")
        assertEquals("work", s?.name)
        assertEquals(3, s?.windows)
        assertEquals(1, s?.attached)
        assertEquals(1759000000L, s?.created)
        assertTrue(s?.inUse == true)
    }

    @Test
    fun `treats zero attached clients as not in use`() {
        assertFalse(parseTmuxLine("idle|1|0|1759000000")!!.inUse)
        assertTrue(parseTmuxLine("busy|1|2|1759000000")!!.inUse)
    }

    @Test
    fun `returns null for malformed rows instead of throwing`() {
        // "no server running on /tmp/tmux-1000/default" is what tmux prints when
        // nothing is up; it must never become a session.
        assertNull(parseTmuxLine("no server running on /tmp/tmux-1000/default"))
        assertNull(parseTmuxLine(""))
        assertNull(parseTmuxLine("only|two"))
        assertNull(parseTmuxLine("|1|0|1759000000"))
    }

    @Test
    fun `non-numeric counters degrade to zero rather than dropping the session`() {
        val s = parseTmuxLine("work|x|y|z")
        assertEquals("work", s?.name)
        assertEquals(0, s?.windows)
        assertEquals(0, s?.attached)
        assertEquals(0L, s?.created)
    }

    @Test
    fun `keeps sessions whose name contains a hyphen`() {
        assertEquals("api-2", parseTmuxLine("api-2|1|0|1")?.name)
    }
}