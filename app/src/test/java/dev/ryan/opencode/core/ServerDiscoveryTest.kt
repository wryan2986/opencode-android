package dev.ryan.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDiscoveryTest {

    // ---- candidate ordering ----

    /**
     * The known host leads because it is nearly always the right answer, and a
     * wasted probe on a dead candidate is a wasted connect timeout.
     */
    @Test
    fun `known host comes first`() {
        val c = ServerDiscovery({ throw AssertionError("not needed") }, { "" })
            .candidates("100.102.124.47:4096")
        assertEquals("http://100.102.124.47:4096", c.first())
    }

    @Test
    fun `tailnet dns name is always a candidate`() {
        val c = ServerDiscovery({ throw AssertionError("not needed") }, { "" })
            .candidates("100.102.124.47:4096")
        assertTrue(c.contains("http://home-server.tail0f4451.ts.net:4096"))
    }

    @Test
    fun `candidates are de-duplicated`() {
        val c = ServerDiscovery({ throw AssertionError("not needed") }, { "" })
            .candidates("http://100.102.124.47:4096", listOf("http://100.102.124.47:4096", ""))
        assertEquals(1, c.count { it.contains("100.102.124.47") })
        assertFalse(c.any { it.isBlank() })
    }

    @Test
    fun `an unpaired install still gets candidates rather than an empty list`() {
        val c = ServerDiscovery({ throw AssertionError("not needed") }, { "" }).candidates("")
        assertTrue(c.isNotEmpty())
        assertTrue(c.all { it.startsWith("http") })
    }

    // ---- port defaulting ----

    @Test
    fun `bare address gets opencode's port`() {
        assertEquals(
            "http://100.102.124.47:4096",
            ServerDiscovery.withDefaultPort("100.102.124.47"),
        )
    }

    @Test
    fun `an explicit port is left alone`() {
        assertEquals(
            "http://100.102.124.47:5000",
            ServerDiscovery.withDefaultPort("100.102.124.47:5000"),
        )
    }

    /**
     * A tunnel or any https endpoint is reached on 443. Appending 4096 there
     * would break the Cloudflare transport outright.
     */
    @Test
    fun `https is never given opencode's port`() {
        assertEquals(
            "https://example.trycloudflare.com",
            ServerDiscovery.withDefaultPort("https://example.trycloudflare.com"),
        )
        assertEquals(
            "https://host:8443",
            ServerDiscovery.withDefaultPort("https://host:8443"),
        )
    }

    @Test
    fun `trailing slashes are normalised away`() {
        assertEquals(
            "http://100.102.124.47:4096",
            ServerDiscovery.withDefaultPort("http://100.102.124.47:4096/"),
        )
    }

    @Test
    fun `blank input does not explode`() {
        assertEquals("", ServerDiscovery.withDefaultPort(""))
        assertEquals("", ServerDiscovery.withDefaultPort("   "))
    }

    @Test
    fun `hostnames are accepted not just ip literals`() {
        assertEquals(
            "http://home-server.tail0f4451.ts.net:4096",
            ServerDiscovery.withDefaultPort("home-server.tail0f4451.ts.net"),
        )
    }
}