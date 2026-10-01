package dev.ryan.opencode.core.ssh

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks on the `authorized_keys` line.
 *
 * This exists because an earlier hand-rolled encoder produced a line that looked
 * plausible and that sshd rejected outright: it omitted the curve-name string and
 * the `0x04` point prefix required by RFC 5656. Nothing fails at compile time and
 * nothing fails loudly at runtime — the user just sees an auth error. Asserting
 * the byte layout is the cheapest way to stop that regressing.
 */
class HostKeyStoreTest {

    private fun freshEc(): java.security.interfaces.ECPublicKey {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair().public as java.security.interfaces.ECPublicKey
    }

    @Test
    fun `type name is the openssh ecdsa name`() {
        assertEquals("ecdsa-sha2-nistp256", HostKeyStore.typeName(freshEc()))
    }

    @Test
    fun `encoded line is type plus base64 and nothing else`() {
        val line = HostKeyStore.encode(freshEc())
        assertNotNull(line)
        val parts = line!!.split(" ")
        assertEquals(2, parts.size)
        assertEquals("ecdsa-sha2-nistp256", parts[0])
        java.util.Base64.getDecoder().decode(parts[1]) // throws if malformed
    }

    @Test
    fun `wire blob is three ssh strings with an uncompressed point`() {
        val fields = readSshFields(HostKeyStore.wireBlob(freshEc()))
        // Exactly three. A fourth means the type name got written twice, which is
        // the second bug this file guards against.
        assertEquals(3, fields.size)
        assertEquals("ecdsa-sha2-nistp256", String(fields[0]))
        // The curve name is a separate string. Dropping it is the first bug.
        assertEquals("nistp256", String(fields[1]))
        // Uncompressed point: 0x04 || X(32) || Y(32).
        assertEquals(65, fields[2].size)
        assertEquals(0x04.toByte(), fields[2][0])
    }

    @Test
    fun `point is exactly two 256-bit coordinates`() {
        val fields = readSshFields(HostKeyStore.wireBlob(freshEc()))
        val point = fields[2]
        // Each coordinate is 32 bytes and must be zero-padded to full width.
        assertEquals(32, point.copyOfRange(1, 33).size)
        assertEquals(32, point.copyOfRange(33, 65).size)
    }

    @Test
    fun `fingerprint is stable for the same key and differs across keys`() {
        val a = freshEc()
        val b = freshEc()
        assertEquals(HostKeyStore.fingerprint(a), HostKeyStore.fingerprint(a))
        assertTrue(HostKeyStore.fingerprint(a) != HostKeyStore.fingerprint(b))
        assertTrue(HostKeyStore.fingerprint(a).startsWith("SHA256:"))
    }

    /**
     * A pinned key must match itself and reject anything else. This is the
     * property that catches a reinstalled server.
     */
    @Test
    fun `pinned key matches on reconnect and refuses a different key`() {
        val original = freshEc()
        val other = freshEc()
        val encoded = HostKeyStore.encode(original)!!
        val known = mapOf("host.example" to encoded)

        var decision: HostKeyStore.Decision? = null
        val store = HostKeyStore(known, pin = true) { decision = it }
        assertTrue(store.verify("host.example", 22, original))
        assertEquals(HostKeyStore.Decision.MATCHES, decision)

        assertTrue(!store.verify("host.example", 22, other))
        assertEquals(HostKeyStore.Decision.CHANGED, decision)
    }

    @Test
    fun `first contact records the key and reports it`() {
        var decision: HostKeyStore.Decision? = null
        val store = HostKeyStore(emptyMap(), pin = true) { decision = it }
        val key = freshEc()
        assertTrue(store.verify("new.example", 22, key))
        assertEquals(HostKeyStore.Decision.PINNED_NEW, decision)
        assertEquals(HostKeyStore.encode(key), store.updatedKnown["new.example"])
    }

    @Test
    fun `known algorithms are reported from the pinned entry`() {
        val key = freshEc()
        val store = HostKeyStore(mapOf("h" to HostKeyStore.encode(key)!!), pin = true) {}
        assertEquals(listOf("ecdsa-sha2-nistp256"), store.findExistingAlgorithms("h", 22))
    }

    private fun readSshFields(blob: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i + 4 <= blob.size) {
            val len = ((blob[i].toInt() and 0xFF) shl 24) or
                ((blob[i + 1].toInt() and 0xFF) shl 16) or
                ((blob[i + 2].toInt() and 0xFF) shl 8) or
                (blob[i + 3].toInt() and 0xFF)
            out.add(blob.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        return out
    }

    private fun readSshStrings(blob: ByteArray): List<String> = readSshFields(blob).map { String(it) }
}