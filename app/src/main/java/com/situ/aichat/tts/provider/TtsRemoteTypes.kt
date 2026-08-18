package com.situ.aichat.tts.provider

import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsResponseFormat

/**
 * Shared value types for the remote-TTS provider layer. 1:1 with the iOS types defined at the top
 * of `TTSService.swift` and in `TTSRemoteProvider.swift`.
 */

/** Voice source — mirrors MiniMax get_voice's three arrays. iOS `TTSVoiceKind`. */
enum class TtsVoiceKind { SYSTEM, CLONED, GENERATED }

/** iOS `TTSRemoteVoiceOption`. */
data class TtsRemoteVoiceOption(
    val id: String,
    val name: String,
    val detail: String?,
    val kind: TtsVoiceKind = TtsVoiceKind.SYSTEM,
) {
    val displayName: String
        get() = if (detail.isNullOrEmpty()) name else "$name（$detail）"
}

/** iOS `TTSRemoteModelOption`. */
data class TtsRemoteModelOption(val id: String, val name: String)

/**
 * MiniMax-only `voice_setting` overrides (iOS `MiniMaxVoiceOverrides`). Other providers ignore it.
 * - [emotion] null → the field is omitted from the request (MiniMax server default = "auto").
 * - [speed] official range [0.5, 2.0]; [pitch] official range [-12, 12] (already clamped upstream).
 */
data class MiniMaxVoiceOverrides(
    val emotion: String?,
    val speed: Double,
    val pitch: Int,
)

/** iOS `TTSRemoteConfigValues` — the resolved config handed to a provider for one synth call. */
data class TtsRemoteConfigValues(
    val providerType: TtsProviderType,
    val providerName: String,
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val responseFormat: TtsResponseFormat,
    val miniMaxVoiceOverrides: MiniMaxVoiceOverrides? = null,
)

/** iOS `TTSResolvedProvider`. */
sealed interface TtsResolvedProvider {
    data class System(val voiceIdentifier: String) : TtsResolvedProvider
    data class Remote(val voiceId: String, val config: TtsRemoteConfigValues) : TtsResolvedProvider
    data object None : TtsResolvedProvider
}

/** iOS `TTSRemoteError`. */
sealed class TtsRemoteException(message: String) : Exception(message) {
    data object InvalidUrl : TtsRemoteException("The TTS URL is invalid.")
    data object InvalidResponse : TtsRemoteException("The TTS service returned an invalid response.")
    class HttpError(val statusCode: Int) : TtsRemoteException("The TTS service returned HTTP $statusCode")
    class BusinessError(val code: Int, val serverMessage: String) :
        TtsRemoteException("TTS service error $code: $serverMessage")
}
