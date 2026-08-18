package com.situ.aichat.ui.starfield

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 记忆星空**环境层**渲染 + 帧时钟宿主 + 全页锁定色板（图纸 2026-07-16-记忆星空 §4.1/§4.2/§4.4/§4.5·
 * **全部数值锁定**）：夜幕 / 银河 / 尘星，全 Canvas 原语零素材零第三方。内容层（记忆星/连线/月份标/
 * 月/流星）见 `StarfieldStarPainter.kt`（同包·拆分理由见施工日志 D-11）。
 *
 * **帧时钟机制锁**（§9）：单 `LaunchedEffect(animate)` 驱动一个 `timeMillis`，在 Canvas 的 draw
 * lambda 内读 → **只重绘不重组**（禁逐星 `rememberInfiniteTransition`）。`animate == false`
 * （reduceMotion）时不启动循环 = 静态一帧。
 *
 * 恒暗页不走主题 token（沿用见面屏恒暗先例），色值为 §4 锁定字面值。
 */

/** §4 锁定色值。 */
internal val SkyStop0 = Color(0xFF0A0D1E)
internal val SkyStop46 = Color(0xFF141A33)
internal val SkyStop78 = Color(0xFF232947)
internal val SkyStop100 = Color(0xFF332F4E)
/** 顶部深邃 rgba(6,8,20,·) / 地平微光 rgba(96,78,102,·) / 暗角 rgba(4,5,12,·)——透明端同 RGB 配 α0，
 * 防 `Color.Transparent` 的黑分量参与插值出偏黑伪影（MeetingSkyPalette R1 🔴-1 同款教训）。 */
internal val DeepTop = Color(0xFF060814)
internal val HorizonGlow = Color(0xFF604E66)
internal val VignetteInk = Color(0xFF04050C)
/** 银河雾色基 rgba(226,222,240)。 */
internal val GalaxyBase = Color(0xFFE2DEF0)
/** 暖白 rgba(237,232,226) 单源：§4.2 微星色基 = §4.3 连线 = §4.7 chrome 文字 = §4.8 sheet 文字，按 α 分角色。 */
internal val WarmWhite = Color(0xFFEDE8E2)
/** 白核 #FFF8EF；月 #EFE6CF。 */
internal val StarCore = Color(0xFFFFF8EF)
internal val MoonDisc = Color(0xFFEFE6CF)
/** sheet 深玻璃近似底（§4.8·D-0 明示降级：Compose ModalBottomSheet 无背景实时模糊）+ 1dp 描边。 */
internal val SheetContainer = Color(0xE0151A2C)
internal val SheetStroke = Color(0xFFEDE8E2).copy(alpha = 0.1f)

/** 晕色（§4.3 锁定三元组）。 */
internal val HaloMeeting = Color(0xFFE8C77B) // 232,199,123
internal val HaloPromise = Color(0xFFA9C5BE) // 169,197,190
internal val HaloMilestone = Color(0xFFE2DEF0) // 226,222,240

internal fun haloColorOf(type: StarType): Color = when (type) {
    StarType.MEETING -> HaloMeeting
    StarType.PROMISE -> HaloPromise
    StarType.MILESTONE -> HaloMilestone
}

private const val GALAXY_TILT_DEG = -24f
private const val TWO_PI = (2.0 * PI).toFloat()
private const val NOVA_HALF_PERIOD_MS = 1200f
private const val MICRO_STAR_COUNT = 150
private const val DUST_COUNT = 88
internal const val METEOR_MS = 1200
/** 图纸未给的尾线宽（TODO-1）。 */
internal const val METEOR_TAIL_WIDTH_DP = 1.2f

/** 三层椭圆雾 [rxF, ryF, α]（§4.2）。 */
private val GalaxyFog = listOf(
    Triple(0.85f, 0.13f, 0.05f),
    Triple(0.60f, 0.085f, 0.07f),
    Triple(0.38f, 0.055f, 0.08f),
)

/** 尘星色温权重表（§4.4 锁定）：暖橙 .25 / 暖白 .35 / 冷白 .25 / 淡蓝 .15。 */
private val DustPalette = listOf(
    Color(0xFFFFD6AA) to 0.25f,
    Color(0xFFFFF1E0) to 0.35f,
    Color(0xFFF0F0F4) to 0.25f,
    Color(0xFFCBD8F7) to 0.15f,
)

// ── 帧时钟宿主 ──────────────────────────────────────────────────────────────

