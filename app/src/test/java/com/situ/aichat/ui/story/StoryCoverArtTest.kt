package com.situ.aichat.ui.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 程序化封面调色 T1（ST7a·契约 §6.1 / §11）：题材基色齐全可辨 / 兜底浅底 / 书名色极性 / 种子确定有界。
 * 纯逻辑，无 Android/Canvas 依赖（渲染正确性归 T4 装机走查）。
 */
class StoryCoverArtTest {

    private val schemes = listOf("rose", "amber", "violet", "cyan", "slate", "crimson", "mint", "sepia", "rust", "sky")

    @Test
    fun 十题材配色齐全且各异() {
        val palettes = schemes.map { StoryCoverArt.palette(it) }
        val starts = palettes.map { it.start }
        assertEquals("十题材基色不得重复", starts.size, starts.distinct().size)
        assertTrue("sky 为浅底", StoryCoverArt.palette("sky").lightSurface)
        schemes.filter { it != "sky" }.forEach {
            assertTrue("$it 应深底", !StoryCoverArt.palette(it).lightSurface)
        }
    }

    @Test
    fun 未知题材回落米杏浅底() {
        val p = StoryCoverArt.palette("某自定义题材")
        assertEquals("未知题材 = sky 兜底", StoryCoverArt.palette("sky"), p)
        assertTrue(p.lightSurface)
    }

    @Test
    fun 书名与纹样色极性正确() {
        assertEquals("深底 → 暖白书名", StoryCoverArt.titleOnDark, StoryCoverArt.titleColor(StoryCoverArt.palette("slate")))
        assertEquals("浅底 → 深字书名", StoryCoverArt.titleOnLight, StoryCoverArt.titleColor(StoryCoverArt.palette("sky")))
        assertEquals("深底 → 暖白纹样", StoryCoverArt.titleOnDark, StoryCoverArt.glyphInk(StoryCoverArt.palette("crimson")))
        assertEquals("浅底 → 褐色纹样", StoryCoverArt.glyphInkOnLight, StoryCoverArt.glyphInk(StoryCoverArt.palette("sky")))
    }

    @Test
    fun 纹样微旋转确定且有界() {
        assertEquals("同 storyId 恒定", StoryCoverArt.glyphJitterDeg("story-abc"), StoryCoverArt.glyphJitterDeg("story-abc"), 0.0001f)
        listOf("a", "b", "story-1", "与你重逢的第七年", "xyz-987", "", " ").forEach {
            val d = StoryCoverArt.glyphJitterDeg(it)
            assertTrue("jitter 越界：$it=$d", d in -6f..6f)
        }
    }
}
