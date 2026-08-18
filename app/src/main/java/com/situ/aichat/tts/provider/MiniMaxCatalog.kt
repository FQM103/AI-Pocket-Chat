package com.situ.aichat.tts.provider

/**
 * MiniMax model + capability matrix. 1:1 with iOS `MiniMaxCatalog` / `MiniMaxModelCapability` /
 * `MiniMaxEmotionSet`. Source: MiniMax official T2A v2 docs (verified 2026-04 on iOS side).
 */
object MiniMaxCatalog {

    /** Built-in model list (newest → oldest). The first is the default; order drives the picker. */
    val builtInModels: List<TtsRemoteModelOption> = listOf(
        TtsRemoteModelOption("speech-2.8-hd", "speech-2.8-hd"),
        TtsRemoteModelOption("speech-2.8-turbo", "speech-2.8-turbo"),
        TtsRemoteModelOption("speech-2.6-hd", "speech-2.6-hd"),
        TtsRemoteModelOption("speech-2.6-turbo", "speech-2.6-turbo"),
        TtsRemoteModelOption("speech-02-hd", "speech-02-hd"),
        TtsRemoteModelOption("speech-02-turbo", "speech-02-turbo"),
        TtsRemoteModelOption("speech-01-hd", "speech-01-hd"),
        TtsRemoteModelOption("speech-01-turbo", "speech-01-turbo"),
    )

    /** Default model: speech-2.8-hd (supports the `(laughs)/(sighs)/(breath)` interpolation tags). */
    const val DEFAULT_MODEL_ID = "speech-2.8-hd"

    /** Capability matrix for a model id. Unknown models fall back to the most conservative (legacy). */
    fun capability(modelId: String): MiniMaxModelCapability {
        val lower = modelId.lowercase()
        return when {
            lower.startsWith("speech-2.8") ->
                MiniMaxModelCapability(supportsInterpolationTags = true, supportedEmotions = MiniMaxEmotionSet.SPEECH28)
            lower.startsWith("speech-2.6") ->
                MiniMaxModelCapability(supportsInterpolationTags = false, supportedEmotions = MiniMaxEmotionSet.SPEECH26)
            else ->
                MiniMaxModelCapability(supportsInterpolationTags = false, supportedEmotions = MiniMaxEmotionSet.LEGACY)
        }
    }

    /**
     * Minimal fallback voice list used when `/v1/get_voice` is unreachable or the key is wrong.
     * Legacy zh ids work across all speech-* models; the two English ids are the new 2.x naming.
     */
    val fallbackVoices: List<TtsRemoteVoiceOption> = listOf(
        TtsRemoteVoiceOption("male-qn-qingse", "青涩青年", "中文 · 男声"),
        TtsRemoteVoiceOption("male-qn-jingying", "精英青年", "中文 · 男声"),
        TtsRemoteVoiceOption("male-qn-badao", "霸道总裁", "中文 · 男声"),
        TtsRemoteVoiceOption("male-qn-daxuesheng", "阳光青年", "中文 · 男声"),
        TtsRemoteVoiceOption("female-shaonv", "少女音色", "中文 · 女声"),
        TtsRemoteVoiceOption("female-yujie", "御姐音色", "中文 · 女声"),
        TtsRemoteVoiceOption("female-chengshu", "成熟女性", "中文 · 女声"),
        TtsRemoteVoiceOption("female-tianmei", "甜美女性", "中文 · 女声"),
        TtsRemoteVoiceOption("presenter_male", "男主持人", "中文 · 男声"),
        TtsRemoteVoiceOption("presenter_female", "女主持人", "中文 · 女声"),
        TtsRemoteVoiceOption("English_Insightful_Speaker", "Insightful Speaker", "EN · Male"),
        TtsRemoteVoiceOption("English_Confident_Woman", "Confident Woman", "EN · Female"),
    )
}

/**
 * A MiniMax model's capabilities. Pure data, no logic.
 * - [supportsInterpolationTags]: supports `(laughs) (sighs) (breath)` tags (speech-2.8 only).
 * - [supportedEmotions]: the emotion enum set, used for clamping during auto-mapping degradation.
 */
data class MiniMaxModelCapability(
    val supportsInterpolationTags: Boolean,
    val supportedEmotions: MiniMaxEmotionSet,
)

/**
 * Emotion sets supported by each MiniMax model generation (verified 2026-04):
 * - all 8 speech models support the base 7: happy/sad/angry/fearful/disgusted/surprised/calm
 * - "fluent" only on speech-2.6 / speech-2.8
 * - "whisper" only on speech-2.6
 * - "neutral" is NOT in the official enum (never send it); "auto" = omit the field (server default).
 */
enum class MiniMaxEmotionSet {
    SPEECH28,
    SPEECH26,
    LEGACY;

    val values: Set<String>
        get() = when (this) {
            // speech-2.8: base 7 + fluent, but NOT whisper
            SPEECH28 -> setOf("happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm", "fluent")
            // speech-2.6: full set — base 7 + fluent + whisper
            SPEECH26 -> setOf("happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm", "fluent", "whisper")
            // speech-02 / speech-01: base 7
            LEGACY -> setOf("happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm")
        }

    /**
     * Clamp a requested emotion to what this model actually supports.
     * - null / empty / "auto" / "neutral" (deprecated) → null (omit the field, server default auto)
     * - supported → as-is
     * - unsupported (e.g. whisper on speech-2.8) → "calm"
     * - if "calm" itself is unavailable (never happens today) → null
     */
    fun clamped(requested: String?): String? {
        if (requested == null) return null
        val value = requested.trim().lowercase()
        if (value.isEmpty() || value == "auto" || value == "neutral") return null
        if (values.contains(value)) return value
        if (values.contains("calm")) return "calm"
        return null
    }
}
