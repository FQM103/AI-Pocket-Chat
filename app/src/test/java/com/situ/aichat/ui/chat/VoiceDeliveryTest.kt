package com.situ.aichat.ui.chat

import com.situ.aichat.prompt.PromptBuilder.AssistantDeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chat voice-reply decision logic. Assertions reverse-derived from iOS `plannedAssistantDeliveryPlan`
 * + `resolveAssistantDelivery`: mirror a user voice message, else go voice once rounds reach the
 * threshold; offline forces text and freezes the counter; voice resets it to 0.
 */
class VoiceDeliveryTest {

    // MARK: - planAssistantDelivery

    @Test
    fun `no available voice always plans text`() {
        // Even with a user voice message + threshold reached, no configured voice → text.
        val p = planAssistantDelivery(hasVoice = false, latestUserMessageIsVoice = true, voiceRoundsSinceLastVoice = 10, voiceNextThreshold = 5)
        assertEquals(AssistantDeliveryMode.TEXT, p.mode)
        assertEquals(AssistantDeliveryReason.TEXT, p.reason)
    }

    @Test
    fun `user voice message is mirrored when voice available`() {
        val p = planAssistantDelivery(hasVoice = true, latestUserMessageIsVoice = true, voiceRoundsSinceLastVoice = 0, voiceNextThreshold = 5)
        assertEquals(AssistantDeliveryMode.VOICE, p.mode)
        assertEquals(AssistantDeliveryReason.USER_VOICE_MIRRORING, p.reason)
    }

    @Test
    fun `voice fires when next round reaches threshold`() {
        // rounds=4 → nextRound=5 >= threshold 5 → voice.
        val p = planAssistantDelivery(hasVoice = true, latestUserMessageIsVoice = false, voiceRoundsSinceLastVoice = 4, voiceNextThreshold = 5)
        assertEquals(AssistantDeliveryMode.VOICE, p.mode)
        assertEquals(AssistantDeliveryReason.SCHEDULED_VOICE, p.reason)
    }

    @Test
    fun `text when next round below threshold`() {
        // rounds=2 → nextRound=3 < threshold 5 → text.
        val p = planAssistantDelivery(hasVoice = true, latestUserMessageIsVoice = false, voiceRoundsSinceLastVoice = 2, voiceNextThreshold = 5)
        assertEquals(AssistantDeliveryMode.TEXT, p.mode)
        assertEquals(AssistantDeliveryReason.TEXT, p.reason)
    }

    // MARK: - resolveAssistantDelivery

    @Test
    fun `planned voice resets counter to zero`() {
        val o = resolveAssistantDelivery(isOffline = false, hasOfflineMeetingAction = false, plannedVoice = true, voiceRoundsSinceLastVoice = 3)
        assertEquals(true, o.shouldBeVoice)
        assertEquals(0, o.nextVoiceRoundsSinceLastVoice)
    }

    @Test
    fun `planned text increments counter`() {
        val o = resolveAssistantDelivery(isOffline = false, hasOfflineMeetingAction = false, plannedVoice = false, voiceRoundsSinceLastVoice = 3)
        assertEquals(false, o.shouldBeVoice)
        assertEquals(4, o.nextVoiceRoundsSinceLastVoice)
    }

    @Test
    fun `offline forces text and freezes counter`() {
        val o = resolveAssistantDelivery(isOffline = true, hasOfflineMeetingAction = false, plannedVoice = true, voiceRoundsSinceLastVoice = 3)
        assertEquals(false, o.shouldBeVoice)
        assertEquals(3, o.nextVoiceRoundsSinceLastVoice) // frozen, not incremented
    }

    @Test
    fun `pending offline-meeting action forces text but still increments`() {
        val o = resolveAssistantDelivery(isOffline = false, hasOfflineMeetingAction = true, plannedVoice = true, voiceRoundsSinceLastVoice = 3)
        assertEquals(false, o.shouldBeVoice)
        assertEquals(4, o.nextVoiceRoundsSinceLastVoice)
    }
}
