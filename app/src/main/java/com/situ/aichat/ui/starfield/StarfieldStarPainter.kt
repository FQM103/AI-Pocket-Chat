package com.situ.aichat.ui.starfield

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.util.MoonPhase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 记忆星空**内容层**绘制（图纸 §4.3/§4.5/§4.6/§4.7）：记忆星与连线、楷体月份标、月、流星。
 * 环境层（夜幕/银河/尘星）与色板、帧时钟宿主在 [StarfieldSkyCanvas]（`StarfieldCanvas.kt`）。
 *
 * 拆分自 StarfieldCanvas.kt（**只搬不改**·行为字节级不变）——原单文件 462 行越 CLAUDE.md §2
 * 新文件 ≤300 目标；见施工日志 D-11。
 */

// ── §4.3 记忆星与连线 ───────────────────────────────────────────────────────

/** 分层次序锁定：外晕 → 内晕 → 十字芒（仅 hero）→ 白核 → 核辉 → 选中环。 */
internal fun DrawScope.drawStar(star: PlacedStar, selected: Boolean, novaAlpha: Float) {
    val core = star.radiusDp.dp.toPx()
    val depth = star.alpha
    val halo = haloColorOf(star.node.type)
    val center = Offset(star.xDp.dp.toPx(), star.yDp.dp.toPx())

    val outerR = core * 7f
    val outerAlpha = if (star.node.nova) novaAlpha else 0.15f
    radialWash(center, outerR, halo, outerAlpha * depth)
    radialWash(center, core * 3f, halo, 0.45f * depth)
    if (star.hero) {
        val len = core * 3.4f
        listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f)).forEach { dir ->
            val end = center + Offset(dir.x * len, dir.y * len)
            drawLine(
                Brush.linearGradient(
                    listOf(StarCore.copy(alpha = 0.5f * depth), StarCore.copy(alpha = 0f)),
                    start = center, end = end,
                ),
                start = center, end = end, strokeWidth = 0.8.dp.toPx(),
            )
        }
    }
    drawCircle(StarCore.copy(alpha = depth), radius = core, center = center)
    radialWash(center, core * 1.6f, Color.White, 0.85f * depth)
    if (selected) {
        drawCircle(
            halo.copy(alpha = 0.45f * depth),
            radius = core + 6.dp.toPx(),
            center = center,
            style = Stroke(width = 1.6.dp.toPx()),
        )
    }
}

