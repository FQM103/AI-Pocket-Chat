package com.situ.aichat.ui.story

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.res.ResourcesCompat
import com.situ.aichat.R

/**
 * 分享长图内容（ST8·契约 §5 / 隐私口径 §14：只含书名/题材/足迹/摘句/署名，**不含正文全文**）。
 * 本地化文案由调用方（[StoryArchiveDetailScreen] VM）用 stringResource 预解析后传入，渲染器只画不查表。
 */
data class StoryShareCardContent(
    val coverColorScheme: String,
    val storyId: String,
    val title: String,
    /** 题材线，如「言情 · 严肃文学」（story.genre + " · " + writingStyle·原始中文·非本地化键）。 */
    val genreLine: String,
    /** 足迹行，如「12 话 · 9 次选择 · 一段写了 41 天的故事」。 */
    val footprintLine: String,
    /** 结局摘句（可空串→隐藏摘句区）。 */
    val quote: String,
    /** 署名行，如「AI Pocket Chat · 和 TA 一起写的故事」。 */
    val signatureLine: String,
)

/**
 * 结局分享长图自绘渲染器（ST8·契约 §5·照 mockup 屏九）。
 *
 * `android.graphics.Canvas` 画 `Bitmap`，**零第三方**：上半幅=封面渐变 + 题材纹样 glyph（复用
 * [drawStoryGlyph] 现成艺术·§2 DRY，绝不重画）+ 大号竖排书名；下半米白信息面板=题材线 + 足迹行 + 楷体摘句 + App 署名。
 * 色板固定走暖陶浅色（分享图是脱应用主题的独立作品·mockup :root 定值），字号按 1080 宽等比缩放（mockup 340 基准）。
 */
object StoryShareCardRenderer {

    /** 输出宽（mockup 340 → 1080·建议分辨率），高按 mockup 9:16 等比。 */
    const val WIDTH = 1080
    const val HEIGHT = 1918
    private const val SCALE = WIDTH / 340f          // mockup px → bitmap px
    private const val UPPER_RATIO = 0.62f            // glyph 幅高占比（mockup .glyph-big height:62%）

    // 固定色板（mockup :root·分享图脱主题恒暖陶浅色）。
    private const val PANEL_BG = 0xFFFDFBF7.toInt()   // .share-panel 米白
    private const val TITLE_INK = 0xFFF5EFEA.toInt()  // .vt-big 封面暖白
    private const val ACC_DEEP = 0xFF9A5B3E.toInt()   // 题材线
    private const val ACC = 0xFFBE8A76.toInt()        // 渐隐横线起色
    private const val TX2 = 0xFF6B6258.toInt()        // 足迹行
    private const val QUOTE_INK = 0xFF544A3E.toInt()  // .share-quote
    private const val TX3 = 0xFF9C938A.toInt()        // 署名
    private const val SUNKEN = 0xFFF1ECE4.toInt()     // 署名分隔线
    private const val GRAD_L1 = 0xFFC99A86.toInt()    // logo 渐变
    private const val GRAD_L2 = 0xFFBE8A76.toInt()

    private fun px(mockupPx: Float): Float = mockupPx * SCALE

    /** 渲染分享长图。字体加载失败（如 Robolectric）时楷体优雅回退衬线（kickoff 允许）。 */
    fun render(context: Context, content: StoryShareCardContent): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val palette = StoryCoverArt.palette(content.coverColorScheme)
        val kai = runCatching { ResourcesCompat.getFont(context, R.font.apc_kai) }.getOrNull() ?: Typeface.SERIF

