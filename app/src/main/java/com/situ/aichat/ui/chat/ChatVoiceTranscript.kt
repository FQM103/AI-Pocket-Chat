package com.situ.aichat.ui.chat

import com.situ.aichat.prompt.PromptBuilder

/*
 * 语音消息草稿 / STT 转写相关类型与纯函数——从 ChatViewModel.kt 抽出，声明体字节级不变。
 * 与 ChatViewModel 同包，故所有引用（VM / UI / 测试）无需改 import。
 * 测试：VoiceTranscriptResultTest。
 */

/**
 * 录好待发的语音消息草稿（1:1 iOS `VoiceDraftState`）：已落盘的 WAV 路径 + 时长 + 后台 STT 转写。
 * [transcript] 起始为占位 `[语音消息]`，STT 完成/失败后更新；[isTranscriptPending] 为转写进行中标志（草稿条副标题）。
 * [id] 是与任何 message UUID 不同的瞬态预览 id（喂 TtsAudioPlayer 试听）。
 */
data class VoiceDraftState(
    val id: String,
    val audioPath: String,
    val durationSec: Double,
    val transcript: String,
    val isTranscriptPending: Boolean,
    val transcriptFailure: VoiceTranscriptFailure? = null,
)

/**
 * 转写失败粒度（P1-41，错误命名参照 iOS SpeechRecognitionService.SpeechError:181-185 的
 * notAvailable/timedOut；EMPTY 为 sherpa 空串安卓特有；iOS notAuthorized 在安卓端侧 STT 无对应权限概念，不设）。
 * UNAVAILABLE = 引擎模型加载失败（SherpaSttEngine.loadFailed 进程内粘滞），重试必败 → UI 不给重试钮；
 * EMPTY/TIMEOUT 纯本地免费可任意重试（P1-42「重新识别」）。
 */
enum class VoiceTranscriptFailure { UNAVAILABLE, EMPTY, TIMEOUT }

internal sealed interface VoiceTranscriptResult {
    data class Success(val text: String) : VoiceTranscriptResult
    data class Failure(val kind: VoiceTranscriptFailure) : VoiceTranscriptResult
}

/**
 * 分流纯函数（P1-41）：[available]=调用前 isAvailable；[engineReturnedNull]=超时窗内 transcribe
 * 返回 null（引擎自查不可用的竞态防御 → 视同不可用）；[timedOut]=withTimeoutOrNull 超时；[text]=引擎产出。
 */
internal fun classifyTranscriptOutcome(
    available: Boolean,
    engineReturnedNull: Boolean,
    timedOut: Boolean,
    text: String?,
): VoiceTranscriptResult = when {
    !available || engineReturnedNull -> VoiceTranscriptResult.Failure(VoiceTranscriptFailure.UNAVAILABLE)
    timedOut -> VoiceTranscriptResult.Failure(VoiceTranscriptFailure.TIMEOUT)
    text.isNullOrBlank() -> VoiceTranscriptResult.Failure(VoiceTranscriptFailure.EMPTY)
    else -> VoiceTranscriptResult.Success(text)
}

/** 转写结果落草稿纯函数：成功→刷 transcript 清失败；失败→恢复占位+记失败种类；两路均复位 pending。 */
internal fun VoiceDraftState.applyingTranscriptResult(result: VoiceTranscriptResult): VoiceDraftState = when (result) {
    is VoiceTranscriptResult.Success ->
        copy(transcript = result.text, isTranscriptPending = false, transcriptFailure = null)
    is VoiceTranscriptResult.Failure ->
        copy(transcript = PromptBuilder.VOICE_MESSAGE_PLACEHOLDER, isTranscriptPending = false, transcriptFailure = result.kind)
}