/** 虚线 dash[2,4]、宽 0.8dp、rgba(237,232,226,.12)（§4.3）。 */
internal fun DrawScope.drawLinks(links: List<StarLink>) {
    if (links.isEmpty()) return
    val effect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
    links.forEach { l ->
        drawLine(
            color = WarmWhite.copy(alpha = 0.12f),
            start = Offset(l.fromXDp.dp.toPx(), l.fromYDp.dp.toPx()),
            end = Offset(l.toXDp.dp.toPx(), l.toYDp.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
            pathEffect = effect,
        )
    }
}

// ── §4.7 楷体月份标（Canvas 内）───────────────────────────────────────────────

/** 近簇 13sp α.5 字距 4sp → 远簇递减（12.5/.46、11.5/.34）；簇心偏移 (-52, -38) dp。 */
internal fun DrawScope.drawMonthLabel(cluster: StarCluster, measurer: TextMeasurer) {
    val (sizeSp, alpha) = when (cluster.depthIndex) {
        0 -> 13f to 0.5f
        1 -> 12.5f to 0.46f
        else -> 11.5f to 0.34f
    }
    val layout = measurer.measure(
        cluster.label,
        style = TextStyle(
            fontFamily = AppTypography.kaiFontFamily,
            fontSize = sizeSp.sp,
            letterSpacing = 4.sp,
            color = WarmWhite.copy(alpha = alpha),
        ),
    )
    drawText(
        layout,
        topLeft = Offset(
            (cluster.centerXDp + StarfieldLayout.LABEL_OFFSET_X_DP).dp.toPx(),
            (cluster.centerYDp + StarfieldLayout.LABEL_OFFSET_Y_DP).dp.toPx(),
        ),
    )
}

// ── §4.6 月（视口系·静态）────────────────────────────────────────────────────

/** 月盒边长（容得下 1.9r 外辉）——盒心即 §4.6 的月心 (视口宽-44, 92)。 */
internal val MoonBoxSize = 44.dp
internal val MoonCenterFromRight = 44.dp
internal val MoonCenterFromTop = 92.dp

/**
 * 真实月相（[MoonPhase]）月牙：亮盘 + 外辉 1.9r + clipPath 内 [BlendMode.Clear] 减圆（阴影处透出星空）。
 * 照亮率 <5% 不画月（同天色卡口径）。[CompositingStrategy.Offscreen] 把 Clear 圈在本层内，绝不擦穿背景。
 */
@Composable
internal fun StarfieldMoon(nowMillis: Long, modifier: Modifier = Modifier) {
    val illumination = remember(nowMillis) { MoonPhase.illumination(nowMillis).toFloat() }
    if (illumination < 0.05f) return
    val waxing = remember(nowMillis) { MoonPhase.isWaxing(nowMillis) }
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val r = 10.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        radialWash(center, r * 1.9f, MoonDisc, 0.1f)
        drawCircle(MoonDisc, radius = r, center = center)
        val shadowOffset = 2f * r * illumination
        val shadowCx = if (waxing) center.x - shadowOffset else center.x + shadowOffset
        clipPath(Path().apply { addOval(Rect(center = center, radius = r)) }) {
            drawCircle(Color.Black, radius = r * 1.02f, center = Offset(shadowCx, center.y), blendMode = BlendMode.Clear)
        }
    }
}

// ── §4.5 流星（视口系·单发 1200ms）───────────────────────────────────────────

/**
 * 单发流星：35° 角、尾长 88dp、头部亮点 r=1.3dp；起点 (0.78w, 0.12h)、位移 0.55w（**视口系**·随相机可见）。
 * 包络：前 15% 淡入 / 后 25% 淡出。播完回调 [onPlayed]。reduceMotion 由调用方门控（[visible] 恒 false）。
 */
@Composable
internal fun StarfieldMeteor(visible: Boolean, onPlayed: () -> Unit, modifier: Modifier = Modifier) {
    if (!visible) return
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(METEOR_MS, easing = LinearEasing))
        onPlayed()
    }
    Canvas(modifier) {
        val t = progress.value
        val envelope = when {
            t < 0.15f -> t / 0.15f
            t > 0.75f -> (1f - t) / 0.25f
            else -> 1f
        }
        val rad = 35f * PI.toFloat() / 180f
        val dir = Offset(cos(rad), sin(rad))
        val head = Offset(size.width * 0.78f, size.height * 0.12f) + dir * (0.55f * size.width * t)
        val tail = head - dir * 88.dp.toPx()
        // TODO(图纸未覆盖): §4.5 给了尾长/角度/尾渐变/头部亮点 r，未给**尾线宽**。取 1.2dp（略细于
        // 头部亮点直径 2.6dp = 尾自头部收窄的常见画法）。见施工日志 TODO-1，留复核裁决。
        drawLine(
            Brush.linearGradient(
                0f to StarCore.copy(alpha = 0.9f * envelope),
                0.5f to HaloMeeting.copy(alpha = 0.4f * envelope),
                1f to HaloMeeting.copy(alpha = 0f),
                start = head, end = tail,
            ),
            start = head, end = tail, strokeWidth = METEOR_TAIL_WIDTH_DP.dp.toPx(),
        )
        drawCircle(StarCore.copy(alpha = envelope), radius = 1.3.dp.toPx(), center = head)
    }
}
