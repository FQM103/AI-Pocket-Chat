package com.situ.aichat.voice

import com.situ.aichat.voice.VoiceCallTtsLogic.SentenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the call TTS pipeline core. Assertions are reverse-derived from the iOS
 * `VoiceCallTTSPipeline` values (sentence-ending set, concurrency 2, 30 s, `(power+50)/50`, the
 * convert/notify decision tables), per verification-process — not from the Kotlin output.
 */
class VoiceCallTtsLogicTest {

    // ---- cutSentences (= iOS feedToken split, swift:60-73) ----

    @Test
    fun cut_singleSentence_keepsRemainder() {
        val r = VoiceCallTtsLogic.cutSentences("你好。世界")
        assertEquals(listOf("你好。"), r.sentences)
        assertEquals("世界", r.remainder)
    }

    @Test
    fun cut_multipleSentences() {
        val r = VoiceCallTtsLogic.cutSentences("你好。在吗？嗯")
        assertEquals(listOf("你好。", "在吗？"), r.sentences)
        assertEquals("嗯", r.remainder)
    }

    @Test
    fun cut_noEnding_bufferKept() {
        val r = VoiceCallTtsLogic.cutSentences("没有标点的句子")
        assertTrue(r.sentences.isEmpty())
        assertEquals("没有标点的句子", r.remainder)
    }

    @Test
    fun cut_empty() {
        val r = VoiceCallTtsLogic.cutSentences("")
        assertTrue(r.sentences.isEmpty())
        assertEquals("", r.remainder)
    }

    @Test
    fun cut_everyEndingChar_splits() {
        // 1:1 iOS sentenceEndings set (swift:42): 。？！；…?!.;～~—\n
        val endings = listOf('。', '？', '！', '；', '…', '?', '!', '.', ';', '～', '~', '—', '\n')
        for (e in endings) {
            val r = VoiceCallTtsLogic.cutSentences("a${e}b")
            assertEquals("ending '$e' should cut", listOf("a$e".trim()), r.sentences)
            assertEquals("b", r.remainder)
        }
    }

    @Test
    fun cut_newlineTrimmedFromSentence() {
        val r = VoiceCallTtsLogic.cutSentences("line1\nline2")
        assertEquals(listOf("line1"), r.sentences) // trailing \n trimmed by trim()
        assertEquals("line2", r.remainder)
    }

    @Test
    fun cut_trimsWhitespaceAroundEachSentence() {
        val r = VoiceCallTtsLogic.cutSentences("  hi。 ")
        assertEquals(listOf("hi。"), r.sentences)
        assertEquals(" ", r.remainder)
    }

    @Test
    fun cut_consecutiveEndings() {
        val r = VoiceCallTtsLogic.cutSentences("。。")
        assertEquals(listOf("。", "。"), r.sentences)
        assertEquals("", r.remainder)
    }

    // NOTE: cleanSentence (= iOS appendSentence cleanup) composes StickerTagParser.stripStickerTags +
    // ReplyParser.stripInternalAssistantTags. ReplyParser's `\p{Han}` regex is accepted by Android's
    // ICU-backed engine but throws on the desktop JVM (and Robolectric uses the host engine), so it can't
    // be exercised in a plain unit test; both strippers are production-proven on-device. The 入队清洗 is
    // validated on device in 10.1g.

    // ---- resolvedCommittedText (= iOS resolvedCommittedText, swift:294-300) ----

    @Test
    fun committed_joinsPlayedTexts() {
        assertEquals("你好。世界！", VoiceCallTtsLogic.resolvedCommittedText(listOf("你好。", "世界！")))
    }

    @Test
    fun committed_emptyWhenNothingPlayed() {
        assertEquals("", VoiceCallTtsLogic.resolvedCommittedText(emptyList()))
    }

    // ---- normalizePlaybackLevel (= iOS (power+50)/50 clamp, swift:268) ----

    @Test
    fun level_mapping() {
        assertEquals(1f, VoiceCallTtsLogic.normalizePlaybackLevel(0f), EPS)
        assertEquals(0f, VoiceCallTtsLogic.normalizePlaybackLevel(-50f), EPS)
        assertEquals(0.5f, VoiceCallTtsLogic.normalizePlaybackLevel(-25f), EPS)
    }

