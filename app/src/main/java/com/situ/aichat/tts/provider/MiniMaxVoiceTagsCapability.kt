package com.situ.aichat.tts.provider

import android.content.Context
import com.situ.aichat.tts.TtsProviderType

/**
 * MiniMax voice-tag "front door" gate (1:1 iOS `MiniMaxVoiceTagsCapability.swift`). Decides — before
 * the system prompt is sent to the LLM — whether to teach the model it MAY sprinkle
 * `(laughs)/(sighs)/(breath)/<#0.5#>` tags into this reply. All 6 conditions must hold:
 *
 * 1. provider == MiniMax  2. this reply is voice  3. the MiniMax model supports interpolation tags
 * (speech-2.8 only)  4. the character is bound to a remote voice  5. the user toggle is on
 * 6. not offline mode.
 *
 * The matching BACK DOOR ([com.situ.aichat.prompt.ReplyParser.stripMiniMaxVoiceTags]) strips these
 * tags from every text path unconditionally, so a front-door misfire can never leak a tag into a
 * text bubble. Pure (no Context) so the gate is unit-testable.
 */
data class MiniMaxVoiceTagsCapability(
    val providerType: TtsProviderType,
    val modelName: String,
    val characterHasRemoteVoice: Boolean,
    val userToggleEnabled: Boolean,
    val isVoiceMode: Boolean,
    val isOfflineMode: Boolean,
) {
    /** Front-door decision: inject the voice-tag teaching only when all 6 conditions hold. */
    val shouldInjectTagsHint: Boolean
        get() {
            if (providerType != TtsProviderType.MINIMAX) return false
            if (!isVoiceMode) return false
            if (!characterHasRemoteVoice) return false
            if (!userToggleEnabled) return false
            if (isOfflineMode) return false
            return MiniMaxCatalog.capability(modelName).supportsInterpolationTags
        }

    /**
     * Whether the cleaning path should PRESERVE voice tags (feed them through to TTS). Currently
     * equivalent to [shouldInjectTagsHint]; kept separate to mirror iOS's allowance for a future
     * "injected but not preserved" (or vice-versa) split.
     */
    val shouldPreserveVoiceTagsWhenCleaning: Boolean
        get() = shouldInjectTagsHint
}

/**
 * User toggle for MiniMax voice-tag teaching (1:1 iOS `MiniMaxVoiceTagsSettings`, default **true**).
 * Backed by SharedPreferences — like iOS UserDefaults and the [com.situ.aichat.tts.pricing.TtsUsageTracker]:
 * a singleton-level global preference that needs no DB migration and never pollutes the character
 * backup. It only expresses "the user allows the LLM to learn the tags"; the other 5 front-door
 * conditions still gate actual injection.
 */
object MiniMaxVoiceTagsSettings {
    private const val PREFS = "tts_minimax_voice_tags"
    private const val KEY_ENABLED = "enabled"

    /** Defaults to true (covers fresh installs + upgrades, same as iOS's "no key written → true"). */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
