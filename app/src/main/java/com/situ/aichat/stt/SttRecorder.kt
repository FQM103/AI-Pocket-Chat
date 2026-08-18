package com.situ.aichat.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Microphone capture for the voice call, 1:1 the call side of iOS audio (`VoiceCallSTT` tap):
 * `AudioRecord` with [MediaRecorder.AudioSource.VOICE_COMMUNICATION] (system AEC/AGC, the reason iOS uses
 * `.voiceChat`), 16 kHz / mono / PCM-16. A background thread reads [SttConstants.FRAME_SAMPLES]-frame
 * chunks, converts to float [-1, 1], computes the [normalizeAudioLevel] level, and pushes both to the
 * caller's callbacks (which fire on the recording thread).
 *
 * [isRecording] is the anti-feedback flag the TTS player reads to refuse playback while capturing
 * (1:1 iOS `AudioRecorderService.isRecording` guard — wired in TtsAudioPlayer, 10.1d 接力).
 *
 * Requires the RECORD_AUDIO runtime permission (declared with the call service in 10.1g); without it the
 * `AudioRecord` init fails and [start] returns false via [onError] rather than crashing.
 *
 * The whole-clip voice-message recorder (iOS `AudioRecorderService`: MIC source, WAV, 60 s) lands with the
 * voice-message UI; the call uses the AEC source, so the two recording sources stay distinct on purpose.
 */
@Singleton
class SttRecorder @Inject constructor() {

    @Volatile
    var isRecording: Boolean = false
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /**
     * Start capturing. [onFrame] receives 16 kHz mono float [-1,1] chunks (feed the [SttStream]); [onLevel]
     * receives the 0..1 audio level; [onError] fires if the recorder can't start. Callbacks run on the
     * recording thread — marshal to the main thread for UI. Returns true if capture started.
     */
    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO before starting a call
    fun start(
        onFrame: (FloatArray) -> Unit,
        onLevel: (Float) -> Unit,
        onError: () -> Unit = {},
    ): Boolean {
        if (isRecording) return true

        val minBuf = AudioRecord.getMinBufferSize(SttConstants.SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize failed ($minBuf)")
            onError()
            return false
        }
        val bufferBytes = maxOf(minBuf, SttConstants.FRAME_SAMPLES * BYTES_PER_SAMPLE * 2)

        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SttConstants.SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord init threw: ${t.message}")
            onError()
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            Log.w(TAG, "AudioRecord not initialized (permission denied or mic busy)")
            onError()
            return false
        }

        record = rec
        isRecording = true
        rec.startRecording()
        Log.i(TAG, "recording started (16k/mono/VOICE_COMMUNICATION)")

        thread = Thread {
            val shortBuf = ShortArray(SttConstants.FRAME_SAMPLES)
            while (isRecording) {
                val n = rec.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                val frame = FloatArray(n) { shortBuf[it] / PCM16_FULL_SCALE }
                onLevel(normalizeAudioLevel(frame))
                onFrame(frame)
            }
        }.apply {
            name = "stt-recorder"
            start()
        }
        return true
    }

    /** Stop capturing and release the mic. Idempotent. */
    fun stop() {
        if (!isRecording && record == null) return
        isRecording = false
        thread?.let { runCatching { it.join(THREAD_JOIN_MS) } }
        thread = null
        record?.let { rec ->
            runCatching { rec.stop() }
            rec.release()
        }
        record = null
        Log.i(TAG, "recording stopped")
    }

    private companion object {
        const val TAG = "SttRecorder"
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
        const val PCM16_FULL_SCALE = 32768f
        const val THREAD_JOIN_MS = 300L
    }
}
