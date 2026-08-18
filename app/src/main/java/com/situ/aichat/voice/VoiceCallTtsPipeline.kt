package com.situ.aichat.voice

import android.util.Log
import com.situ.aichat.voice.VoiceCallTtsLogic.SentenceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Synth-while-play TTS queue for a voice-call turn — the Android port of iOS `VoiceCallTTSPipeline`.
 * Tokens stream in via [feedToken]; complete sentences are cut ([VoiceCallTtsLogic.cutSentences]), cleaned,
 * queued, and synthesized up to [VoiceCallTtsLogic.MAX_CONCURRENT_CONVERSIONS] at a time (each capped at
 * [VoiceCallTtsLogic.SENTENCE_TIMEOUT_MS]); ready sentences play sequentially through [CallTtsPlayer], and
 * each is released the moment it finishes (long-call OOM guard).
 *
 * **Concurrency model = iOS `@MainActor` 1:1, no locks.** All mutable state is confined to
 * `Dispatchers.Main.immediate`: [feedToken]/[finishFeeding]/[interrupt]/[reset] are called from the
 * controller's main coroutines, and the convert/play coroutines launch on a per-turn [turnScope] that is
 * also `Main.immediate`. The convert coroutines only *suspend* off-main inside the synth call; every state
 * mutation happens back on main. `convertGeneration` + cancelling [turnScope] on [reset] invalidate stale
 * work (= iOS `convertGeneration` UUID).
 *
 * Callbacks (set once by [VoiceCallController]) map to the iOS pipeline callbacks: [onFirstSentenceReady],
 * [onAllSentencesPlayed], [onAllSentencesFailed], [onSentenceCommitted], [onSentenceFailed],
 * [onBeforePlayback]. The AI-speaking waveform reads [CallTtsPlayer.audioLevel] directly from the player.
 */
