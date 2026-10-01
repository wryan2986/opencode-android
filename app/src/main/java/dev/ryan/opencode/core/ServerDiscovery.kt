package dev.ryan.opencode.core

import dev.ryan.opencode.core.net.DirectTransport
import dev.ryan.opencode.core.net.OpencodeJson
import dev.ryan.opencode.core.net.await
import dev.ryan.opencode.core.net.buildHttpClient
import dev.ryan.opencode.core.net.sessionCookieName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A host we probed, and whether the token we already hold works there.
 *
 * [reachable] means the host answered *and* accepted the token. A host that
 * answers but rejects the token is not reported as reachable: it is a different
 * opencode, and offering it would be worse than reporting nothing.
 */
data class DiscoveredServer(
    val host: String,
    val base: HttpUrl,
    val reachable: Boolean,
    val version: String? = null,
    val cause: String? = null,
)

/**
 * Finds the server again, so a paired phone never needs a second pairing code.
 *
 * ## Why this is not mDNS
 *
 * mDNS (`_opencode._tcp`) is the obvious answer and the wrong one here. It is a
 * link-local protocol: it resolves on a LAN and nowhere else. This server is
 * reached over Tailscale, on a CGNAT address in `100.x` that mDNS will never see.
 * The original plan to race mDNS across the tailnet assumed that was possible.
 * It is not.
 *
 * What does work is Tailscale MagicDNS, which resolves
 * `home-server.tail0f4451.ts.net` to `100.102.124.47` from any tailnet member and
 * is tailnet-only by construction. That is a better candidate than a bare IP: it
 * survives an IP change, and it works on cellular away from the LAN.
 *
 * ## Why a token is a sufficient trust anchor
 *
 * Trust-on-first-use cannot verify a server *before* the first trust decision,
 * because `/api/info` answers `401` when unauthenticated — there is nothing to
 * fingerprint. So this never tries to identify a server in the abstract. It asks
 * a narrower question: **does the token we already hold work here?**
 *
 * That inverts the problem. Discovery does not decide "is this opencode?" — a
 * wrong host simply fails to accept a token minted by a different server and is
 * dropped. A 401 is a wrong answer, not a slow one. Verified: this token is
 * accepted by `100.102.124.47:4096` and rejected with 401 by the unrelated
 * opencode on `127.0.0.1:49374`, a genuinely separate instance.
 *
 * This is what makes the pairing code one-time. It is not that the code became
 * optional — it is that the credential it yields is now *findable*.
 */
class ServerDiscovery(
    private val clientProvider: () -> OkHttpClient,
    private val directoryProvider: () -> String,
) {
    /**
     * Hosts to try, best first and de-duplicated.
     *
     * Previously-seen hosts lead because they are the ones we have a token for in
     * the common case; the tailnet DNS name follows because it survives an IP
     * change; the bare hostname is a same-LAN fallback.
     */
    fun candidates(knownHost: String, extraHosts: List<String> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        if (knownHost.isNotBlank()) out.add(knownHost)
        extraHosts.forEach { if (it.isNotBlank()) out.add(it) }
        out.addAll(TAILSCALE_DNS_NAMES)
        return out.map(::withDefaultPort).filter { it.isNotBlank() }
    }

    /**
     * Merge in whatever mDNS turned up, without losing the ordering above.
     *
     * mDNS hosts go last: they are the least certain, and the known host plus the
     * tailnet name have already answered on every previous run.
     */
    fun candidatesWith(
        knownHost: String,
        mdnsHosts: List<String>,
        extraHosts: List<String> = emptyList(),
    ): List<String> {
        val base = candidates(knownHost, extraHosts)
        val seen = base.map { it.substringAfter("//").substringBefore(':').lowercase() }.toSet()
        val fresh = mdnsHosts
            .filter { it.isNotBlank() }
            .filterNot { seen.contains(it.trimEnd('.').lowercase()) }
        return base + fresh.map(::withDefaultPort)
    }

    /**
     * Probe every candidate with the stored token, all at once.
     *
     * They race rather than queue: a dead host would otherwise cost a full
     * connect timeout before the live one is tried at all, which is exactly the
     * "it's just spinning" feeling this exists to remove.
     */
    suspend fun discover(
        knownHost: String,
        token: String,
        mdnsHosts: List<String> = emptyList(),
        extraHosts: List<String> = emptyList(),
    ): List<DiscoveredServer> = coroutineScope {
        candidatesWith(knownHost, mdnsHosts, extraHosts)
            .map { host -> async(Dispatchers.IO) { probe(host, token) } }
            .awaitAll()
    }

    /** Probe one candidate with the stored token. Never throws. */
    suspend fun probe(host: String, token: String): DiscoveredServer = withContext(Dispatchers.IO) {
        val base = try {
            DirectTransport.normalize(host)
        } catch (e: Exception) {
            return@withContext DiscoveredServer(
                host, HttpUrl.Builder().scheme("http").host("0.0.0.0").build(),
                false, cause = "not a usable address",
            )
        }

        // A short clock on purpose: this runs for every candidate on every
        // reconnect, and one that has not answered in a couple of seconds is not
        // coming back.
        val version = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                val headers = mapOf("Cookie" to "${sessionCookieName(base)}=$token")
                val client = buildHttpClient(headerProvider = { headers }, readTimeoutSeconds = 3)
                val url = base.newBuilder().addPathSegments("api/info").build()
                client.await(Request.Builder().url(url).get().build()).use { res ->
                    if (!res.isSuccessful) null else versionOf(res.body?.string().orEmpty())
                }
            }.getOrNull()
        }

        if (version == null) {
            DiscoveredServer(host, base, false, cause = "no answer, or token rejected")
        } else {
            DiscoveredServer(host, base, true, version = version)
        }
    }

    /** Pull `version` out of the `/api/info` body without decoding the whole model. */
    private fun versionOf(body: String): String? = runCatching {
        OpencodeJson.decodeFromString<JsonObject>(body)["version"]?.jsonPrimitive?.content
    }.getOrNull()

    companion object {
        internal const val PROBE_TIMEOUT_MS = 2_500L
        internal const val DEFAULT_PORT = 4096

        /** Tailscale MagicDNS name: tailnet-only, and stable across IP changes. */
        internal val TAILSCALE_DNS_NAMES = listOf("home-server.tail0f4451.ts.net")

        /** Give a bare address opencode's port, which the systemd unit binds. */
        internal fun withDefaultPort(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            val withScheme = when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> "http://$trimmed"
            }
            val url = withScheme.toHttpUrlOrNull() ?: return withScheme
            return if (url.port == 80 && url.scheme == "http") {
                url.newBuilder().port(DEFAULT_PORT).build().toString().trimEnd('/')
            } else {
                url.toString().trimEnd('/')
            }
        }
    }
}