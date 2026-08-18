package com.situ.aichat.ui.story

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 阅读器纸面配色的纯函数契约（2026-08-03 心情视觉层退役后，配色只随系统深浅单轴走）：
 * - 正文色随系统深浅二选一（浅纸近黑 / 深纸白字）；
 * - 中性纸面深浅各自独立精调，深档不是浅档的降透明；
 * - 顶栏/底部胶囊垫底色 [StoryReaderLayout.chromeScrimColor] 随纸面明暗自适应（深纸→深底，浅纸→浅底）。
 */
class StoryReaderChromeColorTest {

    @Test fun text_color_follows_system_theme() {
        assertEquals(Color(0xFF1A1A1A), StoryReaderLayout.textColor(isDark = false))
        assertEquals(Color.White.copy(alpha = 0.88f), StoryReaderLayout.textColor(isDark = true))
    }

    // ── 中性纸面：深浅独立、不走降透明 ──

    @Test fun paper_is_ivory_in_light() {
        val colors = StoryMoodPalette.colors(isDark = false)
        assertEquals(3, colors.size)
        assertEquals(Color(0xFFFAF6EF), colors.first()) // 温润象牙
        assertEquals(Color(0xFFEFE6D3), colors.last())
    }

    @Test fun paper_is_warm_gray_in_dark_and_fully_opaque() {
        val colors = StoryMoodPalette.colors(isDark = true)
        assertEquals(Color(0xFF1A1814), colors.first()) // 沉静暖灰
        // 不透明：证明走的是独立深色 base，而非浅色 ×0.62（后者 alpha≈0.62）。
        assertEquals(1f, colors.first().alpha)
    }

    @Test fun dark_paper_differs_from_naive_dimmed_light() {
        val dark = StoryMoodPalette.colors(isDark = true).first()
        val dimmedLight = Color(0xFFFAF6EF).copy(alpha = 0.62f)
        assertNotEquals(dimmedLight, dark)
    }

    // ── 悬浮控件垫底色随纸面明暗自适应 ──

    @Test fun chrome_scrim_is_light_on_light_paper() {
        assertEquals(Color.White.copy(alpha = 0.72f), StoryReaderLayout.chromeScrimColor(isDark = false))
    }

    @Test fun chrome_scrim_is_dark_on_dark_paper() {
        assertEquals(Color.Black.copy(alpha = 0.30f), StoryReaderLayout.chromeScrimColor(isDark = true))
    }
}
