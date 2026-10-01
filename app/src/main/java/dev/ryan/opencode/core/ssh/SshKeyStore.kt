package dev.ryan.opencode.core.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey

/**
 * Holds the SSH private key in the Android keystore and hands out the public half
 * in OpenSSH form.
 *
 * The private key is generated as a non-exportable EC key inside the keystore, so
 * it exists only on this device — there is no file to copy off it, and no
 * `EncryptedSharedPreferences` blob containing a PEM that could be exfiltrated.
 * Backup is excluded for the same reason: restoring a keystore entry onto another
 * device does not work, and a restored entry that *did* work would be worse than
 * no key at all.
 *
 * Revocation is the other half of the story and is deliberately easy: delete the
 * line in `~/.ssh/authorized_keys` and this device stops working.
 */
class SshKeyStore(context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** `ecdsa-sha2-nistp256` — the algorithm this key is registered for. */
    private val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
        .setAlgorithmParameterSpec(
            java.security.spec.ECGenParameterSpec("secp256r1"),
        )
        .setDigests(KeyProperties.DIGEST_SHA256)
        .build()

    /** Create the key if absent. Returns the public key in OpenSSH `authorized_keys` form. */
    fun ensureKey(): String {
        if (!keyStore.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }
        return publicKeyOpenSsh()
    }

    fun hasKey(): Boolean = keyStore.containsAlias(ALIAS)

    /**
     * The private key for SSH user auth, or null if no key has been generated.
     *
     * sshj signs by calling `getEncoded()` on the key and running the math itself.
     * A keystore key returns null from `getEncoded()` by design, because it must
     * not be exportable — so the raw scalar is read back with `getParams()` and
     * rewrapped in a plain `ECPrivateKey` that can encode.
     *
     * The trade-off this makes: the scalar does briefly exist in app memory as a
     * byte array, so the key is protected by the keystore at rest and by app
     * sandboxing in use, rather than by the keystore being the only copy ever.
     * That is the same posture as Termius-style clients, and it is why backup is
     * excluded and the key is revocable by deleting one authorized_keys line.
     */
    fun keyPair(): KeyPair? {
        val priv = privateKey() ?: return null
        val pub = keyStore.getCertificate(ALIAS)?.publicKey ?: return null
        return KeyPair(pub, priv)
    }

    private fun privateKey(): PrivateKey? {
        if (!keyStore.containsAlias(ALIAS)) return null
        val key = keyStore.getKey(ALIAS, null) as? ECPrivateKey ?: return null
        val spec = java.security.spec.ECPrivateKeySpec(key.s, ecParams())
        val factory = java.security.KeyFactory.getInstance("EC")
        return factory.generatePrivate(spec)
    }

    /** Delete the key. The device can no longer authenticate anywhere. */
    fun delete() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    /**
     * The public key as a single `authorized_keys` line.
     *
     * Format is `ssh-ed25519`-style EC: `ecdsa-sha2-nistp256 AAAA… comment`. sshd
     * accepts this without any `ssh-keygen` round trip.
     */
    fun publicKeyOpenSsh(): String {
        val key = keyStore.getCertificate(ALIAS)?.publicKey as? java.security.interfaces.ECPublicKey
            ?: error("no key material for $ALIAS")
        val encoded = sshWireFormat(key)
        val b64 = base64(encoded)
        return "${HostKeyStore.typeName(key)} $b64 opencode-android"
    }

    /** Fingerprint so the user can verify the key on the server before trusting it. */
    fun fingerprint(): String {
        val key = keyStore.getCertificate(ALIAS)?.publicKey as? java.security.interfaces.ECPublicKey ?: return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("ssh-jwt".toByteArray() + sshWireFormat(key))
        return "SHA256:" + base64(digest).trimEnd('=')
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "opencode_ssh_ec"

        /** secp256r1 parameters, materialised so a plain ECPrivateKeySpec can be built. */
        private fun ecParams(): java.security.spec.ECParameterSpec {
            val ap = java.security.AlgorithmParameters.getInstance("EC")
            ap.init(java.security.spec.ECGenParameterSpec("secp256r1"))
            return ap.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        }

        /**
         * The SSH wire blob for this key, in the exact form `authorized_keys` and
         * `known_hosts` expect.
         *
         * RFC 5656 lays an EC public key out as three SSH strings:
         *
         *     string  "ecdsa-sha2-nistp256"
         *     string  "nistp256"
         *     string  Q
         *
         * where `Q` is the *uncompressed* point — a `0x04` prefix followed by the
         * two 32-byte coordinates. An earlier version of this file hand-rolled the
         * format and omitted both the curve-name string and the `0x04`. The
         * resulting line looks plausible and sshd rejects it outright, so every
         * pairing would have failed with a bare auth error.
         *
         * Delegating to [HostKeyStore.wireBlob] keeps one implementation, and the
         * test suite pins that output against sshj's own encoder byte for byte.
         */
        private fun sshWireFormat(publicKey: java.security.interfaces.ECPublicKey): ByteArray =
            HostKeyStore.wireBlob(publicKey)

        fun base64(data: ByteArray): String =
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    }
}
