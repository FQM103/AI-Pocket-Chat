package com.situ.aichat.tts

/**
 * Global TTS configuration (1:1 iOS `TTSConfiguration`, a singleton). On Android this is a plain
 * read-model; persistence (DataStore) + the settings UI are built in the TTS-config-UI chunk. The
 * API key itself lives in [com.situ.aichat.security.ApiKeyStore] keyed by [apiKeyId]; only the id is
 * stored here (mirrors iOS keychain-backed `apiKeyId`).
 */
data class TtsConfiguration(
    val providerType: TtsProviderType = TtsProviderType.SYSTEM,
    val providerName: String = "Volink",
    val apiKeyId: String = DEFAULT_API_KEY_ID,
    val baseURL: String = "",
    val modelName: String = "",
    val defaultSystemVoiceIdentifier: String = "",
    val defaultRemoteVoiceID: String = "",
    val responseFormat: TtsResponseFormat = TtsResponseFormat.MP3,
) {
    /**
     * Mirror of iOS `TTSConfigurationService.normalizeSingleton`: a blank provider name falls back to
     * the provider's display name; switching to the system provider clears the remote baseURL/model.
     */
    fun normalized(): TtsConfiguration {
        val name = providerName.trim().ifEmpty { providerType.displayName }
        return if (providerType == TtsProviderType.SYSTEM) {
            copy(providerName = name, baseURL = "", modelName = "")
        } else {
            copy(providerName = name)
        }
    }

    companion object {
        /** Single TTS config → one stable key id (no per-row UUID needed, unlike chat configs). */
        const val DEFAULT_API_KEY_ID = "tts_api_key"
    }
}

/**
 * The per-character voice fields TTS resolution reads (from [com.situ.aichat.data.local.entity.CharacterEntity]).
 * A small value type so resolution stays decoupled from the entity and is easy to unit-test.
 */
data class TtsVoiceProfile(
    val voiceIdentifier: String = "",
    val remoteVoiceID: String = "",
    val ttsEmotionRaw: String = "auto",
    val ttsSpeed: Double = 1.0,
    val ttsPitch: Int = 0,
)
