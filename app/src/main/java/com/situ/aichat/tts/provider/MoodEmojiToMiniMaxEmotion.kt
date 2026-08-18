package com.situ.aichat.tts.provider

import com.situ.aichat.tts.EmotionType

/**
 * Mood emoji → MiniMax `voice_setting.emotion`, in two steps (1:1 iOS `MoodEmojiToMiniMaxEmotion`):
 *   1. emoji → [EmotionType]
 *   2. [EmotionType] → MiniMax emotion string
 *
 * The result still goes through a per-model [MiniMaxEmotionSet.clamped] second pass at the call
 * site. "neutral" is NOT a valid MiniMax emotion, so thinking/neutral return null (server default
 * "auto"). EmotionType→emotion is a deliberate best-fit degradation (MiniMax has no excited/love/
 * shy/playful, so those collapse to the nearest supported emotion).
 */
object MoodEmojiToMiniMaxEmotion {

    /**
     * Derive the MiniMax emotion from a mood emoji. Returns the raw (not yet model-clamped) string,
     * or null to follow the server default. The caller must still run [MiniMaxEmotionSet.clamped].
     */
    fun emotionFor(moodEmoji: String?): String? {
        if (moodEmoji.isNullOrEmpty()) return null
        return emotionFor(EmotionType.from(moodEmoji))
    }

    /** Translate an [EmotionType] to a MiniMax emotion string. thinking/neutral → null (auto). */
    fun emotionFor(type: EmotionType): String? = when (type) {
        EmotionType.HAPPY -> "happy"
        EmotionType.EXCITED -> "happy"
        EmotionType.ANGRY -> "angry"
        EmotionType.SAD -> "sad"
        EmotionType.SHOCKED -> "surprised"
        EmotionType.SHY -> "calm"
        EmotionType.LOVE -> "happy"
        EmotionType.THINKING -> null
        EmotionType.SCARED -> "fearful"
        EmotionType.PLAYFUL -> "happy"
        EmotionType.SIGH -> "sad"
        EmotionType.NEUTRAL -> null
    }
}