internal class VoiceCallTtsPipeline(
    private val player: CallTtsPlayer,
) {
    private class Sentence(val text: String, var status: SentenceStatus, var audio: ByteArray?)

    var onFirstSentenceReady: (() -> Unit)? = null
    var onAllSentencesPlayed: (() -> Unit)? = null
    var onAllSentencesFailed: (() -> Unit)? = null
    var onSentenceCommitted: ((String) -> Unit)? = null
    var onSentenceFailed: ((String) -> Unit)? = null
    /** Re-apply the audio route before each sentence (= iOS `onBeforePlayback` → `applyPreferredAudioRoute`). */
    var onBeforePlayback: (() -> Unit)? = null

    private var turnScope: CoroutineScope? = null
    private var synthesize: (suspend (String) -> ByteArray?)? = null

    private val sentences = mutableListOf<Sentence>()
    private var sentenceBuffer = ""
    private var playbackIndex = 0
    private var activeConversions = 0
    private var isPlaying = false
    private var isFeedingFinished = false
    private var hasFiredFirstReady = false
    private var hasFiredAllPlayed = false
    private var convertGeneration = 0
    private var committedText = ""
    private var playJob: Job? = null

    /** The text actually spoken so far (played sentences joined) — 1:1 iOS `committedTranscriptText`. */
    val committedTranscriptText: String
        get() = VoiceCallTtsLogic.resolvedCommittedText(sentences.filter { it.status == SentenceStatus.PLAYED }.map { it.text })

    /** Reset + arm a fresh turn with the (already-resolved, no-moodEmoji) [synthesize] for the round. */
    fun beginTurn(synthesize: suspend (String) -> ByteArray?) {
        reset()
        this.synthesize = synthesize
        turnScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }

    fun feedToken(token: String) {
        if (token.isEmpty()) return
        sentenceBuffer += token
        val result = VoiceCallTtsLogic.cutSentences(sentenceBuffer)
        sentenceBuffer = result.remainder
        for (sentence in result.sentences) appendSentence(sentence)
    }

    fun finishFeeding() {
        isFeedingFinished = true
        val remaining = sentenceBuffer.trim()
        sentenceBuffer = ""
        if (remaining.isNotEmpty()) appendSentence(remaining)
        checkAndPlayNext()
        notifyAllPlayedIfNeeded()
    }

    /**
     * Stop now, discard the unplayed tail, return what was already spoken (= iOS `interrupt`). Also marks
     * the turn as "all-played fired" so a late in-flight conversion can't re-fire completion after the
     * controller has moved on to the listening/userSpeaking leg (robustness divergence from iOS, which
     * relies on the next `reset` to invalidate stragglers).
     */
    fun interrupt(): String {
        val currentIndex = playbackIndex
        stopPlayback()
        if (currentIndex in sentences.indices && sentences[currentIndex].status == SentenceStatus.PLAYING) {
            sentences[currentIndex].status = SentenceStatus.DISCARDED
        }
        for (i in (currentIndex + 1) until sentences.size) {
            if (sentences[i].status != SentenceStatus.PLAYED) sentences[i].status = SentenceStatus.DISCARDED
        }
        hasFiredAllPlayed = true
        return committedTranscriptText
    }

    /** Tear the queue down (= iOS `reset`): bump generation, cancel the turn's coroutines, clear all state. */
    fun reset() {
        convertGeneration++
        turnScope?.cancel()
        turnScope = null
        stopPlayback()
        synthesize = null
        sentences.clear()
        sentenceBuffer = ""
        playbackIndex = 0
        activeConversions = 0
        isFeedingFinished = false
        hasFiredFirstReady = false
        hasFiredAllPlayed = false
        committedText = ""
    }

    // MARK: - Queue (all on Main.immediate)

    private fun appendSentence(rawSentence: String) {
        val cleaned = VoiceCallTtsLogic.cleanSentence(rawSentence)
        if (cleaned.isEmpty()) return
        sentences.add(Sentence(text = cleaned, status = SentenceStatus.PENDING_TTS, audio = null))
        Log.d(TAG, "queued sentence #${sentences.size - 1} (len=${cleaned.length})")
        startNextConversionIfNeeded()
    }

    private fun startNextConversionIfNeeded() {
        val hasPending = sentences.any { it.status == SentenceStatus.PENDING_TTS }
        if (!VoiceCallTtsLogic.canStartConversion(activeConversions, hasPending)) return
        val index = sentences.indexOfFirst { it.status == SentenceStatus.PENDING_TTS }
        if (index < 0) return
        activeConversions++
        val generation = convertGeneration
        val synth = synthesize
        turnScope?.launch { convertSentence(index, generation, synth) }
    }

    private suspend fun convertSentence(index: Int, generation: Int, synth: (suspend (String) -> ByteArray?)?) {
        try {
            if (generation != convertGeneration || index !in sentences.indices) return
            if (sentences[index].status != SentenceStatus.PENDING_TTS) return
            sentences[index].status = SentenceStatus.CONVERTING
            val text = sentences[index].text
            Log.d(TAG, "synth start #$index")
            // 30 s cap per sentence (= iOS withTaskGroup race). withTimeoutOrNull → null discards it.
            val audio = if (synth == null) null else withTimeoutOrNull(VoiceCallTtsLogic.SENTENCE_TIMEOUT_MS) { synth(text) }
            if (generation != convertGeneration || index !in sentences.indices) return

            when (VoiceCallTtsLogic.resolveConvertedStatus(audio != null, sentences[index].status)) {
                SentenceStatus.READY -> {
                    sentences[index].audio = audio
                    sentences[index].status = SentenceStatus.READY
                    Log.d(TAG, "synth ready #$index")
                    checkAndPlayNext()
                }
                SentenceStatus.DISCARDED -> {
                    sentences[index].status = SentenceStatus.DISCARDED
                    if (audio == null) {
                        // Real failure / 30 s timeout → notify + keep playing the rest (= iOS swift:185-192).
                        Log.d(TAG, "synth failed/timed out #$index → discarded")
                        onSentenceFailed?.invoke(text)
                        checkAndPlayNext()
                    }
                    notifyAllPlayedIfNeeded()
                }
                else -> {}
            }
        } finally {
            if (generation == convertGeneration) {
                activeConversions = (activeConversions - 1).coerceAtLeast(0)
                startNextConversionIfNeeded()
            }
        }
    }

    private fun checkAndPlayNext() {
        if (isPlaying) return
        while (playbackIndex < sentences.size) {
            when (sentences[playbackIndex].status) {
                SentenceStatus.READY -> {
                    playSentence(playbackIndex)
                    return
                }
                SentenceStatus.DISCARDED, SentenceStatus.PLAYED -> playbackIndex++
                SentenceStatus.PENDING_TTS, SentenceStatus.CONVERTING -> return
                SentenceStatus.PLAYING, SentenceStatus.INTERRUPTED -> return
            }
        }
        notifyAllPlayedIfNeeded()
    }

    private fun playSentence(index: Int) {
        val audio = sentences[index].audio ?: return
        sentences[index].status = SentenceStatus.PLAYING
        isPlaying = true
        // C1 状态机修复（2026-07-12）：first-ready 在「第一次真正开播」时点火，而不是钉死在 index==0 的合成
        // 成功上——首句合成失败、次句成功时，旧点位永不触发，controller 停在 PROCESSING 却在出声（状态/字幕/
        // barge-in 全错位、整轮无法打断）。挪到这里后语义恢复为 iOS 的「第一句准备好、即将开口」。
        if (!hasFiredFirstReady) {
            hasFiredFirstReady = true
            onFirstSentenceReady?.invoke()
        }
        onBeforePlayback?.invoke()
        playJob = turnScope?.launch {
            val ok = try {
                player.play(audio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "play #$index error: ${e.message}")
                false
            }
            // Only the natural-end / error path reaches here; cancellation (interrupt/reset) skips it.
            if (index in sentences.indices && sentences[index].status == SentenceStatus.PLAYING) {
                if (ok) {
                    sentences[index].status = SentenceStatus.PLAYED
                    commitSentence(index)
                } else {
                    sentences[index].status = SentenceStatus.DISCARDED
                }
                playbackIndex++
            }
            isPlaying = false
            checkAndPlayNext()
        }
    }

    private fun commitSentence(index: Int) {
        if (index !in sentences.indices || sentences[index].status != SentenceStatus.PLAYED) return
        sentences[index].audio = null // release played audio (= iOS audioData = nil, long-call OOM guard)
        committedText = committedTranscriptText
        Log.d(TAG, "committed through #$index")
        onSentenceCommitted?.invoke(committedText)
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        isPlaying = false
    }

    private fun notifyAllPlayedIfNeeded() {
        if (!isFeedingFinished || isPlaying || hasFiredAllPlayed) return
        val statuses = sentences.map { it.status }
        if (!VoiceCallTtsLogic.allResolved(statuses)) return
        hasFiredAllPlayed = true
        if (VoiceCallTtsLogic.shouldReportPlayed(statuses)) {
            Log.d(TAG, "all sentences played")
            onAllSentencesPlayed?.invoke()
        } else {
            Log.d(TAG, "all sentences failed")
            onAllSentencesFailed?.invoke()
        }
    }

    private companion object {
        const val TAG = "VoiceCallTtsPipeline"
    }
}