/**
 * 星空主画布（画布空间·外层由 Screen 套相机 `graphicsLayer`）：银河 → 尘星 → 连线 →
 * 记忆星 → 楷体月份标。[seed] = characterUuid.hashCode()（尘星/微星散布确定性·§9 禁无种 Random）。
 *
 * **夜幕不在此层**（R1 后修 F-1·2026-07-16）：夜幕四层（主渐变/顶部深邃/地平微光/暗角）由 Screen 画在
 * **相机外的固定视口层**（[drawSkyBase]）——天幕是无限远背景「星移天不移」，且相机 pan/缩小时画布外
 * 区域必须仍有天幕（旧结构横拖/0.8× 缩小会露出容器底色成「隔断」，真机实证）；暗角属镜头效果恒框视口。
 */
@Composable
internal fun StarfieldSkyCanvas(
    clusters: List<StarCluster>,
    selectedId: String?,
    seed: Int,
    animate: Boolean,
    widthDp: Float,
    canvasHeightDp: Float,
    modifier: Modifier = Modifier,
) {
    // 单帧时钟：只在动画开时空转；draw lambda 内读 → 只重绘不重组（§4.5 机制锁）。
    var timeMillis by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { ms -> timeMillis = (ms - start).toFloat() }
        }
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // 尘星散布只随画布尺寸重算（禁每帧无种 Random——布局会每帧跳变·§9）。
    val dust = remember(seed, widthDp, canvasHeightDp, density) {
        makeDust(seed, with(density) { Size(widthDp.dp.toPx(), canvasHeightDp.dp.toPx()) }, density)
    }

    Canvas(modifier) {
        drawGalaxy(seed)
        drawDust(dust, timeMillis, animate)
        val novaAlpha = novaHaloAlpha(timeMillis, animate)
        clusters.forEach { cluster ->
            drawLinks(cluster.links)
            cluster.stars.forEach { drawStar(it, selected = it.node.id == selectedId, novaAlpha = novaAlpha) }
            drawMonthLabel(cluster, measurer)
        }
    }
}

/** nova 外晕呼吸（§4.5·全周期 2400ms·常态区间 .10–.26）；reduceMotion 恒 .18。 */
internal fun novaHaloAlpha(timeMillis: Float, animate: Boolean): Float =
    if (!animate) 0.18f else 0.10f + 0.16f * (0.5f + 0.5f * sin(PI.toFloat() * timeMillis / NOVA_HALF_PERIOD_MS))

// ── §4.1 夜幕 ───────────────────────────────────────────────────────────────

/** 主渐变 + 顶部深邃 + 地平微光 + 暗角（画布高于视口时以**画布**尺寸绘制·§4.1-5）。 */
internal fun DrawScope.drawSkyBase() {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.linearGradient(
            0f to SkyStop0, 0.46f to SkyStop46, 0.78f to SkyStop78, 1f to SkyStop100,
            start = Offset(0f, 0f), end = Offset(0f, h),
        ),
    )
    radialWash(Offset(w / 2f, -0.15f * h), 0.8f * h, DeepTop, 0.6f)
    radialWash(Offset(w / 2f, 1.06f * h), 0.55f * h, HorizonGlow, 0.4f)
    // 暗角：透明 → rgba(4,5,12,.5)，自 0.42×min(w,h) 起、至 0.78×max(w,h)。
    val outer = 0.78f * max(w, h)
    val innerStop = (0.42f * min(w, h) / outer).coerceIn(0f, 1f)
    drawRect(
        Brush.radialGradient(
            0f to VignetteInk.copy(alpha = 0f),
            innerStop to VignetteInk.copy(alpha = 0f),
            1f to VignetteInk.copy(alpha = 0.5f),
            center = Offset(w / 2f, 0.45f * h),
            radius = outer,
        ),
    )
}

internal fun DrawScope.radialWash(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center = center, radius = radius),
        radius = radius,
        center = center,
    )
}

// ── §4.2 银河 ───────────────────────────────────────────────────────────────

/** 轴心 (0.5w, 0.42h)、倾角 -24°：三层椭圆雾 + 150 颗静态微星（种子 seed+7·不闪）。 */
internal fun DrawScope.drawGalaxy(seed: Int) {
    val axis = Offset(size.width * 0.5f, size.height * 0.42f)
    GalaxyFog.forEach { (rxF, ryF, alpha) ->
        val rx = size.width * rxF
        val ry = size.height * ryF
        withTransform({
            rotate(GALAXY_TILT_DEG, axis)
            scale(rx / ry, 1f, axis)
        }) {
            drawCircle(
                Brush.radialGradient(
                    0f to GalaxyBase.copy(alpha = alpha),
                    0.6f to GalaxyBase.copy(alpha = alpha * 0.4f),
                    1f to GalaxyBase.copy(alpha = 0f),
                    center = axis, radius = ry,
                ),
                radius = ry, center = axis,
            )
        }
    }
    val rnd = Random(seed + 7)
    repeat(MICRO_STAR_COUNT) {
        val lx = (rnd.nextFloat() * 2f - 1f) * 0.75f * size.width
        val ly = rnd.nearGaussian() * 0.22f * size.height
        val p = axis + rotateOnBand(lx, ly)
        drawCircle(
            WarmWhite.copy(alpha = 0.12f + rnd.nextFloat() * 0.30f),
            radius = (0.25f + rnd.nextFloat() * 0.70f).dp.toPx(),
            center = p,
        )
    }
}

