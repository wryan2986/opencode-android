package dev.ryan.opencode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.ryan.opencode.core.ChatRepository
import dev.ryan.opencode.core.ConnectionManager
import dev.ryan.opencode.core.model.ConnectionState
import dev.ryan.opencode.core.model.DisconnectReason
import dev.ryan.opencode.core.net.PtySignal
import dev.ryan.opencode.core.store.AppSettings
import dev.ryan.opencode.voice.VoiceEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val connection = ConnectionManager.get(app)
    private val settingsStore = connection.settingsStore

    val notifications = dev.ryan.opencode.core.NotificationCentre(app, { sid, rid, allow ->
        connection.api.replyPermission(sid, rid, if (allow) "once" else "reject", false, settings.value.directory)
    })

    val chat = ChatRepository(connection, viewModelScope, notifications)

    val settings: StateFlow<AppSettings> = connection.settings
    val connectionState: StateFlow<ConnectionState> = connection.state
    val disconnectReason: StateFlow<DisconnectReason> = connection.reason

    val voice = VoiceEngine(app, viewModelScope)

    private val _ptyConnected = MutableStateFlow(false)
    val ptyConnected: StateFlow<Boolean> = _ptyConnected.asStateFlow()

    private val ptyStarting = AtomicBoolean(false)
    private val _ptyId = MutableStateFlow<String?>(null)
    val ptyId: StateFlow<String?> = _ptyId.asStateFlow()

    /** Set by the UI when a stream signals, so we can react (notifications, TTS). */
    val voiceEngine: VoiceEngine get() = voice

    init {
        connection.start()
        chat.start()
        viewModelScope.launch {
            connection.pty.signals.collect { sig ->
                if (sig is PtySignal.Opened) _ptyConnected.value = true
                if (sig is PtySignal.Dropped) _ptyConnected.value = false
            }
        }
        viewModelScope.launch {
            // Bootstrap when the directory changes OR the app becomes configured for
            // the first time. Keying only on the directory meant a first-time pair
            // (where the directory never changes) never loaded anything.
            var lastKey: Pair<String, Boolean>? = null
            connection.settings.collect { s ->
                val key = s.directory to s.configured
                if (key != lastKey) {
                    lastKey = key
                    chat.setDirectory(s.directory)
                    if (s.configured) chat.bootstrap()
                }
            }
        }
    }

    /**
     * Create and attach the shell exactly once.
     *
     * The `_ptyId.value != null` check alone is racy: two rapid calls (tab switch
     * plus a recomposition) both see null, both create a PTY, and both feed their
     * output into the same emulator — which shows up as the prompt appearing
     * twice on one line. The atomic guard makes it a genuine single-flight.
     */
    /** Called when the chat screen becomes visible. */
    fun onChatResumed() {
        viewModelScope.launch {
            chat.refreshSessions()
            chat.refreshPermissions()
        }
    }

    fun ensureTerminal(cols: Int = 100, rows: Int = 30) {
        if (_ptyId.value != null) return
        if (!ptyStarting.compareAndSet(false, true)) return
        viewModelScope.launch {
            val dir = settings.value.directory
            runCatching { connection.api.createPty(dir, cols = cols, rows = rows) }
                .onSuccess {
                    _ptyId.value = it.id
                    connection.pty.attach(it.id)
                }
                .onFailure {
                    android.util.Log.w("AppViewModel", "pty create failed: ${it.message}")
                }
            ptyStarting.set(false)
        }
    }

    /**
     * Recreate the shell at a new size (rotation, or a very different window).
     *
     * The emulator has no reflow, so an in-place resize would splice the existing
     * screen; starting a fresh PTY gives a clean grid instead.
     */
    fun restartTerminal(cols: Int, rows: Int) {
        closeTerminal()
        ensureTerminal(cols, rows)
    }

    fun closeTerminal() {
        ptyStarting.set(false)
        val id = _ptyId.value
        _ptyId.value = null
        connection.pty.detach()
        _ptyConnected.value = false
        if (id != null) viewModelScope.launch { runCatching { connection.api.deletePty(id) } }
    }

    fun saveSettings(update: suspend (dev.ryan.opencode.core.store.SettingsStore) -> Unit) {
        viewModelScope.launch {
            update(settingsStore)
            val latest = settingsStore.settings.let { flow ->
                var value: AppSettings? = null
                flow.collect { value = it; return@collect }
                value
            }
            latest?.let { connection.applySettings(it) }
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                return AppViewModel(app) as T
            }
        }
    }
}
