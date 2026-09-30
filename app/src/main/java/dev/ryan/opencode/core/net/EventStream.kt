package dev.ryan.opencode.core.net

import dev.ryan.opencode.core.model.ServerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow

/**
 * Server-sent events from `GET /api/event`.
 *
 * Verified: the endpoint needs **both** basic auth and the session cookie — the
 * cookie alone returns 401. Frames look like:
 *
 *     data: {"id":"evt_…","type":"session.text.delta","data":{…}}
 *
 *     : heartbeat
 *
 * Because the opencode server holds all session state, a dropped event stream is
 * never a data-loss event: we reconnect and re-read the message list. That is the
 * whole reason this app never needs a "reconnect" button.
 */
class EventStream(
    private val clientProvider: () -> OkHttpClient,
    private val baseProvider: () -> HttpUrl,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val _signals = MutableSharedFlow<StreamSignal>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val signals: Flow<StreamSignal> = _signals.asSharedFlow()

    private val backoff = Backoff()

    fun events(): Flow<StreamSignal> {
        _signals.tryEmit(StreamSignal.Connecting)
        startStream()
        return _signals
    }

    private fun startStream() {
        val http = clientProvider()
        val url = baseProvider().newBuilder().addPathSegments("api/event").build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                scheduleReconnect()
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    _signals.tryEmit(StreamSignal.AuthProblem(code == 401 || code == 403))
                    scheduleReconnect()
                    return
                }
                _signals.tryEmit(StreamSignal.Open)
                backoff.markConnected()
                val source = response.body?.source()
                if (source == null) { response.close(); scheduleReconnect(); return }
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty() || line.startsWith(":")) {
                            backoff.markActivity()
                            continue
                        }
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        val event = runCatching {
                            OpencodeJson.decodeFromString<ServerEvent>(payload)
                        }.getOrNull() ?: continue
                        _signals.tryEmit(StreamSignal.Event(event))
                        backoff.markActivity()
                    }
                } catch (_: Exception) {
                    // fall through to reconnect
                } finally {
                    runCatching { response.close() }
                }
                scheduleReconnect()
            }
        })
    }

    private var reconnectJob: Job? = null

    private fun scheduleReconnect() {
        if (!scope.isActive) return
        val attempt = backoff.nextAttempt()
        _signals.tryEmit(StreamSignal.Retrying(attempt))
        val waitMs = min(30_000.0, 500.0 * 2.0.pow((attempt - 1).coerceIn(0, 6))).toLong()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(waitMs)
            if (scope.isActive) startStream()
        }
    }

    fun stop() {
        reconnectJob?.cancel()
    }
}

/** Signals the UI needs in order to distinguish "retrying" from "auth is broken". */
sealed interface StreamSignal {
    data object Connecting : StreamSignal
    data object Open : StreamSignal
    data class Event(val event: ServerEvent) : StreamSignal
    data class Retrying(val attempt: Int) : StreamSignal
    data class AuthProblem(val unauthorized: Boolean) : StreamSignal
}

/** Exponential backoff, reset whenever the stream proves it is alive. */
private class Backoff {
    private var attempt = 0
    private var connected = false

    @Synchronized
    fun markConnected() { connected = true; attempt = 0 }

    @Synchronized
    fun markActivity() { attempt = 0 }

    @Synchronized
    fun nextAttempt(): Int {
        if (connected) { connected = false; attempt = 1 } else attempt++
        return attempt
    }
}
