package com.situ.aichat.ui.offline

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.situ.aichat.util.MoonPhase
import kotlin.random.Random

// 见面回忆「那晚的天色」绘制（SKY-1）：渐变 → 星 → 月 → 天气 → 底纱，全部 Canvas 原语零素材。
// 静态画无动画（契约 §4：星星呼吸等 delight 显式本期不做）。图纸 §0.7 R1 勘误：天气铺设区与文字带
// 并非全错位（霞带交叠活动带尾/meta 带首），对比度由 ColorContrastTest 真实文字带扫描保守核。

/**
 * 霞带几何（y0 / 高 / α·GLOW_BANDS 用）——与文字带 y 交叠（活动带尾 0.68–0.71、meta 带首 0.79–0.80），
 * ColorContrastTest 带扫描按整带叠加保守核对比度：改任一值或增删条带必须重跑带扫描。
 * x 向铺设仍在 [drawMeetingSky] 的 skyBand 调用处（只影响横向覆盖，保守口径不消费）。
 */
internal val SKY_GLOW_BANDS = listOf(
    Triple(0.68f, 0.055f, 0.26f),
    Triple(0.755f, 0.048f, 0.18f),
)

/** 月亮渲染参数；照亮率 <5%（朔前后）不画月只留星。 */
internal data class MoonRender(val illumination: Float, val waxing: Boolean)

internal fun moonRenderFor(spec: SkySpec, startMillis: Long): MoonRender? {
    if (spec.moonAlpha <= 0f) return null
    val ill = MoonPhase.illumination(startMillis).toFloat()
    if (ill < 0.05f) return null
    return MoonRender(ill, MoonPhase.isWaxing(startMillis))
}

/** 天色背景：星野以 [seed]（session.id.hashCode()）确定性散布——同一场见面永远是同一片星空。 */
@Composable
internal fun MeetingSkyBackdrop(spec: SkySpec, seed: Int, startMillis: Long, modifier: Modifier = Modifier) {
    val moon = remember(spec, startMillis) { moonRenderFor(spec, startMillis) }
    Canvas(modifier) { drawMeetingSky(spec, seed, moon) }
}

internal fun DrawScope.drawMeetingSky(spec: SkySpec, seed: Int, moon: MoonRender?) {
    drawRect(Brush.verticalGradient(spec.stops))
    val rnd = Random(seed)

    repeat(spec.starCount) {
        val x = size.width * (0.05f + 0.90f * rnd.nextFloat())
        val y = size.height * (0.06f + 0.46f * rnd.nextFloat())
        val r = (0.9f + rnd.nextFloat() * 0.7f).dp.toPx()
        val a = (0.40f + rnd.nextFloat() * 0.35f + spec.starAlphaBoost).coerceAtMost(0.85f)
        drawCircle(MeetingSky.WarmWhite.copy(alpha = a), radius = r, center = Offset(x, y))
    }

    if (moon != null && spec.moonAlpha > 0f) {
        // 半径随画布自适应（SKY-5 修正·mockup 口径）：全宽窗景 = 11dp；52dp 书签条 ≈ 5.7dp（占宽 ~22%）。
        val r = minOf(11.dp.toPx(), size.width * 0.11f)
        val cx = size.width - r - 16.dp.toPx() - rnd.nextFloat() * 6.dp.toPx()
        val cy = 26.dp.toPx() + rnd.nextFloat() * 6.dp.toPx()
        drawCircle(MeetingSky.Moon.copy(alpha = spec.moonAlpha), radius = r, center = Offset(cx, cy))
        // 阴影盘只在月盘内绘制（clipPath），偏移 = 2r×照亮率：朔全遮 → 望离场；盈亏定滑出方向。
        val skyAtMoon = lerp(spec.stops[0], spec.stops[1], ((cy / size.height) * 2f).coerceIn(0f, 1f))
        val shadowOffset = 2f * r * moon.illumination
        val shadowCx = if (moon.waxing) cx - shadowOffset else cx + shadowOffset
        val disc = Path().apply { addOval(Rect(center = Offset(cx, cy), radius = r)) }
        clipPath(disc) {
            drawCircle(skyAtMoon, radius = r * 1.02f, center = Offset(shadowCx, cy))
        }
    }

    when (spec.weather) {
        // 霞带（y 0.68–0.80·入纱区且与活动/meta 带交叠）：y/高/α 单源 = SKY_GLOW_BANDS。
        SkyWeather.GLOW_BANDS -> {
            val (b1, b2) = SKY_GLOW_BANDS
            skyBand(0.02f, b1.first, 0.55f, b1.second, spec.weatherColor.copy(alpha = b1.third))
            skyBand(0.24f, b2.first, 0.62f, b2.second, spec.weatherColor.copy(alpha = b2.third))
        }
        // 雾带（y 0.28–0.46·日期行与地点行之间的空档）。
        SkyWeather.FOG -> {
            skyBand(0f, 0.28f, 1f, 0.05f, spec.weatherColor.copy(alpha = 0.10f))
            skyBand(0f, 0.36f, 1f, 0.05f, spec.weatherColor.copy(alpha = 0.08f))
            skyBand(0f, 0.44f, 1f, 0.05f, spec.weatherColor.copy(alpha = 0.06f))
        }
        // 云团（y 0.30–0.45·同空档；后于月绘制 = 纱月效果）。
        SkyWeather.CLOUDS -> {
            skyBand(0.04f, 0.30f, 0.42f, 0.09f, spec.weatherColor.copy(alpha = 0.09f))
            skyBand(0.30f, 0.40f, 0.34f, 0.075f, spec.weatherColor.copy(alpha = 0.07f))
            skyBand(0.62f, 0.24f, 0.30f, 0.07f, spec.weatherColor.copy(alpha = 0.06f))
        }
        // 暖阳光晕（白天×温暖·右上角，与左上日期行错开）。
        SkyWeather.SUN_HALO -> {
            val c = Offset(size.width - 34.dp.toPx(), 30.dp.toPx())
            val radius = 34.dp.toPx()
            drawCircle(
                Brush.radialGradient(
                    listOf(spec.weatherColor.copy(alpha = 0.60f), spec.weatherColor.copy(alpha = 0f)),
                    center = c,
                    radius = radius,
                ),
                radius = radius,
                center = c,
            )
        }
        SkyWeather.NONE -> Unit
    }

    if (spec.bottomHaze) {
        // 两段折线纱（R1 🔴-1）：三停 Brush 与 MeetingSky.hazeAlphaAt 严格同构（拐点=meta 带起点）。
        val top = size.height * MeetingSky.HAZE_START
        val kneeFraction = (MeetingSky.HAZE_KNEE_Y - MeetingSky.HAZE_START) / (1f - MeetingSky.HAZE_START)
        drawRect(
            Brush.verticalGradient(
                0f to MeetingSky.Haze.copy(alpha = 0f),
                kneeFraction to MeetingSky.Haze.copy(alpha = MeetingSky.HAZE_KNEE_ALPHA),
                1f to MeetingSky.Haze.copy(alpha = MeetingSky.HAZE_ALPHA),
                startY = top,
                endY = size.height,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, size.height - top),
        )
    }
}

private fun DrawScope.skyBand(xF: Float, yF: Float, wF: Float, hF: Float, color: Color) {
    val h = size.height * hF
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * xF, size.height * yF),
        size = Size(size.width * wF, h),
        cornerRadius = CornerRadius(h / 2f, h / 2f),
    )
}
