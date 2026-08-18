package com.situ.aichat.ui.story

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * 程序化封面题材纹样（ST7a·契约 §6.1）——照过审 mockup 封面全览 12 幅 SVG glyph 逐一移植为 Compose Canvas，
 * viewBox 120×160 归一化到画布。10 题材各一（言情/奇幻取代表纹样）；`ink` = 描边基色（深底暖白 / 浅底褐），
 * 强调色（灯火暖黄 / 月玫 / 藤蔓苔绿 / 裂纹微光）照 mockup 原值。曲线一律 [q]（二次→三次抬阶·避 API 差异），
 * 零素材、零第三方。冷色系 5 幅见 [StoryCoverGlyphsCool.kt]。
 */

/** 二次贝塞尔抬阶为三次（起点 = 当前点 (sx,sy)），避不同 Compose 版本 quadratic API 命名差异。 */
internal fun Path.q(sx: Float, sy: Float, cx: Float, cy: Float, ex: Float, ey: Float) {
    cubicTo(
        sx + 2f / 3f * (cx - sx), sy + 2f / 3f * (cy - sy),
        ex + 2f / 3f * (cx - ex), ey + 2f / 3f * (cy - ey),
        ex, ey,
    )
}

private const val VB_W = 120f
private const val VB_H = 160f

internal fun DrawScope.drawStoryGlyph(scheme: String, ink: Color, jitterDeg: Float) {
    rotate(jitterDeg, pivot = Offset(size.width / 2f, size.height / 2f)) {
        when (scheme) {
            "slate" -> glyphUrbanLamps(ink)
            "rose" -> glyphMoonBranch(ink)
            "mint" -> glyphPaperPlane(ink)
            "sepia" -> glyphPalaceSnow(ink)
            "violet" -> glyphMountainSword(ink)
            "cyan" -> glyphStarTrail(ink)
            "amber" -> glyphFoggyWindow(ink)
            "crimson" -> glyphIronGateVine(ink)
            "rust" -> glyphCrackLight(ink)
            else -> glyphStreetLamp(ink)
        }
    }
}

private val AMBER_LIT = Color(0xFFE8C989)

/** 都市·隔街两窗灯火（cv1）。 */
private fun DrawScope.glyphUrbanLamps(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun o(x: Float, y: Float) = Offset(x * ux, y * uy)
    val sw = 1.2f * ux
    val s = ink.copy(alpha = 0.38f)
    drawRect(s, o(12f, 52f), Size(26f * ux, 96f * uy), style = Stroke(sw))
    drawRect(s, o(82f, 38f), Size(26f * ux, 110f * uy), style = Stroke(sw))
    listOf(70f, 90f, 110f).forEach { drawLine(s, o(18f, it), o(32f, it), sw) }
    listOf(58f, 78f, 118f).forEach { drawLine(s, o(88f, it), o(102f, it), sw) }
    drawRect(AMBER_LIT.copy(alpha = 0.95f), o(19f, 96f), Size(12f * ux, 10f * uy))
    drawRect(AMBER_LIT.copy(alpha = 0.60f), o(89f, 84f), Size(12f * ux, 10f * uy))
    drawCircle(AMBER_LIT.copy(alpha = 0.18f), 9f * ux, o(25f, 101f))
}

/** 言情·月与缠枝（cv3）。 */
private fun DrawScope.glyphMoonBranch(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun px(x: Float) = x * ux
    fun py(y: Float) = y * uy
    val sw = 1.3f * ux
    drawCircle(ink.copy(alpha = 0.55f), 15f * ux, Offset(px(34f), py(42f)), style = Stroke(sw))
    drawCircle(Color(0xFFA57F73), 15f * ux, Offset(px(40f), py(38f)))
    val branch = Path().apply {
        moveTo(px(10f), py(132f)); q(px(10f), py(132f), px(40f), py(112f), px(62f), py(126f))
        q(px(62f), py(126f), px(84f), py(140f), px(112f), py(118f)) // T 反射控制点 = 2*end−prevCtrl
    }
    drawPath(branch, ink.copy(alpha = 0.42f), style = Stroke(sw))
    val leaf1 = Path().apply {
        moveTo(px(34f), py(124f)); q(px(34f), py(124f), px(39f), py(116f), px(46f), py(118f))
        q(px(46f), py(118f), px(42f), py(127f), px(34f), py(124f)); close()
    }
    val leaf2 = Path().apply {
        moveTo(px(76f), py(126f)); q(px(76f), py(126f), px(81f), py(118f), px(88f), py(120f))
        q(px(88f), py(120f), px(84f), py(129f), px(76f), py(126f)); close()
    }
    drawPath(leaf1, ink.copy(alpha = 0.30f)); drawPath(leaf2, ink.copy(alpha = 0.30f))
}

