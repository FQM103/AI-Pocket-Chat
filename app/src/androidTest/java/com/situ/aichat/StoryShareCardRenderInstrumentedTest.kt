package com.situ.aichat

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.situ.aichat.ui.story.StoryShareCardContent
import com.situ.aichat.ui.story.StoryShareCardRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 分享长图真机渲染 T3（ST8）：模拟器真实 Canvas/Bitmap 光栅化（Robolectric 不保证真绘制）——
 * 断言输出含多种像素色（证明封面渐变/纹样/面板/文字真的画上去了），并存 PNG 供人工检视布局。
 */
@RunWith(AndroidJUnit4::class)
class StoryShareCardRenderInstrumentedTest {

    @Test
    fun rendersRealPixelsAndSavesForInspection() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val content = StoryShareCardContent(
            coverColorScheme = "rose", storyId = "s1", title = "与你重逢的第七年",
            genreLine = "言情 · 严肃文学",
            footprintLine = "12 话 · 9 次选择 · 一段写了 41 天的故事",
            quote = "后来的梅雨季，伞下总是两个人。有些信迟到了七年，但它到了。",
            signatureLine = "AI Pocket Chat · 和 TA 一起写的故事",
        )
        val bmp = StoryShareCardRenderer.render(ctx, content)
        assertEquals(StoryShareCardRenderer.WIDTH, bmp.width)
        assertEquals(StoryShareCardRenderer.HEIGHT, bmp.height)

        val colors = mutableSetOf<Int>()
        for (y in intArrayOf(50, 400, 900, 1400, 1850)) for (x in intArrayOf(50, 540, 1000)) colors.add(bmp.getPixel(x, y))
        assertTrue("应有多种像素色（证明真实绘制·非全同色）", colors.size > 3)

        val out = File(ctx.cacheDir, "share_card_preview.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
