package com.situ.aichat.prompt.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感二期 M1 · T1-1（E1/E2/E3/E9）：[MemorySummarySections] 两段视图纯解析——标准两段 / 无标题全归
 * unparsed / 只有单标题 / 标题前导语 / 同行内容 / 重复标题追加 / CRLF / 空串。断言从图纸 §3.1 规格独立反推。
 */
class MemorySummarySectionsTest {

    private val longHeader = MemorySummarySections.LONG_TERM_HEADER
    private val recentHeader = MemorySummarySections.RECENT_HEADER

    /** 常量必须与生成模板字面逐字节一致（§6 第四耦合点·钉死回归）。 */
    @Test fun `常量字面与生成模板标题一致`() {
        assertEquals("【长期事实】", MemorySummarySections.LONG_TERM_HEADER)
        assertEquals("【近期经历】", MemorySummarySections.RECENT_HEADER)
        assertTrue(MemoryService.DEFAULT_EXTRACTION_PROMPT.contains(MemorySummarySections.LONG_TERM_HEADER))
        assertTrue(MemoryService.DEFAULT_EXTRACTION_PROMPT.contains(MemorySummarySections.RECENT_HEADER))
    }

    @Test fun `标准两段_各归各节_无导语`() {
        val text = """
            $longHeader
            用户是程序员
            喜欢猫
            $recentHeader
            [2026-07-01] 聊了工作
            [2026-07-05] 一起看电影
        """.trimIndent()
        val s = MemorySummarySections.parse(text)
        assertTrue(s.unparsed.isEmpty())
        assertEquals(listOf("用户是程序员", "喜欢猫"), s.longTermFacts)
        assertEquals(listOf("[2026-07-01] 聊了工作", "[2026-07-05] 一起看电影"), s.recentEvents)
        assertTrue(s.hasSections)
        // E9：查看全部计数 = 三节总行数。
        val total = s.unparsed.size + s.longTermFacts.size + s.recentEvents.size
        assertEquals(4, total)
    }

    @Test fun `无标题_全归unparsed_hasSections为假`() {
        val text = "用户喜欢喝咖啡\n住在上海\n养了一只狗"
        val s = MemorySummarySections.parse(text)
        assertEquals(listOf("用户喜欢喝咖啡", "住在上海", "养了一只狗"), s.unparsed)
        assertTrue(s.longTermFacts.isEmpty())
        assertTrue(s.recentEvents.isEmpty())
        assertFalse("无两标题 → 回退现状（E1）", s.hasSections)
    }

    @Test fun `只有长期标题_近期节为空`() {
        val text = "$longHeader\n用户是老师\n爱读书"
        val s = MemorySummarySections.parse(text)
        assertEquals(listOf("用户是老师", "爱读书"), s.longTermFacts)
        assertTrue(s.recentEvents.isEmpty())
        assertTrue(s.unparsed.isEmpty())
        assertTrue(s.hasSections)
    }

    @Test fun `只有近期标题_长期节为空`() {
        val text = "$recentHeader\n[2026-07-01] 第一次聊天"
        val s = MemorySummarySections.parse(text)
        assertTrue(s.longTermFacts.isEmpty())
        assertEquals(listOf("[2026-07-01] 第一次聊天"), s.recentEvents)
        assertTrue(s.hasSections)
    }

    @Test fun `标题前有导语_导语归unparsed_展示在两节之前`() {
        val text = """
            这是一段开场白
            还有第二行导语
            $longHeader
            用户很内向
            $recentHeader
            [2026-07-02] 聊了心事
        """.trimIndent()
        val s = MemorySummarySections.parse(text)
        assertEquals(listOf("这是一段开场白", "还有第二行导语"), s.unparsed)
        assertEquals(listOf("用户很内向"), s.longTermFacts)
        assertEquals(listOf("[2026-07-02] 聊了心事"), s.recentEvents)
        assertTrue(s.hasSections)
    }

    @Test fun `标题与内容同行_切节且同行剩余作首行`() {
        val text = "$longHeader 他住在北京\n还是个医生\n$recentHeader [2026-07-03] 一起吃饭"
        val s = MemorySummarySections.parse(text)
        assertEquals(listOf("他住在北京", "还是个医生"), s.longTermFacts)
        assertEquals(listOf("[2026-07-03] 一起吃饭"), s.recentEvents)
        assertTrue(s.unparsed.isEmpty())
    }

    @Test fun `重复标题_追加进同节`() {
        val text = """
            $longHeader
            事实一
            $recentHeader
            [2026-07-01] 事件A
            $recentHeader
            [2026-07-02] 事件B
        """.trimIndent()
        val s = MemorySummarySections.parse(text)
        assertEquals(listOf("事实一"), s.longTermFacts)
        assertEquals(listOf("[2026-07-01] 事件A", "[2026-07-02] 事件B"), s.recentEvents)
    }

    @Test fun `CRLF换行_正常解析`() {
        val text = "$longHeader\r\n用户爱运动\r\n$recentHeader\r\n[2026-07-04] 去爬山"
        val s = MemorySummarySections.parse(text)
        assertEquals(listOf("用户爱运动"), s.longTermFacts)
        assertEquals(listOf("[2026-07-04] 去爬山"), s.recentEvents)
        assertTrue(s.unparsed.isEmpty())
    }

    @Test fun `空串_全空_hasSections为假`() {
        val s = MemorySummarySections.parse("")
        assertTrue(s.unparsed.isEmpty())
        assertTrue(s.longTermFacts.isEmpty())
        assertTrue(s.recentEvents.isEmpty())
        assertFalse(s.hasSections)
    }
}
