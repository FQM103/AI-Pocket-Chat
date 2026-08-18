package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `StoryGenerationPolicy` 的 11.1e-8 主编排派生逻辑测试：
 * - [effectiveChapterLength]（请求结局 ×1.5 向零截断）
 * - [decideContinue]（completed→serializing / paused→serializing / 其它不变）
 *
 * **卷二·单模式化**：原「completed 且已到 maxChapters 则 +10 章、并重置 autoExtendCount」的扩容分支随
 * 有限模式退役删除；ContinueDecision 随之收敛成一个新状态串（原三字段里另两个已成纯透传死字段）。
 * 故原四例扩容/重置用例删除属预期。
 */
class StoryGenerationPolicyContinueTest {

    // ── effectiveChapterLength ──

    @Test fun effective_length_no_ending_is_base() {
        assertEquals(1500, StoryGenerationPolicy.effectiveChapterLength(1500, requestedEndingType = null))
    }

    @Test fun effective_length_with_ending_is_1_5x_truncated() {
        // Int(Double(1500)*1.5)=2250；500→750；3000→4500。
        assertEquals(2250, StoryGenerationPolicy.effectiveChapterLength(1500, requestedEndingType = "ai"))
        assertEquals(750, StoryGenerationPolicy.effectiveChapterLength(500, requestedEndingType = "open"))
        assertEquals(4500, StoryGenerationPolicy.effectiveChapterLength(3000, requestedEndingType = "custom"))
    }

    @Test fun effective_length_truncates_toward_zero() {
        // 1*1.5=1.5 → toInt()=1（与 iOS Int(1.5)=1 一致）。
        assertEquals(1, StoryGenerationPolicy.effectiveChapterLength(1, requestedEndingType = "ai"))
    }

    // ── decideContinue ──

    @Test fun continue_completed_becomes_serializing() {
        // 已完结 → 开启续篇：serializing（不再有任何「扩容上限」动作）。
        assertEquals(StoryStatus.SERIALIZING, StoryGenerationPolicy.decideContinue(StoryStatus.COMPLETED))
    }

    @Test fun continue_paused_becomes_serializing() {
        assertEquals(StoryStatus.SERIALIZING, StoryGenerationPolicy.decideContinue(StoryStatus.PAUSED))
    }

    @Test fun continue_other_statuses_unchanged() {
        // serializing / waitingChoice / generating / generationFailed 等原样返回
        // （调用方仍刷 updatedAt + 清 cachedHasPendingChoice）。
        assertEquals(StoryStatus.SERIALIZING, StoryGenerationPolicy.decideContinue(StoryStatus.SERIALIZING))
        assertEquals(StoryStatus.WAITING_CHOICE, StoryGenerationPolicy.decideContinue(StoryStatus.WAITING_CHOICE))
        assertEquals(StoryStatus.GENERATING, StoryGenerationPolicy.decideContinue(StoryStatus.GENERATING))
        assertEquals(
            StoryStatus.GENERATION_FAILED,
            StoryGenerationPolicy.decideContinue(StoryStatus.GENERATION_FAILED),
        )
    }
}
