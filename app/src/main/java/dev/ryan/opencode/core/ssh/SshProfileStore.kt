package dev.ryan.opencode.core.ssh

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.sshStore by preferencesDataStore("opencode_ssh")

/**
 * Saved SSH destinations and the pinned host keys that go with them.
 *
 * Only non-secret data lives here — profiles and host keys. The private key stays
 * in the AndroidKeyStore and is never written to disk or into preferences, so this
 * store is safe to back up even though the app excludes it anyway.
 */
class SshProfileStore(private val context: Context) {

    private object Keys {
        val profiles = stringPreferencesKey("ssh_profiles_json")
        val lastUsed = stringPreferencesKey("ssh_last_used")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val profiles: Flow<List<SshProfile>> = context.sshStore.data.map { prefs ->
        decode(prefs[Keys.profiles])
    }

    val lastUsedId: Flow<String> = context.sshStore.data.map { prefs ->
        prefs[Keys.lastUsed].orEmpty()
    }

    suspend fun all(): List<SshProfile> = profiles.first()

    suspend fun upsert(profile: SshProfile) = mutate { current ->
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current.toMutableList().also { it[idx] = profile } else current + profile
    }

    suspend fun delete(id: String) = mutate { current -> current.filterNot { it.id == id } }

    suspend fun markUsed(id: String) {
        context.sshStore.edit { it[Keys.lastUsed] = id }
    }

    /** Persist host keys learned during a connect. */
    suspend fun saveKnownHosts(id: String, knownHosts: Map<String, String>) = mutate { current ->
        current.map { if (it.id == id && it.knownHosts != knownHosts) it.copy(knownHosts = knownHosts) else it }
    }

    private suspend fun mutate(block: (List<SshProfile>) -> List<SshProfile>) {
        context.sshStore.edit { prefs ->
            val updated = block(decode(prefs[Keys.profiles]))
            prefs[Keys.profiles] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SshProfile.serializer()), updated)
        }
    }

    private fun decode(raw: String?): List<SshProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SshProfile.serializer()), raw) }.getOrElse { emptyList() }
    }

    companion object {
        /** Stable id so a profile survives being edited rather than recreated. */
        fun newId(): String = "ssh-" + java.util.UUID.randomUUID().toString().take(8)
    }
}