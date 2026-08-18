package com.situ.aichat.ui.chat

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.stt.RecordedVoiceClip
import com.situ.aichat.stt.SttConstants
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.VoiceMessageRecorder
import com.situ.aichat.stt.decodeWavPcm16ToFloat
import com.situ.aichat.stt.encodeWavPcm16
import com.situ.aichat.stt.pcm16ToFloat
import com.situ.aichat.tts.TtsAudioPlayer
import com.situ.aichat.util.AudioStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 语音消息「录音 → 草稿 → 端侧 STT 转写」状态机协作者——从 ChatViewModel 抽出（对齐 iOS ChatViewModel+VoiceRecord），方法体字节级不变。
 * 只管录制态与草稿态：start/drag/finish/cancel 录音、建草稿、后台转写、重试、试听、取消。
 * **发送链有意留在 VM**（`sendVoiceDraft`/`resolveTranscriptForSend`/`sendVoiceMessage` 与助手回合引擎强耦合）——
 * VM 经 [voiceDraft] 读草稿、[consumeDraftOnSend] 发送时清草稿、[disposeOnCleared] 退会话时清理。
 * [scope] = VM 的 viewModelScope；[errorFlow]/[infoToastFlow] = VM 的 _error/_infoToast（统一弹错/toast，与日历协作者同款注入）。
 */
