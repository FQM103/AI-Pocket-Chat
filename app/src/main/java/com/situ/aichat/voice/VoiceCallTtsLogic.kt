package com.situ.aichat.voice

import com.situ.aichat.prompt.ReplyParser
import com.situ.aichat.sticker.StickerTagParser

/**
 * Pure, framework-free core of the call TTS pipeline (the port-bug-prone parts of iOS
 * `VoiceCallTTSPipeline`): sentence cutting, tag cleaning, committed-text joining, playback-level
 * normalization, and the small scheduling/status decisions. Kept `internal` + side-effect-free so the
 * assertions can be reverse-derived from the iOS values (see verification-process). The stateful
 * orchestration (coroutines, the ExoPlayer, the queue) lives in [VoiceCallTtsPipeline].
 */
internal object VoiceCallTtsLogic {

    /** 1:1 iOS `sentenceEndings` (VoiceCallTTSPipeline.swift:42). A sentence is cut *after* one of these. */
    val SENTENCE_ENDINGS: Set<Char> =
        setOf('。', '？', '！', '；', '…', '?', '!', '.', ';', '～', '~', '—', '\n')

    /** Max sentences synthesized at once — 1:1 iOS `maxConcurrentConversions` (VoiceCallTTSPipeline.swift:54). */
    const val MAX_CONCURRENT_CONVERSIONS = 2

    /** Per-sentence remote-synth timeout — 1:1 iOS 30 s race (VoiceCallTTSPipeline.swift:174). */
    const val SENTENCE_TIMEOUT_MS = 30_000L

    /** The 7 statuses a queued sentence moves through — 1:1 iOS `SentenceStatus` (VoiceCallTTSPipeline.swift:16-24). */
    enum class SentenceStatus { PENDING_TTS, CONVERTING, READY, PLAYING, PLAYED, INTERRUPTED, DISCARDED }

    data class SplitResult(val sentences: List<String>, val remainder: String)

    /**
     * Cut every complete sentence out of [buffer] (1:1 iOS `feedToken` while-loop): repeatedly take up to
     * and including the first ending char, trim it, keep it if non-empty; the text after the last ending
     * stays as the (un-trimmed) remainder for the next token. No ending → no sentences, whole buffer kept.
     */
    fun cutSentences(buffer: String): SplitResult {
        if (buffer.isEmpty()) return SplitResult(emptyList(), buffer)
        var working = buffer
        val out = mutableListOf<String>()
        while (true) {
            val idx = working.indexOfFirst { it in SENTENCE_ENDINGS }
            if (idx < 0) break
            val sentence = working.substring(0, idx + 1).trim()
            working = working.substring(idx + 1)
            if (sentence.isNotEmpty()) out.add(sentence)
        }
        return SplitResult(out, working)
    }

    /**
     * Clean a sentence before synthesis (1:1 iOS `appendSentence`: `stripStickerTags` then
     * `stripInternalAssistantTags` with defaults). Note the default `preserveMiniMaxVoiceTags=false` — the
     * call path strips MiniMax voice tags before TTS (iOS PromptBuilder+Parsing.swift:430-433: tags are
     * preserved only on the chat-message MiniMax+2.8 synth path, NOT here).
     */
    fun cleanSentence(raw: String): String =
        ReplyParser.stripInternalAssistantTags(StickerTagParser.stripStickerTags(raw))

    /** Committed (spoken) transcript = the played sentences joined + trimmed — 1:1 iOS `resolvedCommittedText`. */
    fun resolvedCommittedText(playedTexts: List<String>): String =
        playedTexts.joinToString("").trim()

    /** Playback level from average power dBFS: `(power+50)/50` clamped 0..1 — 1:1 iOS metering (swift:268). */
    fun normalizePlaybackLevel(powerDb: Float): Float =
        ((powerDb + 50f) / 50f).coerceIn(0f, 1f)

    /** Whether another concurrent conversion may start — 1:1 iOS `startNextConversionIfNeeded` guard (swift:137-139). */
    fun canStartConversion(activeConversions: Int, hasPending: Boolean): Boolean =
        activeConversions < MAX_CONCURRENT_CONVERSIONS && hasPending

    /**
     * Status of a sentence after its synth completes (1:1 iOS `convertSentence` tail, swift:185-204):
     * no audio (failure / 30 s timeout) → discarded; audio but the sentence was discarded meanwhile →
     * stays discarded; otherwise → ready.
     */
    fun resolveConvertedStatus(hasAudio: Boolean, current: SentenceStatus): SentenceStatus = when {
        !hasAudio -> SentenceStatus.DISCARDED
        current == SentenceStatus.DISCARDED -> SentenceStatus.DISCARDED
        else -> SentenceStatus.READY
    }

    /** A sentence is "resolved" (needs no more work) once played/discarded/interrupted — 1:1 iOS (swift:305-312). */
    fun isResolved(status: SentenceStatus): Boolean =
        status == SentenceStatus.PLAYED || status == SentenceStatus.DISCARDED || status == SentenceStatus.INTERRUPTED

    /** All sentences resolved → the turn can be finalized — 1:1 iOS `allResolved` (swift:305-313). */
    fun allResolved(statuses: List<SentenceStatus>): Boolean = statuses.all { isResolved(it) }

    /**
     * After all sentences resolve, did anything actually reach the user? (played/interrupted) — drives the
     * `onAllSentencesPlayed` vs `onAllSentencesFailed` split. Empty queue counts as "played" (1:1 iOS
     * swift:317-322: `anyPlayed || sentences.isEmpty`).
     */
    fun shouldReportPlayed(statuses: List<SentenceStatus>): Boolean =
        statuses.isEmpty() || statuses.any { it == SentenceStatus.PLAYED || it == SentenceStatus.INTERRUPTED }
}
