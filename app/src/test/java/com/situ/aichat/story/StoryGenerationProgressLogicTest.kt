package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * `StoryGenerationProgressLogic`（11.1f-1）测试：超时与清理时长 / 失败文案优先级 / 章号。
 * 锁定 bug 高发点（章号差一、文案打错）。
 *
 * **进度曲线与阶段文案的断言已随假进度定时器一并退役**（灵动岛卷一·图纸 §7「既有测试预裁决」）：
 * 那批假进度常量（INITIAL_PROGRESS、PLOTTING_START、PLOTTING_CAP、WRITING_CAP、SAVING_PROGRESS、
 * DONE_PROGRESS、STEP、STEP_INTERVAL_MS）与五条 PHASE 文案常量已从被测 object 删除，
 * 真实四段进度的断言迁到 [StoryProgressModelTest]。
 */
class StoryGenerationProgressLogicTest {

    @Test fun timeout_and_clear_delays_match_spec() {
        assertEquals(300_000L, StoryGenerationProgressLogic.GENERATION_TIMEOUT_MS) // iOS :97 = 300s
        // 灵动岛卷一 D-5 拍板：完成满格态停留 500ms → 1500ms（500ms 一闪而过看不清）。
        assertEquals(1_500L, StoryGenerationProgressLogic.PROGRESS_CLEAR_DELAY_MS)
    }

    // ── 卷一 V9：生成全局超时按「勾选思考模型」分档（图纸 §4.12·T1-3·E15）──

    @Test fun generation_timeout_is_600s_for_thinking_and_300s_otherwise() {
        assertEquals(600_000L, StoryGenerationProgressLogic.THINKING_GENERATION_TIMEOUT_MS)
        assertEquals(600_000L, StoryGenerationProgressLogic.generationTimeoutMs(isThinkingModel = true))
        assertEquals(300_000L, StoryGenerationProgressLogic.generationTimeoutMs(isThinkingModel = false))
        // 分档函数的非思考档必须**就是**既有常量（不许另写一个数）
        assertEquals(
            StoryGenerationProgressLogic.GENERATION_TIMEOUT_MS,
            StoryGenerationProgressLogic.generationTimeoutMs(isThinkingModel = false),
        )
    }

    @Test fun timeout_message_no_longer_hardcodes_five_minutes() {
        // 卷一 V9：超时时长已分档，文案里写死「超过 5 分钟」会对思考模型说谎（实为 10 分钟）
        val msg = StoryGenerationError.Timeout.message!!
        assertEquals("章节生成超时，请检查网络或 API 状态后重试。", msg)
        assertFalse(msg.contains("5 分钟"))
        assertFalse(msg.contains("分钟"))
    }

    @Test fun cancelled_message_matches_ios() {
        assertEquals("生成中断，请返回后重试。", StoryGenerationProgressLogic.MESSAGE_CANCELLED)
    }

    // ── nextChapterNumber()：(cachedLatest ?? 0) + 1（iOS :41）──

    @Test fun next_chapter_number_handles_null_and_values() {
        assertEquals(1, StoryGenerationProgressLogic.nextChapterNumber(null)) // 首章
        assertEquals(1, StoryGenerationProgressLogic.nextChapterNumber(0))
        assertEquals(6, StoryGenerationProgressLogic.nextChapterNumber(5))
    }

    // ── failureMessage()：超时 > 取消 > 其它（iOS catch :135-142）──

    @Test fun failure_message_timeout_wins() {
        val msg = StoryGenerationProgressLogic.failureMessage(
            cause = RuntimeException("ignored"),
            isTimeout = true,
            timeoutMessage = "超时啦",
        )
        assertEquals("超时啦", msg)
    }

    @Test fun failure_message_cancellation_maps_to_interrupted_text() {
        val msg = StoryGenerationProgressLogic.failureMessage(
            cause = CancellationException("cancelled"),
            isTimeout = false,
            timeoutMessage = "",
        )
        assertEquals("生成中断，请返回后重试。", msg)
    }

    @Test fun failure_message_other_uses_throwable_message() {
        val msg = StoryGenerationProgressLogic.failureMessage(
            cause = RuntimeException("网络连接失败"),
            isTimeout = false,
            timeoutMessage = "",
        )
        assertEquals("网络连接失败", msg)
    }

    @Test fun failure_message_blank_message_falls_back() {
        val msg = StoryGenerationProgressLogic.failureMessage(
            cause = RuntimeException("   "),
            isTimeout = false,
            timeoutMessage = "",
        )
        assertEquals("章节生成失败，请稍后重试。", msg)
    }
}
