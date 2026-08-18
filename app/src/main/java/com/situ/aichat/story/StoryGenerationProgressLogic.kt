package com.situ.aichat.story

import kotlin.coroutines.cancellation.CancellationException

/**
 * 故事生成任务管理器的纯逻辑常量（超时 / 清理延迟 / 错误文案 / 章号）。
 *
 * 抽成独立 internal object 便于单测锁定魔法值（超时 / 章号差一 / 中文文案打错都是 bug 高发点）。
 * 编排部分（协程 / StateFlow / DB / LLM）留在 [StoryGenerationTaskManager]。
 *
 * **进度曲线与阶段文案已迁出**：假进度定时器（0.15→0.45→0.65 每 2 秒 +0.02）于灵动岛卷一退役，
 * 换成真实事件驱动的四段模型——口径唯一收口在 [StoryProgressModel]，阶段枚举见 [StoryGenPhase]。
 */
internal object StoryGenerationProgressLogic {

    // ── 错误文案（1:1 iOS catch :138-141）──
    /** 用户主动取消 / 后台保活到期取消（iOS `error is CancellationError`）。 */
    const val MESSAGE_CANCELLED = "生成中断，请返回后重试。"
    /** 其它错误无 message 时兜底（iOS `error.localizedDescription` 恒非空；安卓 `Throwable.message` 可空，需兜底）。 */
    const val MESSAGE_GENERIC_FAILURE = "章节生成失败，请稍后重试。"

    // ── 看门狗 / 清理（1:1 iOS :97 超时 / :119 延迟清理）──
    /** 生成全局超时（iOS `Task.sleep(for: .seconds(300))`）——非思考模型档。 */
    const val GENERATION_TIMEOUT_MS = 300_000L

    /** 思考模型生成全局超时（卷一 V9）：思考+长章+编务兜底全链余量；非思考维持 300s。 */
    const val THINKING_GENERATION_TIMEOUT_MS = 600_000L

    /** 生成全局超时按创作配置分档（勾选「思考模型」= 唯一裁决，与温度保险丝同源）。 */
    fun generationTimeoutMs(isThinkingModel: Boolean): Long =
        if (isThinkingModel) THINKING_GENERATION_TIMEOUT_MS else GENERATION_TIMEOUT_MS
    /**
     * 完成后停留「第 N 章写好了」满格态的时长，到点才清进度态。
     * 灵动岛卷一由 500ms 提到 1500ms（D-5 拍板）：500ms 一闪而过，用户根本看不清药丸的完成帧。
     */
    const val PROGRESS_CLEAR_DELAY_MS = 1_500L

    /** 本次生成的章号 = 缓存最新章号 ?? 0，再 +1（1:1 iOS `(cachedLatestChapterNumber ?? 0) + 1`）。 */
    fun nextChapterNumber(cachedLatestChapterNumber: Int?): Int = (cachedLatestChapterNumber ?: 0) + 1

    /**
     * 失败错误文案（1:1 iOS catch 分支 :135-142 的优先级）：超时 > 取消 > 其它（取 message，空白则兜底）。
     * @param timeoutMessage 注入 [StoryGenerationError.Timeout] 的 message（错误定义集中持有，不在此重复）。
     */
    fun failureMessage(cause: Throwable, isTimeout: Boolean, timeoutMessage: String): String = when {
        isTimeout -> timeoutMessage
        cause is CancellationException -> MESSAGE_CANCELLED
        else -> cause.message?.takeIf { it.isNotBlank() } ?: MESSAGE_GENERIC_FAILURE
    }
}
