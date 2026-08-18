package com.situ.aichat.ui.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 分享长图渲染 + 落盘 T2（ST8·契约 §11「分享卡渲染 Robolectric Bitmap 非空 + 尺寸」）。
 * Robolectric 提供 Canvas/Bitmap 真实现；楷体缺失优雅回退衬线不崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryShareCardRendererTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun content(quote: String = "后来的梅雨季，伞下总是两个人。") = StoryShareCardContent(
        coverColorScheme = "rose",
        storyId = "s1",
        title = "与你重逢的第七年",
        genreLine = "言情 · 严肃文学",
        footprintLine = "12 话 · 9 次选择 · 一段写了 41 天的故事",
        quote = quote,
        signatureLine = "AI Pocket Chat · 和 TA 一起写的故事",
    )

    @Test
    fun 渲染_bitmap非空且尺寸正确() {
        val bmp = StoryShareCardRenderer.render(context, content())
        assertNotNull(bmp)
        assertEquals(StoryShareCardRenderer.WIDTH, bmp.width)
        assertEquals(StoryShareCardRenderer.HEIGHT, bmp.height)
        assertFalse("bitmap 不应被回收", bmp.isRecycled)
    }

    @Test
    fun 渲染_空摘句不崩() {
        val bmp = StoryShareCardRenderer.render(context, content(quote = ""))
        assertEquals(StoryShareCardRenderer.WIDTH, bmp.width)
        assertEquals(StoryShareCardRenderer.HEIGHT, bmp.height)
    }

    @Test
    fun 渲染_未知题材回落兜底封面不崩() {
        val bmp = StoryShareCardRenderer.render(context, content().copy(coverColorScheme = "unknown_scheme"))
        assertEquals(StoryShareCardRenderer.WIDTH, bmp.width)
    }

    @Test
    fun 落盘_写png返回FileProvider_uri() {
        val bmp = StoryShareCardRenderer.render(context, content())
        val uri = StoryShareImageWriter.write(context, bmp)
        assertNotNull("应返回 FileProvider content uri", uri)
        assertEquals("content", uri!!.scheme)
        assertTrue("authority 应为 \${applicationId}.fileprovider", uri.authority!!.endsWith(".fileprovider"))
    }
}
