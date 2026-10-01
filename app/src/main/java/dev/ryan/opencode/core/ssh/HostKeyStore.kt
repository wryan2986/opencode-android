package dev.ryan.opencode.core.ssh

import java.security.MessageDigest
import java.security.PublicKey
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * Decides whether to trust a host's SSH key, and remembers the decision.
 *
 * ## The honest part
 *
 * The first connection to a host is unauthenticated. An SSH host key has to be
 * accepted *before* any credential goes over the wire, so on first contact there
 * is nothing to compare it against. That is a property of SSH, not of this app.
 * On a tailnet the traffic is already encrypted by WireGuard underneath, so the
 * exposure is small — but it is not zero, and it should not be described as if it
 * were.
 *
 * What this class does provide is the property that matters afterwards: once a key
 * is pinned, a *changed* key is refused. That catches a reinstalled server and a
 * man-in-the-middle, which is where the risk actually concentrates.
 *
 * ## Why the state is a plain map
 *
 * sshj's `OpenSSHKnownHosts` wants a `File` and does its own I/O. Holding
 * `Map<hostname, "type base64">` instead keeps the decision a pure function that
 * can be unit tested without a server, and lets the profile carry it directly.
 */
class HostKeyStore(
    private val known: Map<String, String>,
    private val pin: Boolean,
    private val onDecision: (Decision) -> Unit = {},
) : HostKeyVerifier {

    enum class Decision { PINNED_NEW, MATCHES, CHANGED, REJECTED }

    /** What to persist after this verify, if anything changed. */
    var updatedKnown: Map<String, String> = known
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val entry = known[hostname] ?: known["$hostname:$port"]

        if (entry == null) {
            val encoded = encode(key)
            if (encoded == null) {
                onDecision(Decision.REJECTED)
                return false
            }
            onDecision(Decision.PINNED_NEW)
            updatedKnown = known + (hostname to encoded)
            // When pinning is off we still record it, so the first connect is not
            // a special case, but we do not claim the user verified anything.
            return true
        }

        val parts = entry.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) {
            onDecision(Decision.REJECTED)
            return false
        }

        // Compare decoded bytes, not text: base64 padding and sshj-vs-sshd
        // formatting differences must not read as a changed key.
        val stored = runCatching { java.util.Base64.getDecoder().decode(parts[1]) }.getOrNull()
        val actual = runCatching { wireBlob(key) }.getOrNull()
        val same = stored != null && actual != null && stored.contentEquals(actual)

        if (same) {
            onDecision(Decision.MATCHES)
            return true
        }
        onDecision(Decision.CHANGED)
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        (known[hostname] ?: known["$hostname:$port"])
            ?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?.let(::listOf).orEmpty()

    companion object {
        /** `KeyType.toString()` is already the OpenSSH name, e.g. `ecdsa-sha2-nistp256`. */
        fun typeName(key: PublicKey): String = KeyType.fromKey(key).toString()

        /**
         * The SSH wire encoding of a public key: a length-prefixed type string
         * followed by the type-specific key body. This is what OpenSSH stores in
         * `authorized_keys` and `known_hosts` after the type name, so it is the
         * right thing to compare and to persist.
         */
        fun wireBlob(key: PublicKey): ByteArray {
            val type = KeyType.fromKey(key)
            val buffer = Buffer.PlainBuffer()
            type.putPubKeyIntoBuffer(key, buffer)
            // No manual type prefix: putPubKeyIntoBuffer already writes it.
            return buffer.getCompactData()
        }

        /** One `authorized_keys` line, for pasting onto a server. */
        fun encode(key: PublicKey): String? = runCatching {
            "${typeName(key)} ${b64(wireBlob(key))}"
        }.getOrNull()

        fun b64(data: ByteArray): String = java.util.Base64.getEncoder().encodeToString(data)

        /** Short fingerprint for the UI: `SHA256:…`, truncated. */
        fun fingerprint(key: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(wireBlob(key))
            return "SHA256:" + b64(digest).trimEnd('=').take(20)
        }
    }
}