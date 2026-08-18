package com.situ.aichat.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box

/**
 * 阅读器纸面渐变 + 正文遮罩（自研·FABLE5_STORY_REDESIGN_PROPOSAL §6.4 / D8）。
 *
 * **2026-08-03 心情视觉层退役**：原 11 套电影调色心情 token 表随 `[mood:]` 正文标签整族删除，
 * 纸面恒为**中性双档**——浅=温润象牙、深=沉静暖灰，两组色值逐字沿用（用户一直看到的就是它，
 * 因为「沉浸氛围」开关默认关、从未开启）。颜色只随系统深浅走，故渐变/交叉溶解包装一并拆除
 * （深浅切换由主题重组自然过渡），正文文字色对每个 stop ≥4.5 的回归网仍在
 * `ColorContrastTest` 与 `StoryMoodPaletteTest`。
 *
 * 保持纯 JVM 可测（ColorContrastTest 不吃 Robolectric）：只以 `isDark` 单轴取档、不读 CompositionLocal。
 */

/** 阅读器纸面：中性两组恒色，随系统深浅取档。 */
object StoryMoodPalette {

    fun colors(isDark: Boolean): List<Color> = if (isDark) NEUTRAL_DARK else NEUTRAL_LIGHT

    // 中性纸感（深浅各精调）：浅=温润象牙，深=沉静暖灰。**色值逐字锁定**（图纸 §9-①）。
    private val NEUTRAL_LIGHT = listOf(Color(0xFFFAF6EF), Color(0xFFF4ECDD), Color(0xFFEFE6D3))
    private val NEUTRAL_DARK = listOf(Color(0xFF1A1814), Color(0xFF211E18), Color(0xFF26221B))
}

/** 纸面背景：左上→右下三段渐变。 */
@Composable
fun StoryMoodBackground(isDark: Boolean, modifier: Modifier = Modifier) {
    val colors = StoryMoodPalette.colors(isDark)
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        colors = colors,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
            },
    )
}

/** 正文区半透明遮罩（提升文字对比度）。 */
@Composable
fun StoryReadingOverlay(isDark: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(StoryReaderLayout.readingOverlayColor(isDark)))
}