        drawBackground(canvas, palette)
        drawGlyph(canvas, content, palette)
        drawVerticalTitle(canvas, content.title)
        drawPanel(canvas, content, kai)
        return bitmap
    }

    /** 全卡对角封面渐变（同 [StoryCover] 基色·135° 近似）。 */
    private fun drawBackground(canvas: android.graphics.Canvas, palette: StoryCoverPalette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                palette.start.toArgb(), palette.end.toArgb(), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
    }

    /** 上半幅题材纹样：复用 [drawStoryGlyph]（封面比例 3:4·裁到 62% 幅高·CSS slice 等效）。 */
    private fun drawGlyph(canvas: android.graphics.Canvas, content: StoryShareCardContent, palette: StoryCoverPalette) {
        val upperH = HEIGHT * UPPER_RATIO
        canvas.save()
        canvas.clipRect(0f, 0f, WIDTH.toFloat(), upperH)
        val composeCanvas = androidx.compose.ui.graphics.Canvas(canvas)
        val ink = StoryCoverArt.glyphInk(palette)
        val jitter = StoryCoverArt.glyphJitterDeg(content.storyId)
        // 以封面 3:4 比例给 size（宽=WIDTH，高=WIDTH*160/120）→ glyph 不被压扁，超出的下缘被 clip 裁掉。
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, composeCanvas, Size(WIDTH.toFloat(), WIDTH * 160f / 120f),
        ) {
            drawStoryGlyph(content.coverColorScheme, ink, jitter)
        }
        canvas.restore()
    }

    /** 大号竖排书名（top-right·mockup .vt-big 30px/.4em）；长名自适应缩字保证单列不溢出上半幅。 */
    private fun drawVerticalTitle(canvas: android.graphics.Canvas, title: String) {
        if (title.isEmpty()) return
        val topY = px(44f)
        val rightX = WIDTH - px(38f)
        val available = HEIGHT * UPPER_RATIO - topY - px(24f)
        val fontPx = minOf(px(30f), available / title.length.coerceAtLeast(1) / 1.4f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TITLE_INK
            textSize = fontPx
            textAlign = Paint.Align.CENTER
            setShadowLayer(fontPx * 0.4f, 0f, fontPx * 0.06f, 0x38000000)
        }
        val step = fontPx * 1.4f
        var baseline = topY - paint.fontMetrics.ascent
        title.forEach { ch ->
            canvas.drawText(ch.toString(), rightX, baseline, paint)
            baseline += step
        }
    }

    /** 下半米白信息面板：题材线 + 渐隐横线 + 足迹行 + 楷体摘句 + 署名行（logo + 文案）。 */
    private fun drawPanel(canvas: android.graphics.Canvas, content: StoryShareCardContent, kai: Typeface) {
        val side = px(26f)
        val panelWidth = WIDTH - 2 * side

        val genrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACC_DEEP; textSize = px(11f); letterSpacing = 0.2f; isFakeBoldText = true
        }
        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TX2; textSize = px(12.5f); letterSpacing = 0.03f }
        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = QUOTE_INK; textSize = px(15f); typeface = kai }
        val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TX3; textSize = px(11f); letterSpacing = 0.06f }

        val quoteLayout = if (content.quote.isNotBlank()) {
            StaticLayout.Builder.obtain(content.quote, 0, content.quote.length, quotePaint, panelWidth.toInt())
                .setLineSpacing(0f, 1.95f).build()
        } else {
            null
        }

        val topPad = px(22f)
        val bottomPad = px(20f)
        val genreH = lineHeight(genrePaint)
        val footH = lineHeight(footPaint)
        val quoteH = quoteLayout?.height?.toFloat() ?: 0f
        val logoSize = px(16f)
        val signH = maxOf(logoSize, lineHeight(signPaint))
        val gapFoot = px(10f)
        val gapQuote = if (quoteLayout != null) px(14f) else 0f
        val gapSign = px(18f)
        val signTopPad = px(14f)

        val panelH = topPad + genreH + gapFoot + footH + gapQuote + quoteH + gapSign + signTopPad + signH + bottomPad
        val panelTop = HEIGHT - panelH

        // 面板：顶角圆（radius 20·mockup）·下缘直角（画到卡底）。
        val radius = px(20f)
        val panelPath = Path().apply {
            addRoundRect(
                RectF(0f, panelTop, WIDTH.toFloat(), HEIGHT.toFloat()),
                floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
                Path.Direction.CW,
            )
        }
        canvas.drawPath(panelPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_BG })

        var y = panelTop + topPad
        // 题材线 + 渐隐横线
        val genreBaseline = y - genrePaint.fontMetrics.ascent
        canvas.drawText(content.genreLine, side, genreBaseline, genrePaint)
        val genreTextW = genrePaint.measureText(content.genreLine)
        val ruleStart = side + genreTextW + px(8f)
        val ruleEnd = WIDTH - side
        if (ruleEnd > ruleStart) {
            val ruleY = y + genreH / 2f
            val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = px(1f)
                shader = LinearGradient(ruleStart, ruleY, ruleEnd, ruleY, (ACC and 0x00FFFFFF) or 0x4D000000, ACC and 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            canvas.drawLine(ruleStart, ruleY, ruleEnd, ruleY, rulePaint)
        }
        y += genreH + gapFoot
        // 足迹行
        canvas.drawText(content.footprintLine, side, y - footPaint.fontMetrics.ascent, footPaint)
        y += footH + gapQuote
        // 摘句
        if (quoteLayout != null) {
            canvas.save()
            canvas.translate(side, y)
            quoteLayout.draw(canvas)
            canvas.restore()
            y += quoteH + gapSign
        }
        // 署名：分隔线 + 内边距 + logo + 文案
        canvas.drawLine(side, y, WIDTH - side, y, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUNKEN; strokeWidth = px(1f) })
        y += signTopPad
        val logoRadius = px(5.5f)
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(side, y, side + logoSize, y + logoSize, GRAD_L1, GRAD_L2, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(side, y, side + logoSize, y + logoSize), logoRadius, logoRadius, logoPaint)
        val signBaseline = y + logoSize / 2f - (signPaint.fontMetrics.ascent + signPaint.fontMetrics.descent) / 2f
        canvas.drawText(content.signatureLine, side + logoSize + px(8f), signBaseline, signPaint)
    }

    private fun lineHeight(paint: Paint): Float = paint.fontMetrics.descent - paint.fontMetrics.ascent
}
