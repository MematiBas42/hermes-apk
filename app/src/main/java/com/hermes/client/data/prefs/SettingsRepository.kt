package com.hermes.client.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val systemInstructions: String = "",
    val toolProgress: Boolean = true,
    /** Display name of the assistant inside a conversation (local only). */
    val assistantName: String = "Hermes",
    /** Absolute path of the locally stored assistant avatar, or blank for the default icon. */
    val assistantAvatarPath: String = "",
    /** Base URL of the companion file service. Blank means "derive from [baseUrl]". */
    val filesBaseUrl: String = "",
    /**
     * ntny push lane for cron jobs (agreed design with the Hermes agent: its ntfy platform
     * plugin delivers job results; this app subscribes to the same topic for instant syncs
     * while in the foreground). Blank URL/topic disables the lane; the 15-minute WorkManager
     * reconcile keeps working regardless.
     */
    val jobsPushUrl: String = "",
    val jobsPushTopic: String = "",
    /** Optional `Authorization: Bearer <token>` for the ntfy subscription. */
    val jobsPushToken: String = "",
    val appLanguage: String = "system",
    /** Opt-in: attach credentials to plaintext loopback URLs (SSH/FRP local tunnels). */
    val allowLoopbackCredentials: Boolean = false,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    /** Effective file-service base URL (explicit override, else derived from [baseUrl]). */
    val effectiveFilesBaseUrl: String
        get() = filesBaseUrl.trim().trimEnd('/').ifBlank { deriveFilesBaseUrl(baseUrl) }

    companion object {
        /**
         * Intentionally empty: no server is baked into the binary. Earlier versions shipped a
         * hardcoded default pointing at the maintainer's VPS — fine for a private build, but it
         * published the target host in an open-source repo and funneled every sideloaded copy at
         * it. Each user enters their own Base URL in Settings on first launch.
         */
        const val DEFAULT_BASE_URL = ""

        /**
         * Given an API base URL, guess the companion file-service base URL. The server exposes
         * `<origin>/hermes-api` and `<origin>/hermes-files`, so we swap that suffix; otherwise we
         * append `/hermes-files`.
         */
        fun deriveFilesBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            for (suffix in listOf("/hermes-api", "/api")) {
                if (trimmed.endsWith(suffix)) {
                    return trimmed.removeSuffix(suffix) + "/hermes-files"
                }
            }
            return trimmed + "/hermes-files"
        }
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hermes_settings",
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secretCipher: SecretCipher,
) {
    private val dataStore = context.settingsDataStore

    /** In-memory mirrors so synchronous OkHttp interceptors can read the current config. */
    @Volatile
    var cachedBaseUrl: String = AppSettings.DEFAULT_BASE_URL
        private set

    @Volatile
    var cachedApiKey: String = ""
        private set

    /** Auth header for the companion file service (and Coil image loads). */
    @Volatile
    var cachedFilesBaseUrl: String = AppSettings.deriveFilesBaseUrl(AppSettings.DEFAULT_BASE_URL)
        private set

    /** ntfy push lane caches — read synchronously by [com.hermes.client.data.remote.NtfyPushController]. */
    @Volatile
    var cachedJobsPushUrl: String = ""
        private set

    @Volatile
    var cachedJobsPushTopic: String = ""
        private set

    @Volatile
    var cachedJobsPushToken: String = ""
        private set

    /** Opt-in for credentials over plaintext loopback; read by [com.hermes.client.data.remote.CredentialPolicy] callers. */
    @Volatile
    var cachedAllowLoopbackCredentials: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs.toSettings() }
        .onEach { s ->
            cachedBaseUrl = s.baseUrl
            cachedApiKey = s.apiKey
            cachedFilesBaseUrl = s.effectiveFilesBaseUrl
            cachedJobsPushUrl = s.jobsPushUrl
            cachedJobsPushTopic = s.jobsPushTopic
            cachedJobsPushToken = s.jobsPushToken
            cachedAllowLoopbackCredentials = s.allowLoopbackCredentials
        }
        .distinctUntilChanged()

    init {
        // Keep the synchronous cache warm for interceptors / SSE.
        scope.launch { settings.collect { } }
        // One-shot migration: rewrite any pre-encryption plaintext secrets so the on-disk
        // DataStore no longer carries them in the clear.
        scope.launch { migratePlaintextSecrets() }
    }

    private suspend fun migratePlaintextSecrets() {
        runCatching {
            val prefs = dataStore.data.first()
            val apiKey = prefs[KEY_API_KEY]
            val pushToken = prefs[KEY_JOBS_PUSH_TOKEN]
            if (!apiKey.isNullOrEmpty() && !apiKey.startsWith(SecretCipher.V1_PREFIX)) {
                setApiKey(apiKey)
            }
            if (!pushToken.isNullOrEmpty() && !pushToken.startsWith(SecretCipher.V1_PREFIX)) {
                setJobsPushToken(pushToken)
            }
        }
    }

    suspend fun snapshot(): AppSettings = settings.first()

    // Update the volatile mirrors synchronously as well, so a request issued right after saving
    // (e.g. "Test Connection") can't race the DataStore -> flow -> cache round trip.
    suspend fun setBaseUrl(value: String) {
        val trimmed = value.trim()
        dataStore.edit { it[KEY_BASE_URL] = trimmed }
        cachedBaseUrl = trimmed
        val explicit = dataStore.data.first()[KEY_FILES_BASE_URL]?.trim().orEmpty()
        cachedFilesBaseUrl = explicit.ifBlank { AppSettings.deriveFilesBaseUrl(trimmed) }
    }

    suspend fun setApiKey(value: String) {
        val trimmed = value.trim()
        dataStore.edit { it[KEY_API_KEY] = secretCipher.encrypt(trimmed) }
        cachedApiKey = trimmed
    }

    suspend fun setThemeMode(value: ThemeMode) =
        dataStore.edit { it[KEY_THEME] = value.name }

    suspend fun setDynamicColor(value: Boolean) =
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = value }

    suspend fun setSystemInstructions(value: String) =
        dataStore.edit { it[KEY_SYSTEM_INSTRUCTIONS] = value }

    suspend fun setToolProgress(value: Boolean) =
        dataStore.edit { it[KEY_TOOL_PROGRESS] = value }

    suspend fun setAssistantName(value: String) =
        dataStore.edit { it[KEY_ASSISTANT_NAME] = value.trim() }

    suspend fun setAssistantAvatarPath(value: String) =
        dataStore.edit { it[KEY_ASSISTANT_AVATAR] = value.trim() }

    suspend fun setFilesBaseUrl(value: String) {
        val trimmed = value.trim()
        dataStore.edit { it[KEY_FILES_BASE_URL] = trimmed }
        cachedFilesBaseUrl = trimmed.ifBlank { AppSettings.deriveFilesBaseUrl(cachedBaseUrl) }
    }

    suspend fun setJobsPushUrl(value: String) {
        val trimmed = value.trim()
        dataStore.edit { it[KEY_JOBS_PUSH_URL] = trimmed }
        cachedJobsPushUrl = trimmed
    }

    suspend fun setJobsPushTopic(value: String) {
        val trimmed = value.trim()
        dataStore.edit { it[KEY_JOBS_PUSH_TOPIC] = trimmed }
        cachedJobsPushTopic = trimmed
    }

    suspend fun setJobsPushToken(value: String) {
        val trimmed = value.trim()
        dataStore.edit { it[KEY_JOBS_PUSH_TOKEN] = secretCipher.encrypt(trimmed) }
        cachedJobsPushToken = trimmed
    }

    suspend fun setAllowLoopbackCredentials(value: Boolean) {
        dataStore.edit { it[KEY_ALLOW_LOOPBACK_CREDENTIALS] = value }
        cachedAllowLoopbackCredentials = value
    }

    suspend fun setAppLanguage(value: String) {
        dataStore.edit { it[KEY_APP_LANGUAGE] = value }
    }

    private fun Preferences.toSettings() = AppSettings(
        baseUrl = this[KEY_BASE_URL] ?: "",
        apiKey = secretCipher.decrypt(this[KEY_API_KEY] ?: ""),
        themeMode = runCatching { ThemeMode.valueOf(this[KEY_THEME] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = this[KEY_DYNAMIC_COLOR] ?: false,
        systemInstructions = this[KEY_SYSTEM_INSTRUCTIONS] ?: "",
        toolProgress = this[KEY_TOOL_PROGRESS] ?: true,
        assistantName = this[KEY_ASSISTANT_NAME]?.takeIf { it.isNotBlank() } ?: "Hermes",
        assistantAvatarPath = this[KEY_ASSISTANT_AVATAR] ?: "",
        filesBaseUrl = this[KEY_FILES_BASE_URL] ?: "",
        jobsPushUrl = this[KEY_JOBS_PUSH_URL] ?: "",
        jobsPushTopic = this[KEY_JOBS_PUSH_TOPIC] ?: "",
        jobsPushToken = secretCipher.decrypt(this[KEY_JOBS_PUSH_TOKEN] ?: ""),
        appLanguage = this[KEY_APP_LANGUAGE] ?: "system",
        allowLoopbackCredentials = this[KEY_ALLOW_LOOPBACK_CREDENTIALS] ?: false,
    )

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_SYSTEM_INSTRUCTIONS = stringPreferencesKey("system_instructions")
        val KEY_TOOL_PROGRESS = booleanPreferencesKey("tool_progress")
        val KEY_ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        val KEY_ASSISTANT_AVATAR = stringPreferencesKey("assistant_avatar_path")
        val KEY_FILES_BASE_URL = stringPreferencesKey("files_base_url")
        val KEY_JOBS_PUSH_URL = stringPreferencesKey("jobs_push_url")
        val KEY_JOBS_PUSH_TOPIC = stringPreferencesKey("jobs_push_topic")
        val KEY_JOBS_PUSH_TOKEN = stringPreferencesKey("jobs_push_token")
        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_ALLOW_LOOPBACK_CREDENTIALS = booleanPreferencesKey("allow_loopback_credentials")
    }
}
