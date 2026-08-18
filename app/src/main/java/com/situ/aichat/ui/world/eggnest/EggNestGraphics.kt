package com.situ.aichat.ui.world.eggnest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

// ── 蛋巢自绘字面量（mockup nestSvg 逐值转录·§4.1·奶油蛋壳 / 编织巢 / speckle 用之约角色头像色）──
internal val EggGradTop = Color(0xFFFFFDF7)     // eg 0%
internal val EggGradMid = Color(0xFFF6EBD9)     // eg 70%
internal val EggGradBottom = Color(0xFFE9D9BE)  // eg 100%
internal val NestGradTop = Color(0xFFC2A26B)    // ng 0%
internal val NestGradBottom = Color(0xFF8A6B4E) // ng 100%
private val WeaveLight = Color(0xFFE3C68F)      // 亮编织线
private val WeaveDark = Color(0xFF6E5238)       // 暗编织线
private val EggHighlight = Color(0x8CFFFFFF)    // 左上高光 白 α.55

/**
 * 巢 + 蛋自绘（§4.1·mockup nestSvg viewBox 120×86 逐值转录·1px=1dp 换算到本 [DrawScope] 尺寸）。
 * 巢/蛋主体 + speckle + 高光 = 此单源（overlay 全尺寸 + pact 确认小卡 88×63 复用·「同 4.1 绘制缩放」）。
 * 名牌 / 底标签 / 光晕 / 庆祝在 overlay 各自叠层，不在此。[eggRotationDeg] = 轻晃/轻摇（overlay 传·pact 静=0）。
 */
internal fun DrawScope.drawEggNest(withEgg: Boolean, eggColor: Color, eggRotationDeg: Float = 0f) {
    val sx = size.width / 120f
    val sy = size.height / 86f
    val s = (sx + sy) / 2f

    if (withEgg) {
        // transform-origin 50% 88% → 旋转支点 (60, 0.88×86=75.68)。
        rotate(eggRotationDeg, pivot = Offset(60f * sx, 75.68f * sy)) {
            val eggRx = 21f * sx
            val eggRy = 27f * sy
            drawOval(
                brush = Brush.radialGradient(
                    0f to EggGradTop, 0.70f to EggGradMid, 1f to EggGradBottom,
                    center = Offset(56.64f * sx, 35.2f * sy), // 42%,30% of 蛋 bbox
                    radius = 40f * s,
                ),
                topLeft = Offset(60f * sx - eggRx, 46f * sy - eggRy),
                size = Size(eggRx * 2, eggRy * 2),
            )
            // 左上高光 12×18dp @ (53,36)。
            drawOval(
                color = EggHighlight,
                topLeft = Offset(53f * sx - 6f * sx, 36f * sy - 9f * sy),
                size = Size(12f * sx, 18f * sy),
            )
            // 4 颗 speckle（之约角色头像色 α.5·位置照 mockup）。
            listOf(
                Triple(66f, 38f, 1.8f), Triple(56f, 52f, 1.5f),
                Triple(68f, 55f, 1.3f), Triple(61f, 28f, 1.3f),
            ).forEach { (cx, cy, r) ->
                drawCircle(eggColor.copy(alpha = 0.5f), radius = r * s, center = Offset(cx * sx, cy * sy))
            }
        }
    }

    // 巢体（三层弧线编织·底填充线性渐变）。
    val nest = Path().apply {
        moveTo(18f * sx, 52f * sy)
        quadraticTo(60f * sx, 34f * sy, 102f * sx, 52f * sy)
        lineTo(98f * sx, 66f * sy)
        quadraticTo(60f * sx, 84f * sy, 22f * sx, 66f * sy)
        close()
    }
    drawPath(nest, Brush.verticalGradient(0f to NestGradTop, 1f to NestGradBottom))
    weave(sx, sy, s, 22f, 52f, 38f, 98f, 52f, WeaveLight.copy(alpha = 0.80f), 2.5f)
    weave(sx, sy, s, 26f, 58f, 44f, 94f, 58f, WeaveDark.copy(alpha = 0.55f), 2.0f)
    weave(sx, sy, s, 30f, 64f, 52f, 90f, 64f, WeaveLight.copy(alpha = 0.45f), 1.6f)
}

/** 一条编织弧线（M x0 y0 Q 60 ctrlY x1 y1·stroke 圆头）。 */
private fun DrawScope.weave(
    sx: Float, sy: Float, s: Float,
    x0: Float, y0: Float, ctrlY: Float, x1: Float, y1: Float,
    color: Color, widthUnits: Float,
) {
    val p = Path().apply {
        moveTo(x0 * sx, y0 * sy)
        quadraticTo(60f * sx, ctrlY * sy, x1 * sx, y1 * sy)
    }
    drawPath(p, color, style = Stroke(width = widthUnits * s, cap = StrokeCap.Round))
}
