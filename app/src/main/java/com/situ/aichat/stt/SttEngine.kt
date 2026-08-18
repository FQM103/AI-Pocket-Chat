package com.situ.aichat.stt

import java.io.Closeable

/**
 * On-device speech-to-text abstraction. iOS uses on-device `SFSpeechRecognizer`; for China / no-GMS we
 * replace it with a bundled sherpa-onnx model (offline, free, key-less — see voice-strategy). One engine,
 * two reuse paths:
 *  - [transcribe]: whole-utterance recognition of a complete clip — voice messages (= iOS `SpeechRecognitionService`).
 *  - [openStream]: incremental streaming for the voice call (= iOS `VoiceCallSTT`); the caller drives the
 *    silence/endpointing with [SilenceTracker] (mirrors iOS's own 1.2 s silence timer rather than sherpa's
 *    native endpoint detector).
 *
 * Both paths share a single streaming model; "offline" is that same model run over the whole clip.
 */
interface SttEngine {
    /** Whether the model loaded. False → callers gracefully no-op (mirrors the bge embedder degrading to nil). */
    val isAvailable: Boolean

    /**
     * Recognize one complete utterance. [samples] = 16 kHz mono PCM as float in [-1, 1]. Returns the trimmed
     * transcript, or null if the engine is unavailable. CPU-bound; runs off the caller's thread.
     */
    suspend fun transcribe(samples: FloatArray): String?

    /** Open an incremental streaming session, or null if unavailable. Not safe for concurrent sessions. */
    fun openStream(): SttStream?
}

/** One incremental recognition session over a sherpa `OnlineStream`. Feed 16 kHz mono float frames. */
interface SttStream : Closeable {
    /** Append a chunk of 16 kHz mono float [-1, 1] samples and decode whatever is ready. */
    fun accept(samples: FloatArray)

    /** The current best (partial) transcript so far, trimmed. */
    fun decodedText(): String

    /** Drop the current utterance and start a fresh one on the same stream (= sherpa `reset`). */
    fun reset()

    /** Release native resources. */
    override fun close()
}
