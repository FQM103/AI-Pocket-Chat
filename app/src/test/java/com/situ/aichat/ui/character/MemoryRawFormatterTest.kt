package com.situ.aichat.ui.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryRawFormatter 纯格式化（图纸 2026-07-15-资料页三Tab重构 §4.4·V-1）单测。
 * 断言从 §4.4 规格独立反推（不照抄实现输出）：
 *  - 项目符号前缀集 = 「- 」「• 」「· 」「* 」（**符号+空格**·不吃裸负号）；命中则去前缀 + trim。
 *  - 标题行 = 整行恰为【…】且内部不含嵌套「】」；纯显示层美化·无结构依赖（漏写标题优雅退化）。
 *  - lines = 按 \n/\r 拆行 → trim → 去项目符号 → 滤空。
 * 覆盖边界 E2/E9/E10。
 */
class MemoryRawFormatterTest {

    // ── stripBullet（E9）────────────────────────────────────────────────
    @Test fun `stripBullet removes dash bullet`() {
        assertEquals("内容", MemoryRawFormatter.stripBullet("- 内容"))
    }

    @Test fun `stripBullet removes round dot bullets`() {
        assertEquals("内容", MemoryRawFormatter.stripBullet("• 内容"))
        assertEquals("内容", MemoryRawFormatter.stripBullet("· 内容"))
    }

    @Test fun `stripBullet removes asterisk bullet`() {
        assertEquals("内容", MemoryRawFormatter.stripBullet("* 内容"))
    }

    @Test fun `stripBullet does not eat bare negative number`() {
        // 「-5 度」无「- 」前缀（负号后无空格）→ 原样保留（E9 裸负号不被吃）。
        assertEquals("-5 度", MemoryRawFormatter.stripBullet("-5 度"))
    }

    @Test fun `stripBullet does not eat dash without trailing space`() {
        assertEquals("-内容", MemoryRawFormatter.stripBullet("-内容"))
    }

    @Test fun `stripBullet leaves plain text unchanged`() {
        assertEquals("普通文本", MemoryRawFormatter.stripBullet("普通文本"))
    }

    // ── isSectionTitle（E2/E10）─────────────────────────────────────────
    @Test fun `isSectionTitle true for whole-line bracket title`() {
        assertTrue(MemoryRawFormatter.isSectionTitle("【长期事实】"))
        assertTrue(MemoryRawFormatter.isSectionTitle("【近期经历】"))
    }

    @Test fun `isSectionTitle false when title not whole line`() {
        // 尾随正文 → 非整行标题。
        assertFalse(MemoryRawFormatter.isSectionTitle("【近期经历】x"))
    }

    @Test fun `isSectionTitle false for plain text`() {
        assertFalse(MemoryRawFormatter.isSectionTitle("普通文本"))
    }

    @Test fun `isSectionTitle false for nested closing bracket`() {
        // 内部含嵌套「】」→ 非单一标题（【a】b【c】）。
        assertFalse(MemoryRawFormatter.isSectionTitle("【a】b【c】"))
    }

    @Test fun `isSectionTitle false for too short`() {
        assertFalse(MemoryRawFormatter.isSectionTitle("【"))
        assertFalse(MemoryRawFormatter.isSectionTitle(""))
    }

    // ── lines（拆行 / trim / 去符号 / 滤空）───────────────────────────────
    @Test fun `lines splits trims filters and strips bullets`() {
        val input = "- a\n- b\n\n  c  "
        assertEquals(listOf("a", "b", "c"), MemoryRawFormatter.lines(input))
    }

    @Test fun `lines keeps section title and strips mixed bullets`() {
        val input = "【长期事实】\n- 喜欢猫\n• 住在北京"
        assertEquals(listOf("【长期事实】", "喜欢猫", "住在北京"), MemoryRawFormatter.lines(input))
    }

    @Test fun `lines handles carriage returns and blank-only input`() {
        assertEquals(emptyList<String>(), MemoryRawFormatter.lines(""))
        assertEquals(emptyList<String>(), MemoryRawFormatter.lines("\r\n\r\n"))
        assertEquals(listOf("x", "y"), MemoryRawFormatter.lines("x\r\ny"))
    }

    @Test fun `lines with only a title yields single title line`() {
        // E2：仅标题无内容 → display 只含该标题行（渲染为一条小标题·无展开按钮）。
        val result = MemoryRawFormatter.lines("【长期事实】")
        assertEquals(listOf("【长期事实】"), result)
        assertTrue(MemoryRawFormatter.isSectionTitle(result.single()))
    }
}
