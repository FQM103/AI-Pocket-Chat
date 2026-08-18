package com.situ.aichat.diagnostics

import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文渲染/截断纯函数单测（批 D）。断言从 iOS `formatContextForDisplay`/`clippedTextForLog` 反推：
 * 角色标签映射、消息序号、截断提示带原长。
 */
class LogContextFormatTest {

    private fun msg(role: String, content: String) = ChatMessageDto(role = role, content = content)

    @Test
    fun plainTextJoinsContentsWithNewline() {
        val messages = listOf(msg("system", "规则"), msg("user", "你好"), msg("assistant", "嗨"))
        assertEquals("规则\n你好\n嗨", LogContextFormat.plainText(messages))
    }

    @Test
    fun plainTextTreatsNullContentAsEmpty() {
        val messages = listOf(ChatMessageDto(role = "user", content = null), msg("assistant", "在"))
        assertEquals("\n在", LogContextFormat.plainText(messages))
    }

    @Test
    fun clipReturnsAsIsWhenUnderLimitOrNoLimit() {
        assertEquals("短文本", LogContextFormat.clip("短文本", 100))
        assertEquals("短文本", LogContextFormat.clip("短文本", null))
        assertEquals("短文本", LogContextFormat.clip("短文本", 0))
        assertEquals("短文本", LogContextFormat.clip("短文本", -5))
    }

    @Test
    fun clipTruncatesWithOriginalLengthMarker() {
        val text = "一".repeat(20)
        val clipped = LogContextFormat.clip(text, 5)
        assertEquals("一".repeat(5) + "\n\n[日志内容已截断，共 20 字]", clipped)
    }

    @Test
    fun clipKeepsExactlyAtLimit() {
        val text = "一".repeat(10)
        assertEquals(text, LogContextFormat.clip(text, 10)) // length==limit 不截
    }

    @Test
    fun renderHasHeaderRoleLabelsIndicesAndFooter() {
        val messages = listOf(msg("system", "规则A"), msg("user", "问题B"), msg("assistant", "回答C"))
        val out = LogContextFormat.render(messages)
        assertTrue(out.contains("发送给大模型的完整上下文"))
        assertTrue(out.contains("系统提示"))
        assertTrue(out.contains("用户"))
        assertTrue(out.contains("角色"))
        assertTrue(out.contains("[1/3]"))
        assertTrue(out.contains("[3/3]"))
        assertTrue(out.contains("规则A"))
        assertTrue(out.contains("共 3 条消息"))
    }

    @Test
    fun storedResponseKeepsMediumTextAndCapsOnlyPathological() {
        // 全量记录（2026-07-16）：旧 6000 字软上限已取消——5 万字中等长文原样入库；
        // 仅剩 STORED_TEXT_HARD_LIMIT 极端安全帽，超帽仍按 clip 带原长提示。
        val short = "短回复"
        assertEquals(short, LogContextFormat.storedResponse(short))
        val medium = "字".repeat(50_000)
        assertEquals("旧软上限不得复活", medium, LogContextFormat.storedResponse(medium))
        val huge = "字".repeat(LogContextFormat.STORED_TEXT_HARD_LIMIT + 5_000)
        val capped = LogContextFormat.storedResponse(huge)
        assertTrue(capped.length < huge.length)
        assertTrue(capped.contains("日志内容已截断"))
    }

    @Test
    fun storedContextKeepsLongMessageIntactUnderCap() {
        // 旧口径会把单条 >1200 字剪碎（故事圣经/记忆档案受害场景）：现在 1 万字单条原样全文在场。
        val big = "楔".repeat(10_000)
        val out = LogContextFormat.storedContext(listOf(msg("system", big)))
        assertTrue("整段原文必须在场", out.contains(big))
        assertFalse(out.contains("日志内容已截断"))
    }

    @Test
    fun storedContextPathologicalOverCapTruncatesWithMarker() {
        // 复核 R1-🔵：超帽整串路径此前只经 storedResponse 间接覆盖，补直测。
        val big = "字".repeat(LogContextFormat.STORED_TEXT_HARD_LIMIT + 5_000)
        val out = LogContextFormat.storedContext(listOf(msg("system", big)))
        assertTrue(out.length <= LogContextFormat.STORED_TEXT_HARD_LIMIT + 40) // 帽 + 截断尾注
        assertTrue(out.contains("日志内容已截断"))
    }

    @Test
    fun clipRetreatsAtSurrogatePairBoundary() {
        // 复核 R1-🔵：切点落在 emoji 代理对中间 → 高位回退一格，库里不留孤代理。
        val text = "😀".repeat(10) // 20 个 UTF-16 单元
        val clipped = LogContextFormat.clip(text, 5) // 单元 4 是高位 → 退到 4
        val body = clipped.substringBefore("\n\n[")
        assertEquals("😀😀", body)
        assertFalse(Character.isHighSurrogate(body.last()))
        assertTrue(clipped.contains("共 ${text.length} 字"))
    }

    @Test
    fun unknownRoleFallsBackToRawRoleLabel() {
        val out = LogContextFormat.render(listOf(msg("tool", "工具输出")))
        assertTrue(out.contains("tool"))      // 未知角色用原始 role 串
        assertFalse(out.contains("系统提示"))
    }
}
