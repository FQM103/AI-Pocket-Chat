package com.situ.aichat.prompt.diary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1：三问引导注入正文格式（U2①·撰写页答案注入生成）。断言从规格独立反推——全空不产段、非空按
 * 事→感觉→未说序、空白答案跳过（绝不产空标签行）、逐段 trim、模板套用。用中性模板隔离逻辑与文案。
 */
class DiaryGuideNotesTest {

    private val e = "E:%1\$s"
    private val f = "F:%1\$s"
    private val u = "U:%1\$s"

    private fun body(ev: String, fe: String, un: String) =
        DiaryPromptBuilder.formatGuideBody(DiaryGuideAnswers(ev, fe, un), e, f, u)

    @Test
    fun `all empty yields empty string so section is not injected`() {
        assertEquals("", body("", "", ""))
    }

    @Test
    fun `only one answer produces a single labelled line`() {
        assertEquals("E:去了旧书店", body("去了旧书店", "", ""))
    }

    @Test
    fun `all three kept in event-feeling-unsaid order`() {
        assertEquals("E:事\nF:感觉\nU:没说", body("事", "感觉", "没说"))
    }

    @Test
    fun `blank or whitespace answers are skipped, never empty-labelled`() {
        assertEquals("F:只有感觉", body("   ", "只有感觉", "\t"))
    }

    @Test
    fun `answers are trimmed before formatting`() {
        assertEquals("E:干净", body("  干净  ", "", ""))
    }

    @Test
    fun `percent signs in answers stay literal (format arg, never a specifier)`() {
        // 答案填入 String.format 的实参槽（非模板）→ 「20%」「%1$s」都当字面量·绝不解析/崩溃（提示词注入健壮性）。
        assertEquals("E:省了20%", body("省了20%", "", ""))
        assertEquals("F:模板%1\$s也安全", body("", "模板%1\$s也安全", ""))
    }
}
