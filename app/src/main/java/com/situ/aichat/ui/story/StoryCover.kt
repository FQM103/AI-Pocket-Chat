package com.situ.aichat.ui.story

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.AppShapes

/**
 * 程序化故事封面（ST7a·契约 §6.1 / D6）：题材莫兰迪双 stop 渐变（135°）+ 自绘题材纹样 + 竖排书名。
 * `coverColorScheme`（建故事时落库）定基色与纹样；`storyId` 作种子微旋转纹样（同题材不同书有别）。
 * 3:4 圆角卡（默认 [AppShapes.small] 8dp；[shape] 可按场景给别的圆角，如书页头部缩略图的 10dp）；
 * [titleSizeSp] 按尺寸给（书架 82dp≈11 / 结局大图≈15）。零图片、零第三方。
 */
@Composable
fun StoryCover(
    coverColorScheme: String,
    title: String,
    storyId: String,
    modifier: Modifier = Modifier,
    titleSizeSp: Float = 11f,
    shape: Shape = AppShapes.small,
) {
    val palette = remember(coverColorScheme) { StoryCoverArt.palette(coverColorScheme) }
    val jitter = remember(storyId) { StoryCoverArt.glyphJitterDeg(storyId) }
    val glyphInk = StoryCoverArt.glyphInk(palette)
    val titleColor = StoryCoverArt.titleColor(palette)
    Canvas(modifier.clip(shape)) {
        drawRect(Brush.linearGradient(listOf(palette.start, palette.end), start = Offset.Zero, end = Offset(size.width, size.height)))
        drawStoryGlyph(coverColorScheme, glyphInk, jitter)
        drawVerticalTitle(title, titleColor, titleSizeSp, palette.lightSurface)
    }
}

/** 竖排书名：nativeCanvas 逐字下落；长名自适应缩字保证单列不溢出；深底暖白带柔影，浅底深字。 */
private fun DrawScope.drawVerticalTitle(title: String, color: Color, sizeSp: Float, lightSurface: Boolean) {
    if (title.isEmpty()) return
    val usableTop = size.height * 0.10f
    val usableH = size.height * 0.84f
    val requested = sizeSp.sp.toPx()
    // letterSpacing≈.34em → 行距 1.34×字号；长名缩字（不换列，v1 简版）
    val fontPx = minOf(requested, usableH / title.length.coerceAtLeast(1) / 1.34f)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = fontPx
        textAlign = android.graphics.Paint.Align.CENTER
        if (!lightSurface) setShadowLayer(fontPx * 0.5f, 0f, fontPx * 0.08f, android.graphics.Color.argb(48, 0, 0, 0))
    }
    val step = fontPx * 1.34f
    val colX = size.width * 0.82f
    var baseline = usableTop - paint.fontMetrics.ascent
    val canvas = drawContext.canvas.nativeCanvas
    title.forEach { ch ->
        canvas.drawText(ch.toString(), colX, baseline, paint)
        baseline += step
    }
}
