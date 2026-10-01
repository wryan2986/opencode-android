package dev.ryan.opencode.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dev.ryan.opencode.core.model.ConnectionState
import dev.ryan.opencode.core.model.DisconnectReason
import dev.ryan.opencode.core.net.BasicAuthStore
import dev.ryan.opencode.core.net.DirectTransport
import dev.ryan.opencode.core.net.EventStream
import dev.ryan.opencode.core.net.OpencodeClient
import dev.ryan.opencode.core.net.SessionCookieStore
import dev.ryan.opencode.core.net.StreamSignal
import dev.ryan.opencode.core.net.Transport
import dev.ryan.opencode.core.net.PtySocket
import dev.ryan.opencode.core.net.buildHttpClient
import dev.ryan.opencode.core.store.AppSettings
import dev.ryan.opencode.core.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single live connection to the server and keeps it alive.
 *
 * The design goal is that the user never has to think about connectivity:
 *
 *  - a [ConnectivityManager.NetworkCallback] fires on WiFi <-> cellular switches and
 *    triggers an *immediate* reconnect instead of waiting for a TCP timeout;
 *  - the event stream retries with exponential backoff on its own;
 *  - the PTY re-attaches from its tracked byte offset, so nothing is lost or
 *    duplicated when the socket dies mid-stream;
 *  - all session state lives on the server, so a reconnect only needs a resync of
 *    the message list rather than any user-visible recovery step.
 */
