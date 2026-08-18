package com.situ.aichat.tts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.situ.aichat.security.ApiKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the single [TtsConfiguration] (1:1 iOS `TTSConfiguration` singleton) in DataStore,
 * following the project's [com.situ.aichat.data.repository.SettingsRepository] idiom (typed keys +
 * `data.map` Flow + `edit` writes). The API key itself goes to [ApiKeyStore] under the fixed
 * [TtsConfiguration.DEFAULT_API_KEY_ID] (the DB/prefs never hold the secret).
 */
@Singleton
class TtsConfigurationRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val keyStore: ApiKeyStore,
) {
    val configuration: Flow<TtsConfiguration> = dataStore.data.map { p ->
        TtsConfiguration(
            providerType = TtsProviderType.fromRaw(
                raw = p[KEY_PROVIDER] ?: TtsProviderType.SYSTEM.raw,
                baseUrl = p[KEY_BASE_URL] ?: "",
                providerName = p[KEY_PROVIDER_NAME] ?: "",
            ),
            providerName = p[KEY_PROVIDER_NAME] ?: "Volink",
            apiKeyId = TtsConfiguration.DEFAULT_API_KEY_ID,
            baseURL = p[KEY_BASE_URL] ?: "",
            modelName = p[KEY_MODEL] ?: "",
            defaultSystemVoiceIdentifier = p[KEY_SYSTEM_VOICE] ?: "",
            defaultRemoteVoiceID = p[KEY_REMOTE_VOICE] ?: "",
            responseFormat = TtsResponseFormat.fromRaw(p[KEY_RESPONSE_FORMAT] ?: TtsResponseFormat.MP3.raw),
        )
    }

    suspend fun getConfiguration(): TtsConfiguration = configuration.first()

    /** Persist the config (after [TtsConfiguration.normalized]). Does NOT touch the key — see [setApiKey]. */
    suspend fun update(config: TtsConfiguration) {
        val c = config.normalized()
        dataStore.edit { p ->
            p[KEY_PROVIDER] = c.providerType.raw
            p[KEY_PROVIDER_NAME] = c.providerName
            p[KEY_BASE_URL] = c.baseURL
            p[KEY_MODEL] = c.modelName
            p[KEY_SYSTEM_VOICE] = c.defaultSystemVoiceIdentifier
            p[KEY_REMOTE_VOICE] = c.defaultRemoteVoiceID
            p[KEY_RESPONSE_FORMAT] = c.responseFormat.raw
        }
    }

    // The key is never read back into the UI; only existence is surfaced (masked write-only field).
    // suspend（K3 主线程安全）：底层 ApiKeyStore 全 suspend + IO，语音管线/预览不再于主线程解密。
    suspend fun getApiKey(): String = keyStore.get(TtsConfiguration.DEFAULT_API_KEY_ID) ?: ""
    suspend fun hasApiKey(): Boolean = getApiKey().isNotEmpty()
    suspend fun setApiKey(value: String) { keyStore.put(TtsConfiguration.DEFAULT_API_KEY_ID, value) }
    suspend fun clearApiKey() = keyStore.delete(TtsConfiguration.DEFAULT_API_KEY_ID)

    private companion object {
        val KEY_PROVIDER = stringPreferencesKey("tts_provider_type")
        val KEY_PROVIDER_NAME = stringPreferencesKey("tts_provider_name")
        val KEY_BASE_URL = stringPreferencesKey("tts_base_url")
        val KEY_MODEL = stringPreferencesKey("tts_model_name")
        val KEY_SYSTEM_VOICE = stringPreferencesKey("tts_default_system_voice")
        val KEY_REMOTE_VOICE = stringPreferencesKey("tts_default_remote_voice")
        val KEY_RESPONSE_FORMAT = stringPreferencesKey("tts_response_format")
    }
}
