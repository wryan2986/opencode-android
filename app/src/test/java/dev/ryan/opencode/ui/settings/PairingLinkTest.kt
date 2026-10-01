package dev.ryan.opencode.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scanned QR is `http://HOST:PORT/auth/connect/CODE`, and both halves matter:
 * the code without the right host pairs against nothing, and the host without a
 * code is just an address.
 */
class PairingLinkTest {

    @Test
    fun `splits host and code`() {
        assertEquals(
            "100.102.124.47:4096" to "abc-DEF_123",
            parsePairingLink("http://100.102.124.47:4096/auth/connect/abc-DEF_123"),
        )
    }

    @Test
    fun `works without an explicit port`() {
        assertEquals(
            "host.example:80" to "abc123",
            parsePairingLink("http://host.example/auth/connect/abc123"),
        )
    }

    @Test
    fun `accepts https`() {
        assertEquals(
            "tunnel.trycloudflare.com:443" to "xyz",
            parsePairingLink("https://tunnel.trycloudflare.com/auth/connect/xyz"),
        )
    }

    /**
     * A bare code is what the installer also prints as text, so it has to survive
     * as input — the caller falls back to treating the scan result as one.
     */
    @Test
    fun `rejects a bare code rather than mis-parsing it`() {
        assertNull(parsePairingLink("abc-DEF_123"))
    }

    @Test
    fun `rejects the wrong path`() {
        assertNull(parsePairingLink("http://host:4096/api/pair"))
        assertNull(parsePairingLink("http://host:4096/"))
        assertNull(parsePairingLink("http://host:4096/auth/connect/"))
    }

    @Test
    fun `rejects nonsense without throwing`() {
        assertNull(parsePairingLink(""))
        assertNull(parsePairingLink("not a url"))
        assertNull(parsePairingLink("http://"))
        assertNull(parsePairingLink("ftp://host/auth/connect/code"))
    }

    /**
     * Codes are case-sensitive and may contain `-` and `_`. If parsing uppercased
     * or trimmed them, redemption would fail with an auth error that looks like an
     * expired code.
     */
    @Test
    fun `preserves the code exactly`() {
        val parsed = parsePairingLink("http://h:4096/auth/connect/-D_yIRMjJT9Qshy-59Up9g")
        assertEquals("h:4096" to "-D_yIRMjJT9Qshy-59Up9g", parsed)
    }
}