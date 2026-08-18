package com.situ.aichat.ui.offline

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.util.WallpaperBlur
import com.situ.aichat.util.WallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 线下见面「梦剧场」沉浸背景（契约 FABLE5_MEETING_THEATER_PROPOSAL.md §3 / 图纸 §4.2）。见面屏**恒暗**：
 * 一座暖色舞台，字色/幕布/舞台底一律走 [OfflineTheater]（禁直引 `MaterialTheme.colorScheme`）。
 *
 * 三种底：① 聊天壁纸（最高优先·§3.3/D8）：清晰图铺满 + 剧场幕布（暖黑竖向渐变·三挡 alpha 按壁纸亮度自适应）；
 * ② 粒子：恒暗 espresso 舞台底 + 粒子层；③ 纯色：舞台底 + 用户色 25% 叠染。
 *（原「角色专属图」底为移植死读路——`OfflineBackgrounds` 目录自始无写入方，K9·2026-07-12 清除，契约 §3 修订注记。）
 * 开场「灯暗」：幕布/舞台底 alpha 从 0 淡入 600ms（效果轴·无过冲）；reduceMotion → 瞬时落位、不绘粒子层。
 * 粒子绘制逐行照搬 iOS 故事 `WeatherParticleView` 的 stars/firefly/dust（既有·不动）。
 */
@Composable
fun OfflineBackgroundView(
    backgroundStyle: String,
    particleStyle: String,
    backgroundColor: String,
    themeColorHex: String?,
    chatWallpaperPath: String? = null,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()

    // 开场灯暗（§4.2 / §8 仪式）：目标 0→1（进组合即向 1f），600ms tween·LinearOutSlowInEasing（效果轴·无过冲）；
    // reduceMotion → 直接 1f（瞬时落位）。乘到幕布与舞台底的 alpha 上。
    var lightsUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { lightsUp = true }
    val dimAnim by animateFloatAsState(
        targetValue = if (lightsUp) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
        label = "offlineCurtainDim",
    )
    val curtainProgress = if (reduceMotion) 1f else dimAnim

    // per-角色聊天壁纸最高优先（契约 §3.3/D8·盖过粒子/纯色/角色专属图）。同 produceState 内一次算平均亮度缓存（§4.2）。
    val chatWallpaper by produceState<StageWallpaper?>(initialValue = null, chatWallpaperPath) {
        value = chatWallpaperPath?.let { path ->
            withContext(Dispatchers.IO) {
                WallpaperStore.load(path)?.let { bmp ->
                    StageWallpaper(bmp.asImageBitmap(), WallpaperBlur.averageLuminance(bmp))
                }
            }
        }
    }

    Box(modifier.fillMaxSize().background(OfflineTheater.curtain)) {
        when {
            // ① 聊天壁纸（最高优先）：清晰图铺满 + 剧场幕布（亮度自适应·× 灯暗进度·§4.2）。
            chatWallpaper != null -> {
                Image(
                    bitmap = chatWallpaper!!.image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                StageCurtain(luminance = chatWallpaper!!.luminance, progress = curtainProgress)
            }
            // 设了壁纸但仍在解码：仅底色，绝不回退粒子（防闪）。
            chatWallpaperPath != null -> Unit
            // ② 无聊天壁纸：纯色 / 粒子（皆恒暗舞台）。K9（2026-07-12）：原「角色专属图」分支为移植死读路
            //（`OfflineBackgrounds` 目录全 git 历史零写入方·恒空·分支不可达，已清除，契约 §3 修订注记）；
            // 「customImage」样式值照既有实际行为落粒子舞台底。
            else -> when (backgroundStyle) {
                "solidColor" -> SolidColorBackground(backgroundColor, curtainProgress)
                else -> ParticleBackground(particleStyle, reduceMotion, curtainProgress)
            }
        }
    }
}

/** 加载好的壁纸 + 其平均亮度（BT.601·一次算·喂 [OfflineTheater.curtainAlphas] 自适应幕布）。 */
private data class StageWallpaper(val image: ImageBitmap, val luminance: Float)

/**
 * 剧场幕布：暖黑（[OfflineTheater.curtain]）竖向渐变，顶/中/底三挡 alpha = [OfflineTheater.curtainAlphas]（luminance）
 * × 开场灯暗 [progress]（§4.2）。压在照片类背景（壁纸/角色专属图）上，白字字幕直接压其上即可读。
 */
@Composable
private fun StageCurtain(luminance: Float?, progress: Float) {
    val a = OfflineTheater.curtainAlphas(luminance)
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    OfflineTheater.curtain.copy(alpha = a[0] * progress),
                    OfflineTheater.curtain.copy(alpha = a[1] * progress),
                    OfflineTheater.curtain.copy(alpha = a[2] * progress),
                ),
            ),
        ),
    )
}

// MARK: - 粒子背景

@Composable
private fun ParticleBackground(particleStyle: String, reduceMotion: Boolean, curtainProgress: Float) {
    // 恒暗舞台底（espresso 竖向渐变·§4.2）：× 灯暗进度淡入；替掉旧「主题色渐变 + 浅色主题分支 + 黑 0.04 补丁」。
    Box(
        Modifier
            .fillMaxSize()
            .alpha(curtainProgress)
            .background(Brush.verticalGradient(listOf(OfflineTheater.stageTop, OfflineTheater.stageBottom))),
    )
    if (!reduceMotion) {
        // 星空/萤火虫/尘埃本就为暗底设计：isDark 对本屏恒 true、layerAlpha 恒深档 1f（§4.2）。
        OfflineParticleField(
            particleStyle = particleStyle,
            isDark = true,
            layerAlpha = 1f,
        )
    }
}

