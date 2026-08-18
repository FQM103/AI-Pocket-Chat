package com.situ.aichat.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P1-27 QS 活磁贴 label 兜底链：角色名 → 会话标题 → null（调用方回退默认「找角色」）。 */
class QuickContactsTileLabelTest {

    @Test
    fun `character name wins when present`() {
        assertEquals("小雨", tileLabel("小雨", "会话标题"))
    }

    @Test
    fun `blank character name falls back to conversation title`() {
        // 含角色已删（get→null 时调用方传 null）与空白名两案。
        assertEquals("会话标题", tileLabel("  ", "会话标题"))
        assertEquals("会话标题", tileLabel(null, "会话标题"))
    }

    @Test
    fun `both blank yields null for default label fallback`() {
        assertNull(tileLabel(null, " "))
        assertNull(tileLabel("", ""))
    }
}