class ConnectionManager(
    private val context: Context,
    val settingsStore: SettingsStore,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _reason = MutableStateFlow(DisconnectReason.None)
    val reason: StateFlow<DisconnectReason> = _reason.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _events = MutableSharedFlow<StreamSignal>(replay = 0, extraBufferCapacity = 512)
    val events: SharedFlow<StreamSignal> = _events.asSharedFlow()

    @Volatile var base: HttpUrl? = null
        private set

    @Volatile var transport: Transport? = null
        private set

    private val headers = HashMap<String, String>()

    val httpClient: OkHttpClient by lazy {
        buildHttpClient(headerProvider = { HashMap(headers) }, readTimeoutSeconds = 0)
    }

    val api: OpencodeClient by lazy {
        OpencodeClient(object : OpencodeClient.OkHttpClientProvider {
            override val client: OkHttpClient get() = httpClient
            override val base: HttpUrl
                get() = this@ConnectionManager.base
                    ?: error("ConnectionManager not configured")
        })
    }

    val eventStream: EventStream by lazy {
        EventStream({ httpClient }, { base ?: error("not configured") }, appScope)
    }

    val pty: PtySocket by lazy {
        PtySocket(
            clientProvider = { httpClient },
            baseProvider = { base ?: error("not configured") },
            api = api,
            directoryProvider = { _settings.value.directory },
        )
    }

    val discovery: ServerDiscovery by lazy {
        ServerDiscovery(
            clientProvider = { httpClient },
            directoryProvider = { _settings.value.directory },
        )
    }

    private val _discovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())

    /** Everything the last discovery run probed, reachable or not. */
    val discovered: StateFlow<List<DiscoveredServer>> = _discovered.asStateFlow()

    private val _discoveryBusy = MutableStateFlow(false)
    val discoveryBusy: StateFlow<Boolean> = _discoveryBusy.asStateFlow()

    val tmux: TmuxRepository by lazy {
        TmuxRepository(
            api = api,
            clientProvider = { httpClient },
            baseProvider = { base ?: error("not configured") },
            directoryProvider = { _settings.value.directory },
        )
    }

    private val started = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Load persisted settings and, if configured, bring the connection up. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        registerNetworkCallback()
        // Long-lived observers live here, never in a UI scope — a composable
        // leaving the composition must not be able to kill the connection.
        appScope.launch {
            pty.signals.collect { sig ->
                if (sig is dev.ryan.opencode.core.net.PtySignal.Dropped) {
                    _reason.value = sig.reason
                    if (_state.value == ConnectionState.Connected) {
                        _state.value = ConnectionState.Reconnecting
                    }
                }
            }
        }
        appScope.launch {
            settingsStore.settings.collect { s ->
                val wasConfigured = _settings.value.configured
                _settings.value = s
                if (s.configured && (!wasConfigured || base == null)) {
                    connect(s)
                } else if (!s.configured) {
                    _state.value = ConnectionState.Idle
                }
            }
        }
    }

    fun stop() {
        networkCallback?.let {
            runCatching { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        }
        networkCallback = null
        pty.detach()
        eventStream.stop()
        started.set(false)
    }

    /**
     * Re-establish the connection from a settings snapshot without going through
     * the reactive flow. Used by the notification action receiver, which may run
     * long after the app process was killed.
     */
    suspend fun restoreFrom(s: AppSettings) {
        if (s.configured && base == null) connect(s)
    }

    /**
     * Re-find the server with the token we already hold, so a paired phone
     * reconnects without anyone retyping a pairing code.
     *
     * Exactly one reachable host is the normal case and connects immediately.
     * Several means genuinely ambiguous — two opencode servers both accepting our
     * token, which happens if the same instance is reachable by both a tailnet IP
     * and a DNS name. Rather than pick arbitrarily, the results are published in
     * [discovered] and the caller shows a chooser.
     *
     * Returns the reachable candidates; zero means discovery failed and the user
     * has to fall back to the pairing screen.
     */
    suspend fun reconnectViaDiscovery(): List<DiscoveredServer> {
        val s = _settings.value
        if (s.sessionToken.isBlank()) return emptyList()
        _discoveryBusy.value = true
        _state.value = ConnectionState.Connecting
        try {
            val results = discovery.discover(s.host, s.sessionToken)
            _discovered.value = results
            val live = results.filter { it.reachable }
            Log.i(TAG, "discovery: ${live.size}/${results.size} reachable ${live.map { it.host }}")
            when (live.size) {
                0 -> {
                    _reason.value = DisconnectReason.Unknown
                    _state.value = ConnectionState.Failed
                }
                1 -> {
                    val winner = live.first()
                    if (winner.host != s.host) {
                        settingsStore.setHost(winner.host)
                        _settings.value = s.copy(host = winner.host)
                    }
                    connect(_settings.value)
                }
                else -> Unit // ambiguous: caller decides
            }
            return live
        } finally {
            _discoveryBusy.value = false
        }
    }

    /** Connect to a specific host the user picked out of [discovered]. */
    suspend fun connectTo(host: String) {
        val s = _settings.value
        settingsStore.setHost(host)
        _settings.value = s.copy(host = host)
        connect(_settings.value)
    }

    /** Apply new settings and reconnect from scratch. */
    suspend fun applySettings(s: AppSettings) {
        if (s.configured) connect(s) else {
            _state.value = ConnectionState.Idle
            base = null
        }
    }

    /**
     * Exchange credentials for a long-lived session token.
     *
     * Two paths, both ending in the same place — a session cookie stored on the
     * device instead of the server password:
     *
     *  - [code] supplied (from `opencode pair` on the server) → redeem it directly;
     *  - no code → mint one here, show it to the user, then redeem on the next call.
     */
    suspend fun applyForPairing(
        host: String,
        basicUser: String,
        basicPassword: String,
        code: String? = null,
    ): String? {
        val bootstrap = DirectTransport(
            id = "bootstrap",
            label = "bootstrap",
            url = host,
            basicUser = basicUser.ifBlank { null },
            basicPassword = basicPassword.ifBlank { null },
        )
        val resolved = bootstrap.resolve()

        // A temporary client scoped to just this exchange.
        val headers = HashMap(resolved.headers)
        val tempClient = dev.ryan.opencode.core.net.buildHttpClient(
            headerProvider = { HashMap(headers) },
            readTimeoutSeconds = 30,
        )
        val tempApi = OpencodeClient(object : OpencodeClient.OkHttpClientProvider {
            override val client = tempClient
            override val base = resolved.base
        })

        val pairingCode = code?.takeIf { it.isNotBlank() }
            ?: tempApi.pair(basicUser.ifBlank { "opencode" }, basicPassword).code

        val token = tempApi.redeemPairingCode(pairingCode)
        settingsStore.setSessionToken(token)
        settingsStore.setHost(host)
        if (basicPassword.isNotBlank()) settingsStore.setBasicPassword(basicPassword)

        _settings.value = _settings.value.copy(
            host = host,
            sessionToken = token,
            basicUser = basicUser.ifBlank { "opencode" },
        )
        connect(_settings.value)
        return pairingCode
    }

    private suspend fun connect(s: AppSettings) {
        _state.value = ConnectionState.Connecting
        _reason.value = DisconnectReason.None
        val t = DirectTransport(
            id = s.transportKind,
            label = s.transportKind,
            url = s.host,
            basicUser = s.basicUser.ifBlank { null },
            basicPassword = s.basicPassword.ifBlank { null },
            sessionToken = s.sessionToken.ifBlank { null },
        )
        try {
            val resolved = t.resolve()
            transport = t
            base = resolved.base
            headers.clear()
            headers.putAll(resolved.headers)
            // The event stream and the PTY WebSocket need the cookie spelled out.
            resolved.sessionToken?.let {
                headers["Cookie"] = "${dev.ryan.opencode.core.net.sessionCookieName(resolved.base)}=$it"
                SessionCookieStore.current = it
            }
            resolved.headers["Authorization"]?.let { BasicAuthStore.current = it }

            val info = api.info()
            Log.i(TAG, "connected to opencode ${info.version} at ${resolved.base}")
            _state.value = ConnectionState.Connected
            _reason.value = DisconnectReason.None
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.message}")
            _reason.value = DisconnectReason.Unknown
            _state.value = ConnectionState.Failed
        }
    }

    /**
     * Network transitions are the single biggest cause of the "keeps disconnecting"
     * experience. Reacting to availability changes directly means the app reattaches
     * in well under a second instead of waiting for a dead TCP connection to time out.
     */
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network available -> reconnecting")
                appScope.launch { reattachAll(DisconnectReason.NetworkChanged) }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "network lost")
                _state.value = ConnectionState.Reconnecting
                _reason.value = DisconnectReason.NetworkLost
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "network callback registration failed: ${it.message}") }
        networkCallback = callback
    }

    private suspend fun reattachAll(cause: DisconnectReason) {
        if (!_settings.value.configured) return
        // Brief settle so we don't thrash when Android flaps between networks.
        kotlinx.coroutines.delay(400)
        _reason.value = cause
        _state.value = ConnectionState.Reconnecting
        runCatching { connect(_settings.value) }
        pty.reattach()
    }

    companion object {
        private const val TAG = "ConnectionManager"

        @Volatile
        private var instance: ConnectionManager? = null

        fun get(context: Context): ConnectionManager =
            instance ?: synchronized(this) {
                instance ?: ConnectionManager(
                    context.applicationContext,
                    SettingsStore(context.applicationContext),
                ).also { instance = it }
            }
    }
}
