package dev.ryan.opencode.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshProfileTest {

    @Test
    fun `address omits the default port`() {
        assertEquals("home-server", SshProfile(id = "1", label = "box", host = "home-server").address)
    }

    @Test
    fun `address shows a non-default port`() {
        val p = SshProfile(id = "1", label = "box", host = "10.0.0.5", port = 2222)
        assertEquals("10.0.0.5:2222", p.address)
    }

    @Test
    fun `a profile needs both host and user`() {
        assertFalse(SshProfile(id = "1", label = "x", host = "", user = "ryan").usable)
        assertFalse(SshProfile(id = "1", label = "x", host = "h", user = "").usable)
        assertTrue(SshProfile(id = "1", label = "x", host = "h", user = "ryan").usable)
    }

    /**
     * The profile is serialised into ordinary preferences, so it must never hold a
     * credential. The private key lives in the keystore and is fetched at connect
     * time; nothing else may be added here without re-examining that.
     */
    @Test
    fun `profile carries no secret material`() {
        val fields = SshProfile::class.java.declaredFields.map { it.name }.toSet()
        val suspicious = fields.filter {
            it.contains("key", ignoreCase = true) ||
                it.contains("password", ignoreCase = true) ||
                it.contains("secret", ignoreCase = true) ||
                it.contains("token", ignoreCase = true)
        }
        // `pinHostKey` matches on the substring "key" but is a boolean toggle, not
        // key material. Everything else matching those substrings would be a leak.
        assertEquals(setOf("pinHostKey"), suspicious.toSet())
    }

    @Test
    fun `round trips through json`() {
        val original = SshProfile(
            id = "abc",
            label = "Home-Server",
            host = "100.102.124.47",
            port = 2222,
            user = "ryan",
            tmuxSession = "codex",
            launchCommand = "opencode",
            knownHosts = mapOf("100.102.124.47" to "ecdsa-sha2-nistp256 AAAA"),
        )
        val json = kotlinx.serialization.json.Json.encodeToString(SshProfile.serializer(), original)
        val back = kotlinx.serialization.json.Json.decodeFromString(SshProfile.serializer(), json)
        assertEquals(original, back)
    }

    @Test
    fun `route labels distinguish the two transports`() {
        val opencode = TerminalRoute.Opencode("100.102.124.47:4096")
        assertEquals("opencode", opencode.label)
        val ssh = TerminalRoute.Ssh(SshProfile(id = "1", label = "Home-Server", host = "h"))
        assertEquals("ssh · Home-Server", ssh.label)
    }
}