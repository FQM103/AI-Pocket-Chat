package com.situ.aichat.ui.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.situ.aichat.pet.PetSpecies
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 照顾操作触发的粒子特效（1:1 iOS `PetParticleEffect`）。 */
enum class PetParticleEffect { FEED, CLEAN, PLAY, EVOLVE }

/**
 * 粒子特效叠加层（1:1 iOS `PetParticleOverlay`）：环境粒子常驻 + 动作粒子（喂食下落/清洁气泡/玩耍心形/
 * 进化彩纸）触发后 2-3s 自动消失。Canvas + 时间驱动；确定性伪随机（按粒子索引 hash）保每帧一致。
 */
@Composable
fun PetParticleOverlay(
    performance: PetVisualPerformance,
    activeEffect: PetParticleEffect?,
    effectStartMillis: Long?,
    species: PetSpecies,
    petCenterRatioY: Float,
    modifier: Modifier = Modifier,
) {
    if (!performance.allowsParticles) return
    val frameMs = (performance.particleFrameInterval * 1000).toLong().coerceAtLeast(1L)
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(frameMs) {
        while (true) {
            delay(frameMs)
            nowMs = System.currentTimeMillis()
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val time = nowMs / 1000.0
        PetParticleDrawer.run { drawAmbient(time, performance.ambientParticleCount) }
        if (activeEffect != null && effectStartMillis != null) {
            val elapsed = (nowMs - effectStartMillis) / 1000.0
            val duration = if (activeEffect == PetParticleEffect.EVOLVE) 3.0 else 2.0
            if (elapsed in 0.0..duration) {
                val progress = elapsed / duration
                val origin = Offset(size.width / 2f, size.height * petCenterRatioY)
                PetParticleDrawer.run {
                    when (activeEffect) {
                        PetParticleEffect.FEED -> drawFeed(time, progress, species, performance.feedParticleCount)
                        PetParticleEffect.CLEAN -> drawClean(time, progress, performance.cleanParticleCount)
                        PetParticleEffect.PLAY -> drawPlay(time, progress, origin, performance.playParticleCount)
                        PetParticleEffect.EVOLVE -> drawConfetti(time, progress, origin, performance.confettiParticleCount)
                    }
                }
            }
        }
    }
}

/** 粒子绘制引擎（1:1 iOS `PetParticleDrawer`）：纯时间函数 + 确定性伪随机，无状态。 */
object PetParticleDrawer {

    /** 确定性伪随机（按索引 + 盐值，每帧一致）。Long 运算避免 Int 溢出（iOS 64-bit Int）。 */
    private fun pseudoRandom(index: Int, salt: Int): Float {
        val hash = abs((index.toLong() * 2654435761L + salt.toLong() * 40503L) % 65537L)
        return hash.toFloat() / 65537f
    }

    fun DrawScope.drawAmbient(time: Double, count: Int) {
        for (i in 0 until count) {
            val seed = pseudoRandom(i, 0)
            val x = size.width * seed
            val baseY = size.height * pseudoRandom(i, 1)
            val y = baseY + sin(time * 0.5 + seed * 10).toFloat() * 20f // 缓慢漂浮
            val alpha = (0.3 + 0.4 * sin(time * 1.2 + seed * 8)).toFloat() // 闪烁
            val radius = 1.5f + seed * 1.5f
            drawCircle(Color.White.copy(alpha = alpha.coerceAtLeast(0f)), radius = radius, center = Offset(x, y))
        }
    }

    fun DrawScope.drawFeed(time: Double, progress: Double, species: PetSpecies, count: Int) {
        val fadeOut = (1 - progress * 1.2).coerceAtLeast(0.0).toFloat()
        val color = feedColor(species)
        for (i in 0 until count) {
            val seed = pseudoRandom(i, 10)
            val x = size.width * (0.15f + 0.7f * seed)
            val endY = size.height * 0.7f
            val y = (-10f) + (endY - (-10f)) * progress.toFloat() + sin(time * 3 + seed * 5).toFloat() * 8f
            val ps = 4f + seed * 4f
            drawOval(
                color = color.copy(alpha = fadeOut * (0.6f + seed * 0.4f)),
                topLeft = Offset(x - ps / 2f, y - ps / 2f),
                size = Size(ps, ps * 0.8f),
            )
        }
    }

    fun DrawScope.drawClean(time: Double, progress: Double, count: Int) {
        val fadeOut = (1 - progress * 1.3).coerceAtLeast(0.0).toFloat()
        for (i in 0 until count) {
            val seed = pseudoRandom(i, 20)
            val x = size.width * (0.1f + 0.8f * seed) + sin(time * 2 + seed * 8).toFloat() * 12f
            val startY = size.height * 0.8f
            val endY = size.height * 0.1f
            val y = startY + (endY - startY) * progress.toFloat() * (0.5f + seed * 0.5f)
            val bs = 3f + seed * 6f
            // 气泡外圈
            drawOval(
                color = Color.Cyan.copy(alpha = fadeOut * 0.3f),
                topLeft = Offset(x - bs / 2f, y - bs / 2f),
                size = Size(bs, bs),
                style = Stroke(width = 1f),
            )
            // 气泡高光
            drawOval(
                color = Color.White.copy(alpha = fadeOut * 0.4f),
                topLeft = Offset(x - bs * 0.15f, y - bs * 0.25f),
                size = Size(bs * 0.25f, bs * 0.2f),
            )
        }
    }

    fun DrawScope.drawPlay(time: Double, progress: Double, origin: Offset, count: Int) {
        val fadeOut = (1 - progress * 1.2).coerceAtLeast(0.0).toFloat()
        for (i in 0 until count) {
            val seed = pseudoRandom(i, 30)
            val angle = seed * PI.toFloat() * 2f
            val spread = 40f + 80f * progress.toFloat()
            val x = origin.x + cos(angle) * spread * (0.5f + seed * 0.5f)
            val y = origin.y - 30f * progress.toFloat() - sin(angle) * spread * 0.3f
            val hs = 4f + seed * 4f
            val pinkHue = (0.9f + seed * 0.1f) * 360f
            val heartColor = Color.hsv(pinkHue.coerceIn(0f, 360f), 0.5f, 1.0f).copy(alpha = fadeOut * 0.5f)
            // 简化心形：两个重叠圆
            drawOval(heartColor, topLeft = Offset(x - hs * 0.3f, y - hs * 0.2f), size = Size(hs * 0.5f, hs * 0.5f))
            drawOval(heartColor, topLeft = Offset(x + hs * 0.1f, y - hs * 0.2f), size = Size(hs * 0.5f, hs * 0.5f))
        }
    }

    fun DrawScope.drawConfetti(time: Double, progress: Double, origin: Offset, count: Int) {
        val fadeOut = (1 - progress).coerceAtLeast(0.0).toFloat()
        val colors = listOf(Color.Red, Color(0xFFFF9500), Color.Yellow, Color(0xFF34C759), Color.Blue, Color(0xFFAF52DE), Color(0xFFFF2D55))
        for (i in 0 until count) {
            val seed = pseudoRandom(i, 40)
            val angle = seed * PI.toFloat() * 2f
            val speed = 80f + 200f * seed
            val x = origin.x + cos(angle) * speed * progress.toFloat()
            val y = origin.y + sin(angle) * speed * progress.toFloat() * 0.6f + 100f * progress.toFloat() * progress.toFloat()
            val rotationDeg = Math.toDegrees(time * (2 + seed * 4)).toFloat()
            val w = 3f + seed * 3f
            val h = 2f + seed * 2f
            val color = colors[i % colors.size].copy(alpha = fadeOut * 0.8f)
            rotate(degrees = rotationDeg, pivot = Offset(x, y)) {
                drawRect(color, topLeft = Offset(x - w / 2f, y - h / 2f), size = Size(w, h))
            }
        }
    }

    /** 食物粒子颜色（按物种，1:1 iOS feedColor）。 */
    private fun feedColor(species: PetSpecies): Color = when (species) {
        PetSpecies.CAT, PetSpecies.SPIRIT -> Color(red = 0.4f, green = 0.6f, blue = 0.9f) // 蓝（鱼）
        PetSpecies.DOG -> Color(red = 0.85f, green = 0.75f, blue = 0.55f) // 米（骨头）
        PetSpecies.RABBIT -> Color(red = 0.95f, green = 0.6f, blue = 0.2f) // 橙（胡萝卜）
        PetSpecies.HAMSTER -> Color(red = 0.9f, green = 0.8f, blue = 0.3f) // 黄（种子）
        PetSpecies.DRAGON, PetSpecies.UNICORN -> Color(red = 1.0f, green = 0.85f, blue = 0.3f) // 金
    }
}