/** 校园·纸飞机与香樟叶（cv4）。 */
private fun DrawScope.glyphPaperPlane(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun px(x: Float) = x * ux
    fun py(y: Float) = y * uy
    val sw = 1.3f * ux
    val wing1 = Path().apply { moveTo(px(20f), py(96f)); lineTo(px(84f), py(66f)); lineTo(px(58f), py(96f)); close() }
    drawPath(wing1, ink.copy(alpha = 0.14f))
    val wing2 = Path().apply { moveTo(px(58f), py(96f)); lineTo(px(84f), py(66f)); lineTo(px(66f), py(106f)); close() }
    drawPath(wing2, ink.copy(alpha = 0.55f), style = Stroke(sw))
    val trail = Path().apply { moveTo(px(20f), py(96f)); q(px(20f), py(96f), px(34f), py(108f), px(52f), py(100f)) }
    drawPath(trail, ink.copy(alpha = 0.55f), style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * ux, 4f * ux))))
    val leaf = Path().apply {
        moveTo(px(84f), py(128f)); q(px(84f), py(128f), px(92f), py(115f), px(103f), py(119f))
        q(px(103f), py(119f), px(97f), py(133f), px(84f), py(128f)); close()
    }
    drawPath(leaf, ink.copy(alpha = 0.35f))
}

/** 历史·宫檐落雪（cv5）。 */
private fun DrawScope.glyphPalaceSnow(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun px(x: Float) = x * ux
    fun py(y: Float) = y * uy
    val eave = Path().apply {
        moveTo(px(8f), py(92f)); q(px(8f), py(92f), px(60f), py(74f), px(112f), py(92f))
        lineTo(px(112f), py(98f)); q(px(112f), py(98f), px(60f), py(80f), px(8f), py(98f)); close()
    }
    drawPath(eave, ink.copy(alpha = 0.28f))
    val tipL = Path().apply { moveTo(px(6f), py(92f)); q(px(6f), py(92f), px(2f), py(86f), px(10f), py(82f)) }
    val tipR = Path().apply { moveTo(px(114f), py(92f)); q(px(114f), py(92f), px(118f), py(86f), px(110f), py(82f)) }
    drawPath(tipL, ink.copy(alpha = 0.45f), style = Stroke(1.4f * ux))
    drawPath(tipR, ink.copy(alpha = 0.45f), style = Stroke(1.4f * ux))
    listOf(
        floatArrayOf(30f, 46f, 1.6f, 0.70f), floatArrayOf(58f, 34f, 1.3f, 0.50f),
        floatArrayOf(86f, 52f, 1.6f, 0.65f), floatArrayOf(44f, 64f, 1.2f, 0.45f),
        floatArrayOf(74f, 70f, 1.4f, 0.50f), floatArrayOf(22f, 120f, 1.3f, 0.40f),
        floatArrayOf(96f, 118f, 1.5f, 0.50f),
    ).forEach { drawCircle(ink.copy(alpha = it[3]), it[2] * ux, Offset(px(it[0]), py(it[1]))) }
}

/** 日常·街灯与炊烟（cv12·浅底深纹）。 */
private fun DrawScope.glyphStreetLamp(ink: Color) {
    val ux = size.width / VB_W; val uy = size.height / VB_H
    fun px(x: Float) = x * ux
    fun py(y: Float) = y * uy
    val sw = 1.3f * ux
    val s = ink.copy(alpha = 0.55f)
    drawCircle(AMBER_LIT.copy(alpha = 0.40f), 9f * ux, Offset(px(56f), py(58f)))
    drawLine(s, Offset(px(34f), py(70f)), Offset(px(34f), py(138f)), sw)
    val arm = Path().apply { moveTo(px(34f), py(70f)); q(px(34f), py(70f), px(34f), py(58f), px(46f), py(58f)); lineTo(px(52f), py(58f)) }
    drawPath(arm, s, style = Stroke(sw))
    drawCircle(s, 4.5f * ux, Offset(px(56f), py(58f)), style = Stroke(sw))
    val smoke = Path().apply {
        moveTo(px(84f), py(132f)); q(px(84f), py(132f), px(76f), py(112f), px(88f), py(100f))
        q(px(88f), py(100f), px(98f), py(90f), px(90f), py(74f))
    }
    drawPath(smoke, ink.copy(alpha = 0.40f), style = Stroke(sw))
}
