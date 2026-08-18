package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 全局「文字忌口」设置字段的三态语义（图纸 §7 T1-3·E8）。
 *
 * 与 [StoryCreationTemperatureTest] 分开写的理由同源：两条都是故事域全局设置，但语义完全不同——
 * 温度有 sanitize/clamp，忌口**一个字都不许加工**（trim / 判空回退都会毁掉「主动清空」这一态）。
 */
class StoryBannedExpressionsSettingTest {

    @Test
    fun default_is_null_meaning_never_set() { // E8：老备份/首装解码得 null，不是空串
        assertNull(AppSettings().storyBannedExpressions)
    }

    @Test
    fun three_states_pass_through_unmodified() {
        // "" 必须原样保留 = 用户主动清空（若被折叠成 null 或默认，用户就永远删不掉忌口）
        assertEquals("", AppSettings(storyBannedExpressions = "").storyBannedExpressions)
        assertEquals("我的忌口", AppSettings(storyBannedExpressions = "我的忌口").storyBannedExpressions)
        // 前后空白也不许被 model 层擅自 trim（保存端语义 = 原样存）
        assertEquals("  留白  ", AppSettings(storyBannedExpressions = "  留白  ").storyBannedExpressions)
    }
}
