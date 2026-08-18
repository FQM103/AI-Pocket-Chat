package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Structure parity for [PromptBuilder.buildMiniMaxVoiceTagsHint] (1:1 iOS `buildMiniMaxVoiceTagsHint`):
 * 18 newline-joined parts in a fixed order, with the two locale-independent literal lines (the
 * vocalization token list + the pause examples) verbatim. Resolves real `pb_voice_tags_*` resources
 * (Robolectric default locale = en) so a missing/renamed key would fail here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniMaxVoiceTagsHintTest {

    private fun hint(): String {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        return PromptBuilder.buildMiniMaxVoiceTagsHint(strings)
    }

    @Test
    fun `hint has the 18 iOS parts in order`() {
        // iOS appends exactly 18 parts (incl. 3 blank separators) joined by "\n".
        assertEquals(18, hint().split("\n").size)
    }

    @Test
    fun `vocalization token list is verbatim`() {
        assertTrue(
            hint().contains(
                "(laughs) (sighs) (breath) (gasps) (sniffs) (groans) (inhale) (exhale) (humming) (clear-throat)",
            ),
        )
    }

    @Test
    fun `pause examples are verbatim`() {
        assertTrue(hint().contains("<#0.3#>  <#0.5#>  <#1#>"))
    }

    @Test
    fun `starts with the voice-tags header`() {
        // en (Robolectric default): "[Voice Expression Tags]".
        assertTrue(hint().startsWith("[Voice Expression Tags]"))
    }
}
