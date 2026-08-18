package com.situ.aichat.ui.story

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 冷色系题材纹样（ST7a·契约 §6.1）——奇幻 / 科幻 / 悬疑 / 恐怖 / 末日，续 [StoryCoverGlyphs.kt]，共用 [q] 抬阶助手。
 * 星轨的 SVG 椭圆弧以三次贝塞尔近似（装饰轨迹，视觉等效）。
 */

private const val VB_W = 120f
private const val VB_H = 160f

/** 奇幻·远山与孤剑（cv6）。 */
internal fun DrawScope.glyphMountainSword(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun px(x: Float) = x * ux
    fun py(y: Float) = y * uy
    val sw = 1.3f * ux
    val far = Path().apply {
        moveTo(px(0f), py(118f)); lineTo(px(26f), py(92f)); lineTo(px(46f), py(110f))
        lineTo(px(72f), py(84f)); lineTo(px(96f), py(106f)); lineTo(px(120f), py(88f))
    }
    drawPath(far, ink.copy(alpha = 0.40f), style = Stroke(sw))
    val near = Path().apply {
        moveTo(px(0f), py(132f)); lineTo(px(32f), py(108f)); lineTo(px(58f), py(128f))
        lineTo(px(88f), py(104f)); lineTo(px(120f), py(122f))
    }
    drawPath(near, ink.copy(alpha = 0.25f), style = Stroke(sw))
    drawLine(ink.copy(alpha = 0.60f), Offset(px(88f), py(30f)), Offset(px(88f), py(78f)), 1.5f * ux)
    drawLine(ink.copy(alpha = 0.60f), Offset(px(82f), py(38f)), Offset(px(94f), py(38f)), 1.5f * ux)
}

/** 科幻·星轨归线（cv8·弧线以三次近似）。 */
internal fun DrawScope.glyphStarTrail(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun px(x: Float) = x * ux
    fun py(y: Float) = y * uy
    val orbit1 = Path().apply { moveTo(px(-10f), py(140f)); cubicTo(px(25f), py(118f), px(85f), py(92f), px(130f), py(96f)) }
    drawPath(orbit1, ink.copy(alpha = 0.35f), style = Stroke(1.2f * ux))
    val orbit2 = Path().apply { moveTo(px(-10f), py(120f)); cubicTo(px(30f), py(96f), px(90f), py(72f), px(126f), py(74f)) }
    drawPath(orbit2, ink.copy(alpha = 0.22f), style = Stroke(1.2f * ux, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * ux, 6f * ux))))
    drawCircle(ink.copy(alpha = 0.70f), 7f * ux, Offset(px(88f), py(52f)), style = Stroke(1.4f * ux))
    drawCircle(ink.copy(alpha = 0.80f), 2.2f * ux, Offset(px(88f), py(52f)))
    listOf(
        floatArrayOf(26f, 36f, 1.3f, 0.60f), floatArrayOf(52f, 24f, 1.0f, 0.45f),
        floatArrayOf(36f, 70f, 1.2f, 0.50f), floatArrayOf(104f, 26f, 1.2f, 0.50f),
    ).forEach { drawCircle(ink.copy(alpha = it[3]), it[2] * ux, Offset(px(it[0]), py(it[1]))) }
}

/** 悬疑·雾窗独灯（cv9）。 */
internal fun DrawScope.glyphFoggyWindow(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun px(x: Float) = x * ux
    fun py(y: Float) = y * uy
    val sw = 1.3f * ux
    val s = ink.copy(alpha = 0.45f)
    drawRect(s, Offset(px(32f), py(40f)), Size(56f * ux, 72f * uy), style = Stroke(sw))
    drawLine(s, Offset(px(60f), py(40f)), Offset(px(60f), py(112f)), sw)
    drawLine(s, Offset(px(32f), py(76f)), Offset(px(88f), py(76f)), sw)
    drawRect(Color(0xFFE8C989).copy(alpha = 0.50f), Offset(px(61f), py(77f)), Size(26f * ux, 34f * uy))
    drawRect(
        Brush.verticalGradient(listOf(Color(0x008B979C), Color(0xBFB9C2C5)), startY = py(98f), endY = py(140f)),
        Offset(px(20f), py(98f)), Size(86f * ux, 42f * uy),
    )
}

/** 恐怖·铁门与藤蔓（cv10）。 */
internal fun DrawScope.glyphIronGateVine(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun px(x: Float) = x * ux
    fun py(y: Float) = y * uy
    val sw = 1.4f * ux
    val s = ink.copy(alpha = 0.40f)
    listOf(34f, 52f, 70f, 88f).forEach { drawLine(s, Offset(px(it), py(34f)), Offset(px(it), py(130f)), sw) }
    val arch = Path().apply { moveTo(px(26f), py(40f)); q(px(26f), py(40f), px(60f), py(26f), px(94f), py(40f)) }
    drawPath(arch, s, style = Stroke(sw))
    val moss = Color(0xFF9FA88E)
    val vine = Path().apply {
        moveTo(px(20f), py(122f)); q(px(20f), py(122f), px(46f), py(96f), px(40f), py(66f))
    }
    drawPath(vine, moss.copy(alpha = 0.80f), style = Stroke(sw))
    val sprigA = Path().apply { moveTo(px(40f), py(66f)); q(px(40f), py(66f), px(32f), py(72f), px(36f), py(80f)) }
    val sprigB = Path().apply { moveTo(px(40f), py(66f)); q(px(40f), py(66f), px(48f), py(68f), px(46f), py(78f)) }
    drawPath(sprigA, moss.copy(alpha = 0.80f), style = Stroke(sw)); drawPath(sprigB, moss.copy(alpha = 0.80f), style = Stroke(sw))
    val leaf = Path().apply {
        moveTo(px(34f), py(96f)); q(px(34f), py(96f), px(25f), py(97f), px(24f), py(105f))
        q(px(24f), py(105f), px(33f), py(107f), px(34f), py(96f)); close()
    }
    drawPath(leaf, moss.copy(alpha = 0.55f))
}

/** 末日·裂纹透微光（cv11·暗线打底 + 亮线覆盖）。 */
internal fun DrawScope.glyphCrackLight(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun px(x: Float) = x * ux
    fun py(y: Float) = y * uy
    val glow = Color(0xFFF3D9A4)
    drawCircle(glow.copy(alpha = 0.22f), 10f * ux, Offset(px(64f), py(84f)))
    val crack = Path().apply {
        moveTo(px(58f), py(22f)); lineTo(px(52f), py(58f)); lineTo(px(64f), py(84f))
        lineTo(px(50f), py(112f)); lineTo(px(60f), py(142f))
    }
    drawPath(crack, Color(0xFF2E2925).copy(alpha = 0.30f), style = Stroke(5f * ux))
    drawPath(crack, glow.copy(alpha = 0.90f), style = Stroke(1.4f * ux))
}
