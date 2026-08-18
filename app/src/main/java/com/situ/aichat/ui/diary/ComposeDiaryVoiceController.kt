package com.situ.aichat.ui.diary

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.VoiceMessageRecorder
import com.situ.aichat.stt.pcm16ToFloat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 日记「说一段」语音落笔状态机协作者（J6·图纸 §4-J6）——从 [ComposeDiaryViewModel] 抽出（VM 越 400 行·图纸 §2 预案）。
 * **松手落笔**：按住录 → 松手 → 转写中 → 端侧整段转写文本**追加**正文末尾（永不覆盖）。复用聊天同一条已验证管线
 * （[VoiceMessageRecorder] + [SttEngine]·sherpa-onnx·非流式），但**不落 WAV、不建草稿、无试听/重试**（与聊天分道之处·有意）。
 *
 * [scope] = VM 的 viewModelScope；[currentContent] 追加时读**最新**正文（勿闭包旧值·转写中用户手打字也正确拼接）；
 * [setContent] 落最终正文（经 VM 走 J1 SavedStateHandle 镜像）；[emitMessage] 发一次性 snackbar/toast 文案。
 * [transcribeDispatcher] 默认 [Dispatchers.Default]（模型加载 + pcm 转换不占主线程）·测试注 Unconfined 求确定性。
 */
internal class ComposeDiaryVoiceController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val voiceRecorder: VoiceMessageRecorder,
    private val sttEngine: SttEngine,
    private val currentContent: () -> String,
    private val setContent: (String) -> Unit,
    private val emitMessage: (String) -> Unit,
    private val transcribeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** 录音器实时三态直转（供录音浮层：是否在录 / 电平 / 计时）。 */
    val voiceRecording: StateFlow<Boolean> = voiceRecorder.isRecording
    val voiceLevel: StateFlow<Float> = voiceRecorder.level
    val voiceDurationMs: StateFlow<Long> = voiceRecorder.durationMs

    /** 录音中已上滑越阈值（松手将丢弃·1:1 聊天 recordingCancelling）。 */
    private val _voiceCancelling = MutableStateFlow(false)
    val voiceCancelling: StateFlow<Boolean> = _voiceCancelling.asStateFlow()

    /** 转写中（松手后→追加前·纸面下方「正在落笔…」）。 */
    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    /** 按住开始录音；失败（无权限/麦被占）→ snackbar（复用现键）。 */
    fun startVoice() {
        _voiceCancelling.value = false
        if (!voiceRecorder.start(onMaxReached = { finishVoice() })) {
            emitMessage(appContext.getString(R.string.voice_record_failed))
        }
    }

    /** 录音中手指上滑位移（dp·UI 换算）：越阈值置「将取消」（与聊天同值 80f·[com.situ.aichat.ui.chat.ChatVoiceController] :244）。 */
    fun updateVoiceDrag(draggedUpDp: Float) {
        if (!voiceRecorder.isRecording.value) return
        _voiceCancelling.value = draggedUpDp > VOICE_CANCEL_DRAG_DP
    }

    /** 松手 / 录满 60s 收尾（幂等守卫同聊天）：取消态或太短(<0.3s)→ 丢弃；否则转写中→端侧整段转写→成文追加正文。 */
    fun finishVoice() {
        if (!voiceRecorder.isRecording.value) return
        val cancelling = _voiceCancelling.value
        val clip = voiceRecorder.stop()
        _voiceCancelling.value = false
        if (cancelling || clip == null) return
        if (clip.durationSec < VOICE_MIN_DURATION_SEC) {
            emitMessage(appContext.getString(R.string.voice_too_short))
            return
        }
        _isTranscribing.value = true
        scope.launch(transcribeDispatcher) {
            val transcript = transcribe(pcm16ToFloat(clip.samples))
            if (transcript != null) {
                // 追加永不覆盖：读最新正文（转写中手打字也正确拼接）·经 setContent 走 J1 镜像。
                val cur = currentContent()
                setContent(if (cur.isEmpty()) transcript else cur + "\n" + transcript)
            }
            _isTranscribing.value = false
        }
    }

    /** 端侧整段 STT（15s 超时）；失败三态 → snackbar：不可用 / 空·超时（并入 empty）。成功返回转写文，否则 null。 */
    private suspend fun transcribe(samples: FloatArray): String? {
        if (!sttEngine.isAvailable) {
            Log.w(TAG, "说一段转写·结果=UNAVAILABLE")
            emitMessage(appContext.getString(R.string.diary_voice_unavailable))
            return null
        }
        val text = withTimeoutOrNull(STT_INTERNAL_TIMEOUT_MS) { sttEngine.transcribe(samples) }
        if (text.isNullOrBlank()) { // 超时(null) / 引擎空 → 并入 empty
            Log.w(TAG, "说一段转写·结果=EMPTY")
            emitMessage(appContext.getString(R.string.diary_voice_empty))
            return null
        }
        return text
    }

    /** VM onCleared：若在录必须停（recorder 是 @Singleton·VM 销毁不会自动停）。 */
    fun onCleared() {
        if (voiceRecorder.isRecording.value) voiceRecorder.cancel()
    }

    private companion object {
        const val TAG = "DiaryVoiceCtrl"

        /** 上滑取消阈值（dp·与聊天同值 80·ChatVoiceController.VOICE_CANCEL_DRAG_DP）。 */
        const val VOICE_CANCEL_DRAG_DP = 80f

        /** 录音太短下限（秒·与聊天同值 0.3）。 */
        const val VOICE_MIN_DURATION_SEC = 0.3

        /** 端侧 STT 内部超时（毫秒·与聊天同值 15s）。 */
        const val STT_INTERNAL_TIMEOUT_MS = 15_000L
    }
}
