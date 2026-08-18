package com.situ.aichat.ui.diary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1：正文分段（U2③·详情页段落呼吸感）。断言从规格独立反推——单段/多段、单换行与空行分隔、
 * 逐段 trim、CRLF、以及全空白的优雅退化。
 */
class DiaryParagraphsTest {

    @Test
    fun `single line stays one paragraph`() {
        assertEquals(listOf("今天很开心"), splitDiaryParagraphs("今天很开心"))
    }

    @Test
    fun `single newline splits into paragraphs`() {
        assertEquals(listOf("第一段", "第二段", "第三段"), splitDiaryParagraphs("第一段\n第二段\n第三段"))
    }

    @Test
    fun `blank-line separators drop the empties`() {
        assertEquals(listOf("上", "下"), splitDiaryParagraphs("上\n\n下"))
    }

    @Test
    fun `each paragraph is trimmed`() {
        assertEquals(listOf("甲", "乙"), splitDiaryParagraphs("  甲  \n\n  乙 "))
    }

    @Test
    fun `windows CRLF also splits cleanly`() {
        assertEquals(listOf("a", "b"), splitDiaryParagraphs("a\r\nb"))
    }

    @Test
    fun `all-whitespace degrades to a single trimmed element`() {
        assertEquals(listOf(""), splitDiaryParagraphs("   \n  "))
    }
}
