package dev.ryan.opencode.core.ssh

import android.app.Application
import android.util.Log
import dev.ryan.opencode.core.TerminalLink
import dev.ryan.opencode.core.model.DisconnectReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns whichever SSH link the terminal is currently using.
 *
 * At most one SSH session exists at a time. Switching routes tears the old one
 * down and opens the new one, because the terminal screen has exactly one byte
 * pipe and pointing two sources at one emulator is how you get a screen painted
 * from two interleaved sessions.
 */
class SshManager(
    private val app: Application,
    private val scope: CoroutineScope,
) {
    private val profileStore = SshProfileStore(app)
    private val keyStore = SshKeyStore(app)

    private val _link = MutableStateFlow<TerminalLink?>(null)
    val link: StateFlow<TerminalLink?> = _link.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _profiles = MutableStateFlow<List<SshProfile>>(emptyList())
    val profiles: StateFlow<List<SshProfile>> = _profiles.asStateFlow()

    init {
        // Mirror the store into a StateFlow so Compose can collect it directly;
        // DataStore's Flow is cold and would need an initial value otherwise.
        scope.launch { profileStore.profiles.collect { _profiles.value = it } }
    }

    fun keyPresent(): Boolean = keyStore.hasKey()

    /**
     * The public key as an `authorized_keys` line.
     *
     * The user pastes this onto the server; until they do, every connect fails
     * with a plain auth error, which is why the UI shows it rather than hiding it.
     */
    fun ensureKey(): String {
        val line = keyStore.ensureKey()
        Log.i(TAG, "ssh key fingerprint ${keyStore.fingerprint()}")
        return line
    }

    fun fingerprint(): String = keyStore.fingerprint()

    /** Forget this device's key. The profile stays; it just can no longer connect. */
    fun deleteKey() = keyStore.delete()

    suspend fun connect(profile: SshProfile, cols: Int, rows: Int) {
        _busy.value = true
        _error.value = null
        try {
            val terminal = SshTerminal(profile, keyStore)
            withContext(Dispatchers.IO) { terminal.connect(cols, rows) }

            // Host keys learned on this connect must outlive the session, or the
            // next one re-pins from scratch and the "changed key" protection never
            // engages.
            if (terminal.updatedKnownHosts != profile.knownHosts) {
                profileStore.saveKnownHosts(profile.id, terminal.updatedKnownHosts)
            }
            profileStore.markUsed(profile.id)

            // Replace any existing link only once the new one is actually up, so a
            // failed connect leaves the working session alone.
            _link.value?.let { old ->
                if (old is SshTerminal) scope.launch { old.disconnect() }
            }
            _link.value = terminal
        } catch (e: Exception) {
            _error.value = friendlyError(e)
            Log.w(TAG, "ssh connect failed: ${e::class.simpleName}: ${e.message}")
        } finally {
            _busy.value = false
        }
    }

    suspend fun disconnect() {
        _link.value?.let { scope.launch { it.detach() } }
        _link.value = null
    }

    fun upsert(profile: SshProfile) {
        scope.launch {
            profileStore.upsert(profile)
            _profiles.value = profileStore.all()
        }
    }

    fun delete(id: String) {
        scope.launch {
            profileStore.delete(id)
            _profiles.value = profileStore.all()
        }
    }

    /** Turn a thrown exception into something worth showing a person. */
    private fun friendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Auth fail", true) || msg.contains("authentication", true) ->
                "Server rejected the key. Paste the public key into ~/.ssh/authorized_keys."
            msg.contains("No SSH key", true) -> msg
            msg.contains("Connection refused", true) -> "Connection refused — is sshd running on port 22?"
            msg.contains("timeout", true) || msg.contains("timed out", true) ->
                "Timed out. Check the address and that the phone is on the tailnet."
            msg.contains("host key", true) || msg.contains("verifier", true) ->
                "The server's host key was rejected. It may have been reinstalled — " +
                    "remove the host from the profile to re-pin it."
            else -> msg.ifBlank { e::class.simpleName ?: "Unknown error" }
        }
    }

    private companion object {
        const val TAG = "SshManager"
    }
}