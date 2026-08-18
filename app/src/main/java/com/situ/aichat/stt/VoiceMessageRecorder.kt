package com.situ.aichat.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 录到的整段语音：PCM-16 mono 16 kHz 样本 + 时长秒。调用方负责 <0.3s 太短判定 + PCM16→WAV 编码。 */
class RecordedVoiceClip(val samples: ShortArray, val durationSec: Double)

/**
 * 聊天「语音消息」整段录音器（1:1 iOS `AudioRecorderService`）：[MediaRecorder.AudioSource.MIC]（纯麦克风——
 * **不**复用 [SttRecorder] 的 VOICE_COMMUNICATION 回声消除源，那是通话用，iOS 同样把两路录音源刻意分开）、
 * 16 kHz / mono / PCM-16，累积整片、60 s 自动停。后台线程读帧累积，并更新 [durationMs] / [level] StateFlow
 * 供录音浮层（波形 + 计时）。[stop] 返回整段样本（含时长）。
 *
 * 需 RECORD_AUDIO 运行时权限（调用方先申请）；麦被占（如通话中）→ AudioRecord init 失败 → [start] 返回 false，
 * 而非崩溃（等价 iOS hasActiveCall 守卫的效果）。
 */
@Singleton
class VoiceMessageRecorder @Inject constructor() {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** 归一化电平 0..1（波形条用，来自 [normalizeAudioLevel]）。 */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var capturing = false
    private val collected = ArrayList<ShortArray>()
    private var collectedSamples = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 开始录音。[onMaxReached] 在录满 60 s 时（主线程）回调，调用方据此触发松手收尾。返回 true=已开始。
     * 麦不可用/无权限/被占 → 返回 false（不抛）。
     */
    @SuppressLint("MissingPermission") // 调用方确保 RECORD_AUDIO 已授权
    fun start(onMaxReached: () -> Unit): Boolean {
        if (capturing) return true

        val minBuf = AudioRecord.getMinBufferSize(SttConstants.SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize failed ($minBuf)")
            return false
        }
        val bufferBytes = maxOf(minBuf, SttConstants.FRAME_SAMPLES * BYTES_PER_SAMPLE * 2)

        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SttConstants.SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord init threw: ${t.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            Log.w(TAG, "AudioRecord not initialized (permission denied or mic busy)")
            return false
        }

        record = rec
        synchronized(collected) { collected.clear(); collectedSamples = 0 }
        _durationMs.value = 0L
        _level.value = 0f
        capturing = true
        _isRecording.value = true
        rec.startRecording()
        Log.i(TAG, "voice-message recording started (16k/mono/MIC)")

        var notifiedMax = false
        thread = Thread {
            val buf = ShortArray(SttConstants.FRAME_SAMPLES)
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                synchronized(collected) {
                    collected.add(ShortArray(n) { buf[it] })
                    collectedSamples += n
                }
                _durationMs.value = collectedSamples.toLong() * 1000L / SttConstants.SAMPLE_RATE
                _level.value = normalizeAudioLevel(FloatArray(n) { buf[it] / PCM16_FULL_SCALE })
                if (!notifiedMax && collectedSamples >= MAX_SAMPLES) {
                    notifiedMax = true
                    capturing = false
                    mainHandler.post(onMaxReached)
                }
            }
        }.apply {
            name = "voice-msg-recorder"
            start()
        }
        return true
    }

    /** 停止并返回整段样本（含时长秒）。无样本返回 null。幂等。 */
    fun stop(): RecordedVoiceClip? {
        if (!capturing && record == null) return null
        teardown()
        val samples = drainCollected()
        _durationMs.value = 0L
        _level.value = 0f
        if (samples.isEmpty()) return null
        return RecordedVoiceClip(samples, samples.size.toDouble() / SttConstants.SAMPLE_RATE)
    }

    /** 取消录音、丢弃缓冲。幂等。 */
    fun cancel() {
        if (!capturing && record == null) return
        teardown()
        drainCollected()
        _durationMs.value = 0L
        _level.value = 0f
    }

    private fun teardown() {
        capturing = false
        thread?.let { runCatching { it.join(THREAD_JOIN_MS) } }
        thread = null
        record?.let { rec ->
            runCatching { rec.stop() }
            rec.release()
        }
        record = null
        _isRecording.value = false
        Log.i(TAG, "voice-message recording stopped")
    }

    private fun drainCollected(): ShortArray = synchronized(collected) {
        val out = ShortArray(collectedSamples)
        var pos = 0
        for (frame in collected) {
            System.arraycopy(frame, 0, out, pos, frame.size)
            pos += frame.size
        }
        collected.clear()
        collectedSamples = 0
        out
    }

    private companion object {
        const val TAG = "VoiceMsgRecorder"
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
        const val PCM16_FULL_SCALE = 32768f
        const val THREAD_JOIN_MS = 300L

        /** 60 s 上限（1:1 iOS AudioRecorderService.maxRecordingDuration）= 60 * 16000 样本。 */
        const val MAX_SAMPLES = 60 * SttConstants.SAMPLE_RATE
    }
}