    @Test
    fun level_clamped() {
        assertEquals(0f, VoiceCallTtsLogic.normalizePlaybackLevel(-160f), EPS) // floor
        assertEquals(1f, VoiceCallTtsLogic.normalizePlaybackLevel(12f), EPS)   // ceiling
    }

    // ---- concurrency gate (= iOS maxConcurrentConversions = 2, swift:54/137-139) ----

    @Test
    fun conversion_gate() {
        assertEquals(2, VoiceCallTtsLogic.MAX_CONCURRENT_CONVERSIONS)
        assertTrue(VoiceCallTtsLogic.canStartConversion(activeConversions = 0, hasPending = true))
        assertTrue(VoiceCallTtsLogic.canStartConversion(activeConversions = 1, hasPending = true))
        assertFalse("2 in flight = at cap", VoiceCallTtsLogic.canStartConversion(activeConversions = 2, hasPending = true))
        assertFalse("nothing pending", VoiceCallTtsLogic.canStartConversion(activeConversions = 0, hasPending = false))
    }

    @Test
    fun sentenceTimeout_is30s() {
        assertEquals(30_000L, VoiceCallTtsLogic.SENTENCE_TIMEOUT_MS)
    }

    // ---- convert outcome (= iOS convertSentence tail, swift:185-204) ----

    @Test
    fun converted_noAudio_discards() {
        // null audio = failure or 30 s timeout → discarded (whatever the prior status was)
        assertEquals(SentenceStatus.DISCARDED, VoiceCallTtsLogic.resolveConvertedStatus(hasAudio = false, current = SentenceStatus.CONVERTING))
        assertEquals(SentenceStatus.DISCARDED, VoiceCallTtsLogic.resolveConvertedStatus(hasAudio = false, current = SentenceStatus.DISCARDED))
    }

    @Test
    fun converted_withAudio_readyUnlessAlreadyDiscarded() {
        assertEquals(SentenceStatus.READY, VoiceCallTtsLogic.resolveConvertedStatus(hasAudio = true, current = SentenceStatus.CONVERTING))
        // interrupted mid-synth → stays discarded even though audio came back
        assertEquals(SentenceStatus.DISCARDED, VoiceCallTtsLogic.resolveConvertedStatus(hasAudio = true, current = SentenceStatus.DISCARDED))
    }

    // ---- completion gating (= iOS notifyAllPlayedIfNeeded, swift:302-322) ----

    @Test
    fun resolved_predicates() {
        assertTrue(VoiceCallTtsLogic.isResolved(SentenceStatus.PLAYED))
        assertTrue(VoiceCallTtsLogic.isResolved(SentenceStatus.DISCARDED))
        assertTrue(VoiceCallTtsLogic.isResolved(SentenceStatus.INTERRUPTED))
        assertFalse(VoiceCallTtsLogic.isResolved(SentenceStatus.PENDING_TTS))
        assertFalse(VoiceCallTtsLogic.isResolved(SentenceStatus.CONVERTING))
        assertFalse(VoiceCallTtsLogic.isResolved(SentenceStatus.READY))
        assertFalse(VoiceCallTtsLogic.isResolved(SentenceStatus.PLAYING))
    }

    @Test
    fun allResolved_requiresEveryTerminal() {
        assertTrue(VoiceCallTtsLogic.allResolved(listOf(SentenceStatus.PLAYED, SentenceStatus.DISCARDED)))
        assertTrue(VoiceCallTtsLogic.allResolved(emptyList()))
        assertFalse(VoiceCallTtsLogic.allResolved(listOf(SentenceStatus.PLAYED, SentenceStatus.CONVERTING)))
    }

    @Test
    fun shouldReportPlayed_emptyOrAnyPlayed() {
        assertTrue("empty → played (iOS sentences.isEmpty)", VoiceCallTtsLogic.shouldReportPlayed(emptyList()))
        assertTrue(VoiceCallTtsLogic.shouldReportPlayed(listOf(SentenceStatus.PLAYED, SentenceStatus.DISCARDED)))
        assertTrue(VoiceCallTtsLogic.shouldReportPlayed(listOf(SentenceStatus.INTERRUPTED)))
        assertFalse("all discarded → failed", VoiceCallTtsLogic.shouldReportPlayed(listOf(SentenceStatus.DISCARDED, SentenceStatus.DISCARDED)))
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