internal class ChatVoiceController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val errorFlow: MutableStateFlow<String?>,
    private val infoToastFlow: MutableStateFlow<String?>,
    private val voiceRecorder: VoiceMessageRecorder,
    private val sttEngine: SttEngine,
    private val ttsAudioPlayer: TtsAudioPlayer,
) {
    /** 点语音气泡：正在放这条 → 停；否则播放已存音频（不重合成，对齐 iOS VoiceMessageBubble.togglePlayback）。审计 S3 自 VM 搬入。 */
    fun toggleVoicePlayback(message: MessageEntity) {
        val path = message.audioRelativePath ?: return
        val state = ttsAudioPlayer.state.value
        if (state.playingId == message.messageUUID && state.isPlaying) {
            ttsAudioPlayer.stop()
        } else {
            ttsAudioPlayer.play(message.messageUUID, path)
        }
    }

    /** 录音器实时状态（直接转发，供录音浮层：是否在录 / 计时 / 波形电平）。 */
    val voiceRecording: StateFlow<Boolean> = voiceRecorder.isRecording
    val voiceRecordingDurationMs: StateFlow<Long> = voiceRecorder.durationMs
    val voiceRecordingLevel: StateFlow<Float> = voiceRecorder.level

    /** 录音中是否已上滑越过取消阈值（松手将丢弃，1:1 iOS recordingCancelled）。 */
    private val _voiceRecordingCancelling = MutableStateFlow(false)
    val voiceRecordingCancelling: StateFlow<Boolean> = _voiceRecordingCancelling.asStateFlow()

    /** 录好待发的语音草稿（松手后出现；点发清空）。 */
    private val _voiceDraft = MutableStateFlow<VoiceDraftState?>(null)
    val voiceDraft: StateFlow<VoiceDraftState?> = _voiceDraft.asStateFlow()

    /** 草稿后台转写 job（草稿创建即启动；取消/发送时清理）。 */
    private var voiceTranscriptionJob: Job? = null

    /** 开始录音：先停 TTS 试听/播放，再启麦克风。失败（无权限/麦被占）→ 报错。 */
    fun startVoiceRecording() {
        if (_voiceDraft.value != null) return // 已有草稿待处理时不重复录
        ttsAudioPlayer.stop()
        _voiceRecordingCancelling.value = false
        if (!voiceRecorder.start(onMaxReached = { finishVoiceRecording() })) {
            errorFlow.value = appContext.getString(R.string.voice_record_failed)
        }
    }

    /** 录音中手指上滑位移（dp，由 UI 换算）：越过阈值置「将取消」（1:1 iOS updateRecordingDrag > 80pt）。 */
    fun updateVoiceRecordingDrag(draggedUpDp: Float) {
        if (!voiceRecorder.isRecording.value) return
        _voiceRecordingCancelling.value = draggedUpDp > VOICE_CANCEL_DRAG_DP
    }

    /** 松手 / 录满 60s 收尾：取消态或太短（<0.3s）→ 丢弃；否则建草稿。幂等（松手 + 60s 回调可能都触发）。 */
    fun finishVoiceRecording() {
        if (!voiceRecorder.isRecording.value) return
        val cancelling = _voiceRecordingCancelling.value
        val clip = voiceRecorder.stop()
        _voiceRecordingCancelling.value = false
        if (cancelling || clip == null) return
        if (clip.durationSec < VOICE_MIN_DURATION_SEC) {
            infoToastFlow.value = appContext.getString(R.string.voice_too_short)
            return
        }
        createVoiceDraft(clip)
    }

    /** 录音被外部打断（离开页面等）：停录、丢草稿、清状态。 */
    fun cancelVoiceRecordingIfActive() {
        if (voiceRecorder.isRecording.value) {
            voiceRecorder.cancel()
            _voiceRecordingCancelling.value = false
        }
    }

    private fun createVoiceDraft(clip: RecordedVoiceClip) {
        scope.launch {
            val wavBytes = encodeWavPcm16(clip.samples, SttConstants.SAMPLE_RATE)
            val path = AudioStore.saveBytes(appContext, wavBytes, "wav")
            if (path == null) {
                errorFlow.value = appContext.getString(R.string.voice_record_failed)
                return@launch
            }
            // 落盘期间草稿被取消/页面销毁（onCleared 删的是当时的 _voiceDraft，此刻可能尚未设上）→ 删掉刚写的文件防泄漏。
            if (!isActive) {
                AudioStore.delete(path)
                return@launch
            }
            val draftId = "voice_draft_${UUID.randomUUID()}"
            _voiceDraft.value = VoiceDraftState(
                id = draftId,
                audioPath = path,
                durationSec = clip.durationSec,
                transcript = PromptBuilder.VOICE_MESSAGE_PLACEHOLDER,
                isTranscriptPending = true,
            )
            // 草稿创建即启动后台 STT（1:1 iOS createVoiceDraft：转写不阻塞试听/发送决策）。
            // Default 调度（批7 复核修）：isAvailable 首调会同步加载 sherpa 模型（百 ms~秒级）+ pcm16ToFloat
            // 大数组转换，不能占主线程；_voiceDraft.update 为 CAS 线程安全、draftId 守卫跨线程成立。
            voiceTranscriptionJob = launch(Dispatchers.Default) {
                val result = transcribeClipResult(pcm16ToFloat(clip.samples))
                _voiceDraft.update { cur ->
                    if (cur?.id == draftId) cur.applyingTranscriptResult(result) else cur
                }
            }
        }
    }

    /** 端侧整段 STT（15s 内部超时；P1-41 失败分流 UNAVAILABLE/EMPTY/TIMEOUT，落草稿见 [applyingTranscriptResult]）。 */
    private suspend fun transcribeClipResult(samples: FloatArray): VoiceTranscriptResult {
        if (!sttEngine.isAvailable) {
            val outcome = classifyTranscriptOutcome(false, false, false, null)
            Log.w(TAG, "语音消息转写·结果=${transcriptOutcomeLabel(outcome)}")
            return outcome
        }
        var engineNull = false
        val text = withTimeoutOrNull(STT_INTERNAL_TIMEOUT_MS) {
            sttEngine.transcribe(samples).also { if (it == null) engineNull = true }
        }
        val outcome = classifyTranscriptOutcome(true, engineNull, timedOut = text == null && !engineNull, text = text)
        Log.w(TAG, "语音消息转写·结果=${transcriptOutcomeLabel(outcome)}")
        return outcome
    }

    /** STT 结果分类的日志标签（仅输出结果枚举·成功只记 SUCCESS·绝不输出转写文本）。 */
    private fun transcriptOutcomeLabel(result: VoiceTranscriptResult): String = when (result) {
        is VoiceTranscriptResult.Success -> "SUCCESS"
        is VoiceTranscriptResult.Failure -> result.kind.name
    }

    /**
     * 草稿「重新识别」（P1-42）：读已落盘 WAV 重跑端侧 STT。纯本地免费可任意重试；
     * UI 仅在 EMPTY/TIMEOUT 提供入口（UNAVAILABLE=模型加载失败粘滞，重试必败）。
     * 重试 job 赋给 [voiceTranscriptionJob]——cancelVoiceDraft/sendVoiceDraft 的既有取消清理即自动覆盖。
     */
    fun retryVoiceTranscription() {
        val draft = _voiceDraft.value ?: return
        if (draft.isTranscriptPending || draft.transcriptFailure == null) return
        _voiceDraft.update { cur ->
            if (cur?.id == draft.id) {
                cur.copy(isTranscriptPending = true, transcriptFailure = null, transcript = PromptBuilder.VOICE_MESSAGE_PLACEHOLDER)
            } else {
                cur
            }
        }
        voiceTranscriptionJob?.cancel()
        voiceTranscriptionJob = scope.launch(Dispatchers.Default) { // 同 createVoiceDraft：模型加载/解码不占主线程
            val samples = AudioStore.load(draft.audioPath)?.let { decodeWavPcm16ToFloat(it) }
            // 文件丢失/损坏 → 按 EMPTY 收尾（副标题引导重录；再点重试无害），不映射 UNAVAILABLE（会误导「设备不行」且失去重试钮）。
            val result = if (samples == null) {
                VoiceTranscriptResult.Failure(VoiceTranscriptFailure.EMPTY)
            } else {
                transcribeClipResult(samples)
            }
            _voiceDraft.update { cur ->
                if (cur?.id == draft.id) cur.applyingTranscriptResult(result) else cur
            }
        }
    }

    /** 草稿试听：播放/停止（与气泡共用 [ttsAudioPlayer]；isPlaying 由 UI 从 [playbackState] 按 draft.id 派生）。 */
    fun toggleVoiceDraftPlayback() {
        val draft = _voiceDraft.value ?: return
        val state = ttsAudioPlayer.state.value
        if (state.playingId == draft.id && state.isPlaying) {
            ttsAudioPlayer.stop()
        } else {
            ttsAudioPlayer.play(draft.id, draft.audioPath)
        }
    }

    /** 取消草稿：停转写、停试听、删音频文件、清草稿。 */
    fun cancelVoiceDraft() {
        voiceTranscriptionJob?.cancel()
        voiceTranscriptionJob = null
        val draft = _voiceDraft.value
        if (draft != null) {
            if (ttsAudioPlayer.state.value.playingId == draft.id) ttsAudioPlayer.stop()
            AudioStore.delete(draft.audioPath)
        }
        _voiceDraft.value = null
    }

    /**
     * 发送通过门控后清草稿（搬自 VM sendVoiceDraft 尾段·字节级不变）：停本草稿试听、停转写、清草稿。
     * 发送链其余（落库 + 起回合）留在 VM（与助手回合引擎强耦合）。
     */
    fun consumeDraftOnSend(draft: VoiceDraftState) {
        if (ttsAudioPlayer.state.value.playingId == draft.id) ttsAudioPlayer.stop()
        voiceTranscriptionJob?.cancel()
        voiceTranscriptionJob = null
        _voiceDraft.value = null
    }

    /**
     * VM onCleared 时调用（搬自 VM onCleared 语音段·字节级不变）：停转写 job、停录音、删未发草稿音频防泄漏。
     * 录音器是 @Singleton，VM 销毁不会自动停。
     */
    fun disposeOnCleared() {
        voiceTranscriptionJob?.cancel()
        cancelVoiceRecordingIfActive()
        _voiceDraft.value?.let { AudioStore.delete(it.audioPath) }
    }

    private companion object {
        const val TAG = "ChatVoiceController"

        // P13.4b 语音消息常量（1:1 iOS）。
        /** 上滑取消阈值（dp，1:1 iOS updateRecordingDrag 的 80pt）。 */
        const val VOICE_CANCEL_DRAG_DP = 80f

        /** 录音太短下限（秒，1:1 iOS finishVoiceRecording 的 0.3s）。 */
        const val VOICE_MIN_DURATION_SEC = 0.3

        /** 端侧 STT 内部超时（毫秒，1:1 iOS SpeechRecognitionService 的 15s）。 */
        const val STT_INTERNAL_TIMEOUT_MS = 15_000L
    }
}
