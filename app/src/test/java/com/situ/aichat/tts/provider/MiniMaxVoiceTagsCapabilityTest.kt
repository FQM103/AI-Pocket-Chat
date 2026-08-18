package com.situ.aichat.tts.provider

import com.situ.aichat.tts.TtsProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MiniMax voice-tag front-door gate (6 conditions). Assertions reverse-derived from iOS
 * `MiniMaxVoiceTagsCapability.shouldInjectTagsHint`: provider==miniMax && isVoiceMode &&
 * characterHasRemoteVoice && userToggleEnabled && !isOfflineMode && model supports interpolation
 * tags (speech-2.8 only). Any single condition off → no injection (and the back door still strips).
 */
class MiniMaxVoiceTagsCapabilityTest {

    private fun capability(
        providerType: TtsProviderType = TtsProviderType.MINIMAX,
        modelName: String = "speech-2.8-hd",
        characterHasRemoteVoice: Boolean = true,
        userToggleEnabled: Boolean = true,
        isVoiceMode: Boolean = true,
        isOfflineMode: Boolean = false,
    ) = MiniMaxVoiceTagsCapability(
        providerType = providerType,
        modelName = modelName,
        characterHasRemoteVoice = characterHasRemoteVoice,
        userToggleEnabled = userToggleEnabled,
        isVoiceMode = isVoiceMode,
        isOfflineMode = isOfflineMode,
    )

    @Test
    fun `all six conditions met injects`() {
        val cap = capability()
        assertTrue(cap.shouldInjectTagsHint)
        // preserve == inject (currently equivalent, 1:1 iOS)
        assertTrue(cap.shouldPreserveVoiceTagsWhenCleaning)
    }

    @Test
    fun `non-MiniMax provider never injects`() {
        assertFalse(capability(providerType = TtsProviderType.SYSTEM).shouldInjectTagsHint)
        assertFalse(capability(providerType = TtsProviderType.VOLINK).shouldInjectTagsHint)
        assertFalse(capability(providerType = TtsProviderType.OPENAI).shouldInjectTagsHint)
        assertFalse(capability(providerType = TtsProviderType.CUSTOM_OPENAI_COMPATIBLE).shouldInjectTagsHint)
    }

    @Test
    fun `text mode does not inject`() {
        assertFalse(capability(isVoiceMode = false).shouldInjectTagsHint)
    }

    @Test
    fun `character without remote voice does not inject`() {
        assertFalse(capability(characterHasRemoteVoice = false).shouldInjectTagsHint)
    }

    @Test
    fun `user toggle off does not inject`() {
        assertFalse(capability(userToggleEnabled = false).shouldInjectTagsHint)
    }

    @Test
    fun `offline mode does not inject`() {
        assertFalse(capability(isOfflineMode = true).shouldInjectTagsHint)
    }

    @Test
    fun `models without interpolation tags do not inject`() {
        // Only speech-2.8 supports interpolation tags; 2.6 / legacy do not (= MiniMaxCatalog).
        assertFalse(capability(modelName = "speech-2.6-hd").shouldInjectTagsHint)
        assertFalse(capability(modelName = "speech-2.6-turbo").shouldInjectTagsHint)
        assertFalse(capability(modelName = "speech-01-turbo").shouldInjectTagsHint)
        assertFalse(capability(modelName = "").shouldInjectTagsHint)
        // speech-2.8 turbo still qualifies.
        assertTrue(capability(modelName = "speech-2.8-turbo").shouldInjectTagsHint)
    }
}
