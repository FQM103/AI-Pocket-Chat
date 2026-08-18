package com.situ.aichat.ui.chat

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.toolcalling.GoldenResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B（③ 大输出安全阀）：[truncateToolResultText] 边界 + 阀门生效 + 对短文案零影响。
 *
 * - B-1 边界（小阈值便于钉点）：长度恰好阈值不截断 / 超 1 字符即截断 / 空串原样。
 * - B-3 短文案不变（与 0-3 golden 同向）：把真实回喂文案过一遍阀门后**字节等于 0-3 golden** → 证明 B-2 包裹零影响。
 * - B-3 阀门生效：超默认阈值（32K 字符）→ 截断为「说明 + 预览」，预览长度 == previewChars、说明含原始字符数。
 */
class ToolResultTruncationTest {

    // ── B-1 边界 ──

    @Test fun exactly_at_limit_is_unchanged() {
        val s = "0123456789" // 10 字符
        assertEquals(s, truncateToolResultText(s, maxChars = 10, previewChars = 4))
    }

    @Test fun one_char_over_limit_truncates() {
        val s = "0123456789A" // 11 字符
        val out = truncateToolResultText(s, maxChars = 10, previewChars = 4)
        assertTrue("应含截断说明 + 原始字符数", out.startsWith("[工具输出过长已截断：原 11 字符，仅保留前 4 字符]\n"))
        assertTrue("说明后接前 4 字符预览", out.endsWith("\n0123"))
    }

    @Test fun empty_is_unchanged() {
        assertEquals("", truncateToolResultText("", maxChars = 10, previewChars = 4))
    }

    @Test fun under_limit_is_unchanged() {
        val s = "短文案"
        assertEquals(s, truncateToolResultText(s, maxChars = 10, previewChars = 4))
    }

    // ── B-3 短文案不变：真实回喂文案过阀后 == 0-3 golden（证明 B-2 包裹零影响） ──

    @Test fun real_followup_text_through_valve_equals_golden() {
        val createAction = CalendarAction.fromToolCallArguments(
            """{"action":"create_event","title":"开会","startDate":"2026-06-05T10:00:00"}""",
        )
        val confirm = truncateToolResultText(toolFollowUpResultText(createAction, "calendar_action", true))
        assertEquals(GoldenResources.read("followup_confirm.txt"), confirm)

        val future = truncateToolResultText(toolFollowUpResultText(null, "propose_future_meeting", false))
        assertEquals(GoldenResources.read("followup_future.txt"), future)
    }

    // ── B-3 阀门生效（默认 32K/4K 阈值） ──

    @Test fun over_default_threshold_truncates_to_notice_plus_preview() {
        val big = "a".repeat(MAX_TOOL_RESULT_CHARS + 1)
        val out = truncateToolResultText(big)

        assertTrue("超阈值应截断", out.length < big.length)
        assertTrue(
            "说明含原始字符数",
            out.startsWith("[工具输出过长已截断：原 ${MAX_TOOL_RESULT_CHARS + 1} 字符，仅保留前 $TOOL_RESULT_PREVIEW_CHARS 字符]\n"),
        )
        // 预览部分（说明行之后）长度 == previewChars
        val preview = out.substringAfter("]\n")
        assertEquals(TOOL_RESULT_PREVIEW_CHARS, preview.length)
        assertFalse("预览不含完整原文", out.contains(big))
    }
}