/** 粒子动画层：Canvas + ~30fps 时间驱动（仿 PetParticleOverlay）。 */
@Composable
private fun OfflineParticleField(particleStyle: String, isDark: Boolean, layerAlpha: Float) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33L)
            nowMs = System.currentTimeMillis()
        }
    }
    Canvas(Modifier.fillMaxSize().alpha(layerAlpha)) {
        val time = nowMs / 1000.0
        OfflineParticleDrawer.run {
            when (particleStyle) {
                "firefly" -> drawFireflies(time, isDark, FIREFLY_COUNT)
                "dust" -> drawDust(time, DUST_COUNT)
                else -> drawStars(time, STAR_COUNT)
            }
        }
    }
}

// MARK: - 纯色背景

@Composable
private fun SolidColorBackground(backgroundColor: String, curtainProgress: Float) {
    // 恒暗舞台渐变底 + 用户色 25% 整层叠染（解析失败回落纯 stage）·× 灯暗进度（§4.2）。
    Box(Modifier.fillMaxSize().alpha(curtainProgress)) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(OfflineTheater.stageTop, OfflineTheater.stageBottom))),
        )
        parseHexColorOrNull(backgroundColor)?.let { color ->
            Box(Modifier.fillMaxSize().background(color.copy(alpha = 0.25f)))
        }
    }
}

// MARK: - 粒子绘制引擎（1:1 iOS StoryWeatherDrawer 的 stars/firefly/dust + random）

private const val STAR_COUNT = 60
private const val FIREFLY_COUNT = 20
private const val DUST_COUNT = 45

private object OfflineParticleDrawer {

    /** 确定性伪随机：fract(sin(index*97 + salt*31) * 43758.5453)（1:1 iOS random）。 */
    private fun random(index: Int, salt: Int): Double {
        val value = sin((index * 97 + salt * 31).toDouble()) * 43758.5453
        return value - floor(value)
    }

    /** 归一化到 [0,1)（对应 iOS truncatingRemainder(dividingBy:1) 的正值规整）。 */
    private fun wrap01(x: Double): Double = x - floor(x)

    /** 星空：固定位置，透明度正弦呼吸（1:1 iOS drawStars）。 */
    fun DrawScope.drawStars(time: Double, count: Int) {
        for (i in 0 until count) {
            val x = (random(i, 490) * size.width).toFloat()
            val y = (random(i, 501) * size.height).toFloat()
            val period = 0.3 + random(i, 512) * 0.4
            val phase = random(i, 523) * PI * 2
            val breath = (sin(time * period + phase) + 1) / 2
            val opacity = (0.1 + breath * 0.5).toFloat().coerceIn(0f, 1f)
            val radius = (1.5 + random(i, 534) * 1.5).toFloat()
            val isGold = random(i, 545) > 0.6
            val color = if (isGold) Color(0xFFFFF8E1) else Color.White
            drawOval(color.copy(alpha = opacity), topLeft = Offset(x, y), size = Size(radius, radius))
        }
    }

    /** 萤火虫：暖色发光点漂浮 + 脉动（1:1 iOS drawFireflies）。 */
    fun DrawScope.drawFireflies(time: Double, isDark: Boolean, count: Int) {
        val baseColor = if (isDark) Color(0xFFFFEB3B) else Color(0xFFFF9800)
        val baseAlpha = if (isDark) 0.45 else 0.28
        for (i in 0 until count) {
            val baseX = random(i, 140) * size.width
            val baseY = random(i, 151) * size.height
            val x = (baseX + sin(time * (0.35 + random(i, 162)) + random(i, 173) * PI) * 18).toFloat()
            val y = (baseY + cos(time * (0.28 + random(i, 184)) + random(i, 195) * PI) * 16).toFloat()
            val pulse = (sin(time * (1.2 + random(i, 206)) + random(i, 217) * PI * 2) + 1) / 2
            val radius = (3 + pulse * 3).toFloat()
            val alpha = (baseAlpha * (0.35 + pulse * 0.25)).toFloat().coerceIn(0f, 1f)
            drawOval(baseColor.copy(alpha = alpha), topLeft = Offset(x, y), size = Size(radius, radius))
        }
    }

    /** 尘埃：暖白微粒缓慢上升 + 布朗抖动（1:1 iOS drawDust）。 */
    fun DrawScope.drawDust(time: Double, count: Int) {
        for (i in 0 until count) {
            val seedX = random(i, 430)
            val seedY = random(i, 441)
            val brownX = sin(time * (0.5 + random(i, 452)) + random(i, 463) * PI * 4) * 8
            val brownY = cos(time * (0.4 + random(i, 474)) + random(i, 485) * PI * 4) * 6
            val rise = time * 0.012
            val x = (seedX * size.width + brownX).toFloat()
            val y = (wrap01(seedY - rise) * size.height + brownY).toFloat()
            val radius = (1 + random(i, 496) * 1).toFloat()
            val opacity = (0.15 + random(i, 507) * 0.10).toFloat()
            drawOval(Color(0xFFFFFBF0).copy(alpha = opacity), topLeft = Offset(x, y), size = Size(radius, radius))
        }
    }
}
