package com.situ.aichat.tts

/**
 * Mirrors the iOS `TTSProviderType` (Models/TTSConfiguration.swift). This is the TTS-specific
 * provider enum, deliberately separate from the chat [com.situ.aichat.data.model.ApiProviderType]:
 * TTS distinguishes an explicit `openai` case from `custom_openai_compatible`, plus `volink` and
 * `minimax`. `system` = on-device Android TextToSpeech.
 *
 * rawValue strings are identical to iOS for backup / forward compatibility.
 */
enum class TtsProviderType(val raw: String) {
    SYSTEM("system"),
    VOLINK("volink"),
    OPENAI("openai"),
    CUSTOM_OPENAI_COMPATIBLE("custom_openai_compatible"),
    MINIMAX("minimax");

    val displayName: String
        get() = when (this) {
            SYSTEM -> "系统 TTS"
            VOLINK -> "Volink"
            OPENAI -> "OpenAI"
            CUSTOM_OPENAI_COMPATIBLE -> "自定义 (OpenAI 协议)"
            MINIMAX -> "MiniMax"
        }

    /**
     * Default base URL (iOS `defaultBaseURL`). Empty string = the user must supply one.
     * Volink uses the documented native endpoint `/v1/tts/speech` (docs.volink.org API reference;
     * the legacy undocumented `/api/v1/tts` behaves identically today but carries no stability
     * promise — stored legacy URLs are migrated in [com.situ.aichat.tts.provider.CompatibleTtsProvider.buildSpeechUrl]).
     * MiniMax defaults to the mainland-China endpoint (overseas keys from minimax.io return 1004 there).
     */
    val defaultBaseUrl: String
        get() = when (this) {
            SYSTEM -> ""
            VOLINK -> "https://api.volink.org/v1/tts/speech"
            OPENAI -> "https://api.openai.com/v1"
            CUSTOM_OPENAI_COMPATIBLE -> ""
            MINIMAX -> "https://api.minimaxi.com/v1/t2a_v2"
        }

    val usesRemoteApi: Boolean get() = this != SYSTEM

    companion object {
        /**
         * Resolve a stored rawValue. Mirrors the iOS `@Transient providerType` migration:
         * the legacy `"openai_compatible"` value maps to [VOLINK] when the baseURL or provider name
         * hints Volink, otherwise [CUSTOM_OPENAI_COMPATIBLE]; any other unknown value → [SYSTEM].
         */
        fun fromRaw(raw: String, baseUrl: String = "", providerName: String = ""): TtsProviderType {
            entries.firstOrNull { it.raw == raw }?.let { return it }
            if (raw == "openai_compatible") {
                val hintsVolink = baseUrl.lowercase().contains("volink") ||
                    providerName.lowercase().contains("volink")
                return if (hintsVolink) VOLINK else CUSTOM_OPENAI_COMPATIBLE
            }
            return SYSTEM
        }
    }
}

/** Mirrors the iOS `TTSResponseFormat`. */
enum class TtsResponseFormat(val raw: String) {
    MP3("mp3"),
    WAV("wav");

    val displayName: String get() = raw.uppercase()

    companion object {
        fun fromRaw(raw: String): TtsResponseFormat = entries.firstOrNull { it.raw == raw } ?: MP3
    }
}
