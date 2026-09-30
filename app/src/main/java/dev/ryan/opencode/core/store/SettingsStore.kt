package dev.ryan.opencode.core.store

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("opencode_settings")

/** Everything the app remembers between launches. */
data class AppSettings(
    val host: String = "",
    val basicUser: String = "opencode",
    val basicPassword: String = "",
    val sessionToken: String = "",
    val directory: String = "/home/ryan",
    val transportKind: String = "tailscale",
    val lastSessionId: String = "",
    val ttsEnabled: Boolean = false,
    val ttsPitch: Float = 1.0f,
    val ttsRate: Float = 1.0f,
    val voiceModeAutoListen: Boolean = true,
    val bargeInEnabled: Boolean = true,
    val terminalFontSize: Float = 12f,
    val keepScreenOn: Boolean = false,
    val notificationsEnabled: Boolean = true,
) {
    val configured: Boolean get() = host.isNotBlank() && sessionToken.isNotBlank()
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val host = stringPreferencesKey("host")
        val basicUser = stringPreferencesKey("basic_user")
        val basicPassword = stringPreferencesKey("basic_password")
        val sessionToken = stringPreferencesKey("session_token")
        val directory = stringPreferencesKey("directory")
        val transportKind = stringPreferencesKey("transport_kind")
        val lastSessionId = stringPreferencesKey("last_session_id")
        val ttsEnabled = booleanPreferencesKey("tts_enabled")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val ttsRate = floatPreferencesKey("tts_rate")
        val voiceAutoListen = booleanPreferencesKey("voice_auto_listen")
        val bargeIn = booleanPreferencesKey("barge_in")
        val terminalFontSize = floatPreferencesKey("terminal_font_size")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val notifications = booleanPreferencesKey("notifications")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        host = this[Keys.host].orEmpty(),
        basicUser = this[Keys.basicUser] ?: "opencode",
        basicPassword = this[Keys.basicPassword].orEmpty(),
        sessionToken = this[Keys.sessionToken].orEmpty(),
        directory = this[Keys.directory] ?: "/home/ryan",
        transportKind = this[Keys.transportKind] ?: "tailscale",
        lastSessionId = this[Keys.lastSessionId].orEmpty(),
        ttsEnabled = this[Keys.ttsEnabled] ?: false,
        ttsPitch = this[Keys.ttsPitch] ?: 1.0f,
        ttsRate = this[Keys.ttsRate] ?: 1.0f,
        voiceModeAutoListen = this[Keys.voiceAutoListen] ?: true,
        bargeInEnabled = this[Keys.bargeIn] ?: true,
        terminalFontSize = this[Keys.terminalFontSize] ?: 12f,
        keepScreenOn = this[Keys.keepScreenOn] ?: false,
        notificationsEnabled = this[Keys.notifications] ?: true,
    )

    suspend fun setHost(value: String) = put(Keys.host, value.trim())
    suspend fun setBasicUser(value: String) = put(Keys.basicUser, value.trim())
    suspend fun setBasicPassword(value: String) = put(Keys.basicPassword, value)
    suspend fun setSessionToken(value: String) = put(Keys.sessionToken, value.trim())
    suspend fun setDirectory(value: String) = put(Keys.directory, value.trim())
    suspend fun setTransportKind(value: String) = put(Keys.transportKind, value)
    suspend fun setLastSessionId(value: String) = put(Keys.lastSessionId, value)
    suspend fun setTtsEnabled(value: Boolean) = put(Keys.ttsEnabled, value)
    suspend fun setTtsPitch(value: Float) = put(Keys.ttsPitch, value)
    suspend fun setTtsRate(value: Float) = put(Keys.ttsRate, value)
    suspend fun setVoiceAutoListen(value: Boolean) = put(Keys.voiceAutoListen, value)
    suspend fun setBargeIn(value: Boolean) = put(Keys.bargeIn, value)
    suspend fun setTerminalFontSize(value: Float) = put(Keys.terminalFontSize, value)
    suspend fun setKeepScreenOn(value: Boolean) = put(Keys.keepScreenOn, value)
    suspend fun setNotificationsEnabled(value: Boolean) = put(Keys.notifications, value)

    suspend fun clearCredentials() {
        context.dataStore.edit {
            it.remove(Keys.sessionToken)
            it.remove(Keys.basicPassword)
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
