package com.situ.aichat.voice

import android.os.SystemClock
import android.util.Log
import com.situ.aichat.stt.SilenceTracker
import com.situ.aichat.stt.SttConstants
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.SttRecorder
import com.situ.aichat.stt.SttStream
import com.situ.aichat.stt.isBackchannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The voice-call microphone + on-device recognition front-end — the Android port of iOS `VoiceCallSTT`,
 * built on the 10.1d primitives ([SttRecorder] 16 kHz/mono/VOICE_COMMUNICATION mic, [SttEngine] streaming
 * sherpa recognizer, [SilenceTracker] 1.2 s endpointer, [isBackchannel] 附和词 filter). It has two modes,
 * both half-duplex (one mic, one purpose at a time):
 *
 *  - **Listening** ([startListening]): recognize the user's turn. The first non-empty partial fires
 *    [onVoiceDetected] (→ userSpeaking); when the partial then stays unchanged for 1.2 s the turn ends and
 *    [onFinalResult] delivers the text (= iOS `onVoiceDetected` / `onFinalResult`).
 *  - **Monitoring** ([startMonitoring]): barge-in detection while the AI speaks. Energy is always exposed
 *    via [audioLevel] (the controller runs the energy [com.situ.aichat.stt.BargeInDetector] on it). In
 *    earpiece mode `withRecognition=true` additionally recognizes speech and fires [onSpeechDetected] once
 *    the text is non-backchannel and has lasted ≥ 0.3 s (= iOS `startMonitoringWithRecognition`,
 *    VoiceCallSTT.swift:194-228). Speaker mode stays energy-only, 1:1 iOS (`.default` has no AEC there;
 *    on Android `MODE_IN_COMMUNICATION` does add AEC even on speaker, so recognition *could* be enabled
 *    later — kept 1:1 iOS for now, to validate on device in 10.1g/h).
 *
 * Mic frames arrive on the recorder thread and are handed to a single processing coroutine via a
 * drop-oldest [Channel], so the (CPU-bound) sherpa decode never stalls capture and all [SilenceTracker] /
 * recognition state stays confined to one coroutine (no locks). Callbacks are dispatched as independent
 * main-thread launches, so a callback that tears the session down (stopListening) never cancels the very
 * coroutine that fired it. Framework-bound → compile + Logcat verified; the downstream pure logic is unit-tested.
 */
internal class VoiceCallStt(
    private val engine: SttEngine,
    private val recorder: SttRecorder,
    private val scope: CoroutineScope,
    private val timeSource: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private enum class Mode { IDLE, LISTENING, MONITORING }

    @Volatile
    var audioLevel: Float = 0f
        private set

    /** Text recognized during barge-in monitoring; the controller prepends it to the next turn
     *  (= iOS `monitoringRecognizedText` → `interruptionPrefixText`). Cleared by [reset]. */
    @Volatile
    var monitoringRecognizedText: String = ""
        private set

    @Volatile
    private var mode = Mode.IDLE
    private var stream: SttStream? = null
    private var frames: Channel<FloatArray>? = null
    private var processJob: Job? = null

    /** Start listening for the user's turn. Returns false (and cleans up) if the mic won't start. */
    fun startListening(onVoiceDetected: () -> Unit, onFinalResult: (String) -> Unit): Boolean {
        stopCapture()
        val sttStream = engine.openStream() // null when the model failed to load → graceful no-op
        stream = sttStream
        mode = Mode.LISTENING

        val tracker = SilenceTracker()
        val channel = openFrameChannel()
        processJob = scope.launch(Dispatchers.Default) {
            for (frame in channel) {
                if (!isActive || mode != Mode.LISTENING) break
                sttStream?.accept(frame)
                val now = timeSource()
                val text = sttStream?.decodedText().orEmpty()
                if (tracker.onText(text, now)) {
                    scope.launch(Dispatchers.Main) { onVoiceDetected() }
                }
                if (tracker.isFinished(now)) {
                    val finalText = tracker.text()
                    scope.launch(Dispatchers.Main) { onFinalResult(finalText) }
                    break
                }
            }
        }

        return startRecorder()
    }

    /**
     * Start barge-in monitoring. [withRecognition] adds the recognition trigger (earpiece); otherwise the
     * mic runs for energy only. [onSpeechDetected] fires at most once per monitoring session.
     */
    fun startMonitoring(withRecognition: Boolean, onSpeechDetected: () -> Unit): Boolean {
        stopCapture()
        monitoringRecognizedText = ""
        mode = Mode.MONITORING

        if (withRecognition) {
            val sttStream = engine.openStream()
            if (sttStream == null) {
                mode = Mode.IDLE // no recognizer → caller falls back to energy-only monitoring
                return false
            }
            stream = sttStream
            val channel = openFrameChannel()
            processJob = scope.launch(Dispatchers.Default) {
                var speechStartMs = 0L
                var triggered = false
                for (frame in channel) {
                    if (!isActive || mode != Mode.MONITORING) break
                    sttStream.accept(frame)
                    val text = sttStream.decodedText()
                    if (text.isEmpty()) continue
                    monitoringRecognizedText = text
                    if (speechStartMs == 0L) speechStartMs = timeSource()
                    if (triggered) continue
                    val hasMinDuration = timeSource() - speechStartMs >= SttConstants.MONITORING_MIN_SPEECH_MS
                    if (!isBackchannel(text) && hasMinDuration) {
                        triggered = true
                        scope.launch(Dispatchers.Main) { onSpeechDetected() }
                    }
                }
            }
        }

        return startRecorder()
    }

    /** Stop listening (= iOS `stopListening`). */
    fun stopListening() {
        stopCapture()
    }

    /** Stop monitoring; [monitoringRecognizedText] survives until [reset] so the controller can read it. */
    fun stopMonitoring() {
        stopCapture()
    }

    /** Clear the carried-over monitoring text (= iOS `reset` clearing `monitoringRecognizedText`). */
    fun reset() {
        monitoringRecognizedText = ""
    }

    private fun openFrameChannel(): Channel<FloatArray> {
        // Drop-oldest so a slow decode never blocks the mic thread (it can't suspend).
        val channel = Channel<FloatArray>(capacity = FRAME_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        frames = channel
        return channel
    }

    private fun startRecorder(): Boolean {
        val started = recorder.start(
            onFrame = { frame -> frames?.trySend(frame) },
            onLevel = { level -> audioLevel = level },
            onError = { Log.w(TAG, "recorder failed to start") },
        )
        if (!started) stopCapture()
        return started
    }

    /** Stop the mic + processing for the current session. */
    private fun stopCapture() {
        mode = Mode.IDLE
        recorder.stop()
        frames?.close()
        frames = null
        processJob?.cancel()
        processJob = null
        stream?.close()
        stream = null
        audioLevel = 0f
    }

    private companion object {
        const val TAG = "VoiceCallStt"
        const val FRAME_BUFFER = 64
    }
}
