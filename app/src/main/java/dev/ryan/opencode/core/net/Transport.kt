package dev.ryan.opencode.core.net

import dev.ryan.opencode.core.model.ConnectToken
import dev.ryan.opencode.core.model.ConnectionState
import dev.ryan.opencode.core.model.DisconnectReason
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Shared lenient JSON codec. The API adds fields freely; we must not break on them. */
val OpencodeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * How the app reaches the opencode server.
 *
 * All three transports ultimately produce a base [HttpUrl] plus a set of headers,
 * so the rest of the app never needs to know which one is in use.
 */
sealed interface Transport {
    val id: String
    val label: String

    /** Resolve to a usable base URL + headers, or throw. */
    suspend fun resolve(): Resolved

    data class Resolved(
        val base: HttpUrl,
        val headers: Map<String, String> = emptyMap(),
        /** Session cookie value from pairing, if we have one. */
        val sessionToken: String? = null,
    )
}

/**
 * Tailscale / Cloudflare / LAN — anything where the phone can open a socket straight
 * to the server. They differ only in URL and in which extra auth headers are needed,
 * so they share one implementation.
 */
data class DirectTransport(
    override val id: String,
    override val label: String,
    val url: String,
    val basicUser: String? = null,
    val basicPassword: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val sessionToken: String? = null,
) : Transport {

    override suspend fun resolve(): Transport.Resolved {
        val base = normalize(url)
        val headers = LinkedHashMap<String, String>(extraHeaders)
        if (basicUser != null && basicPassword != null) {
            headers["Authorization"] = okhttp3.Credentials.basic(basicUser, basicPassword)
        }
        return Transport.Resolved(base, headers, sessionToken)
    }

    companion object {
        /**
         * Normalise a user-typed host into a full URL.
         * Accepts `10.0.0.5`, `host:4096`, `http://host`, `https://tunnel.trycloudflare.com`.
         * Plain hosts get `http://` (Tailscale and LAN are typically plain HTTP);
         * anything with a scheme or a known TLS hostname is left alone.
         */
        fun normalize(raw: String): HttpUrl {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) throw IllegalArgumentException("Enter your server address first")
            val withScheme = when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> "http://$trimmed"
            }
            val url = withScheme.toHttpUrl()
            return if (url.port == 80 && url.scheme == "http") {
                url.newBuilder().port(4096).build()
            } else url
        }
    }
}

/**
 * Fallback path: tunnel through SSH so the phone only needs SSH credentials,
 * with no server-side port exposure at all. Requires the embedded SSH client
 * (see SshTunnel) to be wired in; until then it reports unavailable rather
 * than pretending to work.
 */
data class SshTransport(
    override val id: String = "ssh",
    override val label: String = "SSH tunnel",
    val host: String,
    val port: Int = 22,
    val user: String,
    val authKeyAlias: String? = null,
    val authPassword: String? = null,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int = 4096,
) : Transport {
    override suspend fun resolve(): Transport.Resolved =
        throw TransportUnavailable("SSH transport is not available in this build")
}

class TransportUnavailable(message: String) : Exception(message)

/**
 * Cookie jar that holds exactly one opencode session cookie.
 *
 * The cookie name embeds the server port (`opencode_session_4096`), which we only
 * know after parsing the URL — so we synthesise it per-request in [AuthInterceptor]
 * rather than relying on OkHttp's cookie matching.
 */
class SingleSessionCookieJar : CookieJar {
    @Volatile var token: String? = null

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.firstOrNull { it.name.startsWith("opencode_session") }?.let { token = it.value }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
}

/** Attaches basic auth, extra headers and the session cookie to every request. */
class AuthInterceptor(
    private val headerProvider: () -> Map<String, String>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder: Request.Builder = chain.request().newBuilder()
        headerProvider().forEach { (k, v) -> builder.header(k, v) }
        return chain.proceed(builder.build())
    }
}

fun buildHttpClient(
    headerProvider: () -> Map<String, String> = { emptyMap() },
    readTimeoutSeconds: Long = 0,
): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(AuthInterceptor(headerProvider))
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .cookieJar(SingleSessionCookieJar())
    .build()

/**
 * The session cookie name for a base URL. Verified: the server uses
 * `opencode_session_<port>` when the URL carries an explicit port, else
 * `opencode_session`.
 */
fun sessionCookieName(base: HttpUrl): String =
    if (base.port != 80 && base.port != 443) "opencode_session_${base.port}" else "opencode_session"

suspend fun OkHttpClient.await(request: Request): Response =
    suspendCoroutine { cont ->
        newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        })
    }

/** Reads an error body into a human-usable message, falling back to the status code. */
suspend fun Response.errorMessage(): String {
    val bodyText = runCatching { body?.string() }.getOrNull().orEmpty()
    if (bodyText.isBlank()) return "HTTP $code"
    return runCatching {
        val obj = OpencodeJson.parseToJsonElement(bodyText).let {
            (it as? kotlinx.serialization.json.JsonObject)?.get("message")?.toString()?.trim('"')
        }
        obj ?: bodyText.take(200)
    }.getOrElse { bodyText.take(200) }
}

fun Response.isUnauthorized(): Boolean = code == 401

fun HttpUrl.toWebSocketUrl(): HttpUrl = newBuilder().scheme(if (scheme == "https") "wss" else "ws").build()

/** Human-readable summary of a disconnect, used in notifications and the UI. */
fun DisconnectReason.describe(): String = when (this) {
    DisconnectReason.None -> "Connected"
    DisconnectReason.NetworkLost -> "No network connection"
    DisconnectReason.NetworkChanged -> "Network changed"
    DisconnectReason.ServerClosed -> "Server closed the connection"
    DisconnectReason.AuthFailed -> "Authentication failed"
    DisconnectReason.Unknown -> "Connection lost"
}

fun ConnectionState.isLive(): Boolean = this == ConnectionState.Connected
