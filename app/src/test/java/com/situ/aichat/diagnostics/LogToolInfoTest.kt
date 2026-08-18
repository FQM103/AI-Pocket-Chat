package com.situ.aichat.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具遥测纯函数看门（上下文日志工具可见性·2026-07-12·T1）：
 * 编解码往返 / 空与损坏输入 / detail 隐私剥离 / 参数预览截断 / 两轨工厂。断言从规格独立反推。
 */
class LogToolInfoTest {

    private val json = Json

    @Test
    fun `编解码往返_字段完整`() {
        val info = LogToolInfo.toolTurn(
            sentToolNames = listOf("calendar_action", "suggest_offline_meeting", "propose_future_meeting"),
            calls = listOf("suggest_offline_meeting" to """{"location":"公园"}"""),
            parsedOfflineActions = 1,
            usedTextFollowUp = false,
        )
        val decoded = LogToolInfo.decode(json, info.encode(json))
        assertEquals(info, decoded)
        assertEquals(LogToolInfo.MODE_TOOL, decoded!!.mode)
        assertEquals(1, decoded.parsedOfflineActions)
        assertEquals("suggest_offline_meeting", decoded.calls.single().name)
        assertEquals("""{"location":"公园"}""", decoded.calls.single().argsPreview)
    }

    @Test
    fun `解码_空串与损坏JSON均返回null不崩`() {
        assertNull(LogToolInfo.decode(json, ""))
        assertNull(LogToolInfo.decode(json, "{broken"))
        assertNull(LogToolInfo.decode(json, """{"unrelated":true}"""))
    }

    @Test
    fun `sanitized_detail关剥参数预览_名与计数恒存`() {
        val info = LogToolInfo.toolTurn(
            sentToolNames = listOf("calendar_action"),
            calls = listOf("calendar_action" to """{"title":"开会"}"""),
            parsedCalendarActions = 1,
        )
        val stripped = info.sanitized(detailEnabled = false)
        assertNull("detail 关 → 参数预览剥除", stripped.calls.single().argsPreview)
        assertEquals("工具名恒存", "calendar_action", stripped.calls.single().name)
        assertEquals("计数恒存", 1, stripped.parsedCalendarActions)
        assertEquals("下发清单恒存", listOf("calendar_action"), stripped.sentTools)
    }

    @Test
    fun `sanitized_detail开原样返回`() {
        val info = LogToolInfo.toolTurn(
            sentToolNames = listOf("calendar_action"),
            calls = listOf("calendar_action" to """{"title":"开会"}"""),
        )
        assertSame("detail 开 → 同一实例原样返回", info, info.sanitized(detailEnabled = true))
    }

    @Test
    fun `toolTurn_参数预览截断200_空参数无预览`() {
        val longArgs = "x".repeat(500)
        val info = LogToolInfo.toolTurn(
            sentToolNames = emptyList(),
            calls = listOf("t1" to longArgs, "t2" to ""),
        )
        assertEquals(LogToolInfo.ARGS_PREVIEW_MAX, info.calls[0].argsPreview!!.length)
        assertNull("空 arguments → 无预览", info.calls[1].argsPreview)
    }

    @Test
    fun `marker工厂_暗号轨零工具`() {
        val info = LogToolInfo.marker()
        assertEquals(LogToolInfo.MODE_MARKER, info.mode)
        assertTrue(info.sentTools.isEmpty())
        assertTrue(info.calls.isEmpty())
        // 往返后语义不变（详情页据 mode 显示「文本暗号轨」说明）。
        assertEquals(info, LogToolInfo.decode(json, info.encode(json)))
    }
}
