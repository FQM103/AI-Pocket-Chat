package com.situ.aichat.prompt.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「智能渐进压缩」开关（2026-06-20）纯函数单测。断言反推过审规格，不照搬实现：
 * - 用户自定义提取 prompt → 开关让位 [CompressionMode.NONE]（{{压缩策略}} 留空）。
 * - 默认模板 + 开关开 → [CompressionMode.PROGRESSIVE]（四级渐进话术）。
 * - 默认模板 + 开关关 → [CompressionMode.FIXED_LIMIT]（一句硬字数要求）。
 * - 三态都不截断存储，仅切换提取话术。
 * - 默认模板已删「当前时间」规则；但 {{当前时间}} 宏替换保留以兼容用户自定义模板。
 */
class MemoryCompressionModeTest {

    // ── resolveCompressionMode 三态 ──

    @Test fun customPrompt_alwaysNone_regardlessOfToggle() {
        assertEquals(CompressionMode.NONE, MemoryService.resolveCompressionMode(hasCustomPrompt = true, progressiveEnabled = true))
        assertEquals(CompressionMode.NONE, MemoryService.resolveCompressionMode(hasCustomPrompt = true, progressiveEnabled = false))
    }

    @Test fun defaultTemplate_toggleOn_progressive() {
        assertEquals(CompressionMode.PROGRESSIVE, MemoryService.resolveCompressionMode(hasCustomPrompt = false, progressiveEnabled = true))
    }

    @Test fun defaultTemplate_toggleOff_fixedLimit() {
        assertEquals(CompressionMode.FIXED_LIMIT, MemoryService.resolveCompressionMode(hasCustomPrompt = false, progressiveEnabled = false))
    }

    // ── {{压缩策略}} 宏内容（经 public applyExtractionMacros 间接验证 private compressionStrategy）──

    private fun strategyOf(mode: CompressionMode, currentLength: Int, maxLength: Int = 3000): String =
        MemoryService.applyExtractionMacros(
            template = "{{压缩策略}}",
            conversationText = "",
            existingMemory = "",
            now = "now",
            maxLength = maxLength,
            characterName = "",
            userName = "",
            currentLength = currentLength,
            compressionMode = mode,
        )

    @Test fun none_emptyStrategy() {
        assertEquals("", strategyOf(CompressionMode.NONE, currentLength = 1500))
    }

    @Test fun fixedLimit_exactHardRequirement() {
        assertEquals(
            "请将合并后的记忆控制在上限字数以内，如何精简取舍由你自行判断。",
            strategyOf(CompressionMode.FIXED_LIMIT, currentLength = 1500),
        )
    }

    @Test fun fixedLimit_ignoresLengthRatio() {
        // 关闭态话术固定，不随字数变化：首次(0) 与接近上限(2900) 一致。
        assertEquals(strategyOf(CompressionMode.FIXED_LIMIT, 0), strategyOf(CompressionMode.FIXED_LIMIT, 2900))
    }

    @Test fun progressive_firstExtraction_encouragesFullRecord() {
        assertTrue(strategyOf(CompressionMode.PROGRESSIVE, currentLength = 0).contains("首次记忆提取"))
    }

    @Test fun progressive_plenty_naturalRecord() {
        // ratio = 300/3000 = 0.1 ≤ 0.5 → 空间充裕档
        assertTrue(strategyOf(CompressionMode.PROGRESSIVE, currentLength = 300).contains("空间充裕"))
    }

    @Test fun progressive_nearLimit_hardCompress() {
        // ratio = 2900/3000 > 0.9 → 接近上限档
        assertTrue(strategyOf(CompressionMode.PROGRESSIVE, currentLength = 2900).contains("接近上限"))
    }

    // ── 当前时间：默认模板已删除，宏替换保留兼容自定义模板 ──

    @Test fun defaultPrompt_noLongerContainsCurrentTime() {
        assertFalse(MemoryService.DEFAULT_EXTRACTION_PROMPT.contains("当前时间"))
        assertFalse(MemoryService.DEFAULT_EXTRACTION_PROMPT.contains("{{当前时间}}"))
    }

    @Test fun currentTimeMacro_stillReplaced_forCustomTemplates() {
        val out = MemoryService.applyExtractionMacros(
            template = "现在：{{当前时间}}",
            conversationText = "",
            existingMemory = "",
            now = "2026-06-20 15:30",
            maxLength = 3000,
            characterName = "",
            userName = "",
            currentLength = 0,
            compressionMode = CompressionMode.NONE,
        )
        assertEquals("现在：2026-06-20 15:30", out)
    }
}