/** 带轴坐标 → 画布坐标（绕轴心转 -24°）。 */
private fun rotateOnBand(lx: Float, ly: Float): Offset {
    val rad = GALAXY_TILT_DEG * PI.toFloat() / 180f
    return Offset(lx * cos(rad) - ly * sin(rad), lx * sin(rad) + ly * cos(rad))
}

/** 三均值近高斯 → [-1, 1] 中心密集。 */
private fun Random.nearGaussian(): Float = ((nextFloat() + nextFloat() + nextFloat()) / 3f) * 2f - 1f

// ── §4.4 尘星 ───────────────────────────────────────────────────────────────

/** 一颗尘星（[periodMs]/[phase] = 每星随机的闪烁参数·§4.4）。 */
internal data class DustStar(
    val center: Offset,
    val radiusPx: Float,
    val color: Color,
    val baseAlpha: Float,
    val glow: Float,
    val periodMs: Float,
    val phase: Float,
)

/** 88 颗·种子固定（§4.4）：45% 落银河带（带轴高斯偏移 0.3h·见 D-9），其余全画布均匀。 */
internal fun makeDust(seed: Int, canvas: Size, density: Density): List<DustStar> {
    val rnd = Random(seed)
    val axis = Offset(canvas.width * 0.5f, canvas.height * 0.42f)
    val marginX = with(density) { 4.dp.toPx() }
    val marginTop = with(density) { 30.dp.toPx() }
    val marginBottom = with(density) { 70.dp.toPx() }
    return List(DUST_COUNT) {
        val p = if (rnd.nextFloat() < 0.45f) {
            axis + rotateOnBand((rnd.nextFloat() * 2f - 1f) * 0.75f * canvas.width, rnd.nearGaussian() * 0.3f * canvas.height)
        } else {
            Offset(rnd.nextFloat() * canvas.width, rnd.nextFloat() * canvas.height)
        }
        val bright = rnd.nextFloat()
        val (radiusDp, glow) = when {
            bright < 0.70f -> (0.6f + rnd.nextFloat() * 0.5f) to 0f
            bright < 0.90f -> (1.0f + rnd.nextFloat() * 0.5f) to 0f
            bright < 0.97f -> (1.4f + rnd.nextFloat() * 0.5f) to 0.5f
            else -> (1.8f + rnd.nextFloat() * 0.6f) to 1.0f
        }
        DustStar(
            center = Offset(
                p.x.coerceIn(marginX, canvas.width - marginX),
                p.y.coerceIn(marginTop, (canvas.height - marginBottom).coerceAtLeast(marginTop)),
            ),
            radiusPx = with(density) { radiusDp.dp.toPx() },
            color = rnd.pickDustColor(),
            baseAlpha = 0.38f + rnd.nextFloat() * 0.25f,
            glow = glow,
            periodMs = (2.6f + rnd.nextFloat() * 2.4f) * 1000f,
            phase = rnd.nextFloat() * TWO_PI,
        )
    }
}

private fun Random.pickDustColor(): Color {
    var roll = nextFloat()
    DustPalette.forEach { (color, weight) ->
        if (roll < weight) return color
        roll -= weight
    }
    return DustPalette.last().first
}

/** 闪烁（§4.4）：α = base × (0.55 + 0.75×tw)，tw = 0.6 + 0.4×sin(2πt/周期 + 相位)，封顶 .95。 */
internal fun DrawScope.drawDust(dust: List<DustStar>, timeMillis: Float, animate: Boolean) {
    dust.forEach { d ->
        val tw = if (!animate) 0.6f else 0.6f + 0.4f * sin(TWO_PI * timeMillis / d.periodMs + d.phase)
        val alpha = (d.baseAlpha * (0.55f + 0.75f * tw)).coerceAtMost(0.95f)
        if (d.glow > 0f) {
            val glowR = d.radiusPx * 5f
            drawCircle(
                Brush.radialGradient(
                    listOf(d.color.copy(alpha = alpha * 0.5f * d.glow), d.color.copy(alpha = 0f)),
                    center = d.center, radius = glowR,
                ),
                radius = glowR, center = d.center,
            )
        }
        drawCircle(d.color.copy(alpha = alpha), radius = d.radiusPx, center = d.center)
    }
}
