package com.situ.aichat.ui.pet

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.pet.AdoptionProgress
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppTheme
import kotlin.math.roundToInt

// 宠物「无宠物·领养空态」（从 PetDetailScreen 抽出·只搬不改）：还没养宠物时的整屏视图——
// 蛋卧暖巢 hero（自绘 Canvas·reduceMotion 门控轻晃）+ 孵化总进度（大百分比 + 陶土进度条）+
// 领养 5 指标一行小字 + 「一起迎接 TA」按钮（达标）/ 引导文案（未达标/无进度）。
// 顶部返回按钮复用主文件的 TopIconButton（同包 internal）。
@Composable
internal fun NoPetView(progress: AdoptionProgress?, canAdopt: Boolean, onBack: () -> Unit, onAdopt: () -> Unit) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    Box(Modifier.fillMaxSize()) {
        // 壁纸重构②：NavHost 去垫付 → 背景 fillMaxSize 自然铺满系统栏后（不再 clawback）。
        HatchWarmBackground(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EggNestHero(reduceMotion)
            Spacer(Modifier.height(28.dp))
            when {
                progress != null && canAdopt -> {
                    Text("你们的羁绊已经足够深", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.text.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("一起迎接这个小生命吧", style = MaterialTheme.typography.bodyMedium, color = colors.text.secondary)
                    Spacer(Modifier.height(24.dp))
                    AppButton(onClick = onAdopt) {
                        Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("一起迎接 TA")
                    }
                }
                progress != null -> {
                    Text("再亲近一些，就能一起迎接 TA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.text.primary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(22.dp))
                    HatchProgress(progress.overallPercent)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        adoptionMetricsLine(progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text.secondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp,
                    )
                }
                else -> {
                    Text("还没有宠物", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.text.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("和 TA 多相处，慢慢解锁专属的小宠物", style = MaterialTheme.typography.bodyMedium, color = colors.text.secondary, textAlign = TextAlign.Center)
                }
            }
        }
        TopIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), onBack)
    }
}

/** 蛋巢空态暖光背景（独立于 [PetMoodBackground]·不随 [PetMoodType] 变）：token 派生暖陶竖向 wash + 琥珀微光。 */
@Composable
private fun HatchWarmBackground(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.accent.container, colors.surface.base, colors.surface.base))),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(colors.pet.satiety.copy(alpha = if (colors.isDark) 0.16f else 0.34f), Color.Transparent),
                    ),
                ),
        )
    }
}

/**
 * 「待孵化的蛋卧暖巢」hero（自绘 Canvas·莫兰迪暖 token）。蛋静卧巢中，[reduceMotion]=false 时极缓轻晃
 * （仅蛋绕巢沿轻摆·巢不动）；reduceMotion → 静止。纯装饰，无障碍由外层文案承载。
 */
@Composable
private fun EggNestHero(reduceMotion: Boolean) {
    val colors = AppTheme.colors
    val isDark = colors.isDark
    val infinite = rememberInfiniteTransition(label = "eggNest")
    val animTilt by infinite.animateFloat(
        initialValue = -2.4f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1700, easing = EaseInOut), RepeatMode.Reverse),
        label = "eggTilt",
    )
    val tilt = if (reduceMotion) 0f else animTilt

    val nestTop = colors.pet.satiety
    val nestBottom = colors.accent.text
    // 蛋壳=暖白：浅档 surface.raised(纯白)；深档无专用蛋壳 token → 取最浅暖 text.primary(Cream)，
    // 才能从同族琥珀巢里浮出来。蛋底渐入暖琥珀 → 卧进巢色。高光恒取浅档暖白（深档 surface.raised 是
    // 深咖会反成暗斑）。
    val eggTop = if (isDark) colors.text.primary else colors.surface.raised
    val eggBottom = if (isDark) colors.pet.satiety else colors.accent.container
    val highlight = if (isDark) colors.text.primary else colors.surface.raised
    val speckle = colors.accent.text
    val strawPrimary = colors.accent.primary

    Canvas(Modifier.size(200.dp)) {
        val s = size.minDimension
        val cx = size.width / 2f
        // 落影
        drawOval(color = colors.surface.scrim.copy(alpha = 0.10f), topLeft = Offset(cx - s * 0.32f, s * 0.82f), size = Size(s * 0.64f, s * 0.10f))
        // 巢（后沿）
        drawOval(
            brush = Brush.verticalGradient(listOf(nestTop, nestBottom), startY = s * 0.56f, endY = s * 0.90f),
            topLeft = Offset(cx - s * 0.42f, s * 0.56f),
            size = Size(s * 0.84f, s * 0.34f),
        )
        drawNestStraw(cx, s, yBase = s * 0.60f, primary = strawPrimary, accent = nestTop)
        // 蛋（轻晃·绕巢沿 pivot）
        rotate(degrees = tilt, pivot = Offset(cx, s * 0.66f)) {
            drawOval(
                brush = Brush.verticalGradient(listOf(eggTop, eggBottom), startY = s * 0.16f, endY = s * 0.70f),
                topLeft = Offset(cx - s * 0.21f, s * 0.16f),
                size = Size(s * 0.42f, s * 0.54f),
            )
            drawOval(
                color = highlight.copy(alpha = if (isDark) 0.38f else 0.55f),
                topLeft = Offset(cx - s * 0.13f, s * 0.23f),
                size = Size(s * 0.13f, s * 0.19f),
            )
            listOf(
                Offset(cx + s * 0.05f, s * 0.31f), Offset(cx - s * 0.06f, s * 0.42f),
                Offset(cx + s * 0.08f, s * 0.50f), Offset(cx - s * 0.02f, s * 0.57f),
            ).forEach { drawCircle(color = speckle.copy(alpha = 0.20f), radius = s * 0.012f, center = it) }
        }
        // 巢（前沿·盖住蛋底 → 蛋卧巢中）
        drawOval(
            brush = Brush.verticalGradient(listOf(nestTop, nestBottom), startY = s * 0.64f, endY = s * 0.92f),
            topLeft = Offset(cx - s * 0.40f, s * 0.66f),
            size = Size(s * 0.80f, s * 0.26f),
        )
        drawNestStraw(cx, s, yBase = s * 0.74f, primary = strawPrimary, accent = nestTop)
    }
}

/** 巢沿编织草秆（短角度斜线·index 派生确定性·无随机）。 */
private fun DrawScope.drawNestStraw(cx: Float, s: Float, yBase: Float, primary: Color, accent: Color) {
    val count = 7
    for (i in 0 until count) {
        val frac = (i + 0.5f) / count
        val x = cx - s * 0.36f + s * 0.72f * frac
        val dy = if (i % 2 == 0) -s * 0.030f else -s * 0.055f
        val col = if (i % 2 == 0) primary else accent
        drawLine(
            color = col.copy(alpha = 0.65f),
            start = Offset(x - s * 0.05f, yBase),
            end = Offset(x + s * 0.05f, yBase + dy),
            strokeWidth = s * 0.018f,
            cap = StrokeCap.Round,
        )
    }
}

/** 孵化总进度领衔（= [AdoptionProgress.overallPercent]·5 项等权均值）：大百分比 + 陶土渐变进度条。 */
@Composable
private fun HatchProgress(fraction: Float) {
    val colors = AppTheme.colors
    val clamped = fraction.coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${(clamped * 100).roundToInt()}%", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = colors.accent.text)
        Text("孵化进度", style = MaterialTheme.typography.labelMedium, color = colors.text.secondary)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(220.dp).height(10.dp).clip(RoundedCornerShape(50)).background(colors.surface.sunken)) {
            Box(
                Modifier
                    .fillMaxWidth(clamped)
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))),
            )
        }
    }
}

/** 领养 5 指标降为一行小字（去 ⭕/✅ 功能标记·总进度领衔后这里只作明细补充）。 */
private fun adoptionMetricsLine(p: AdoptionProgress): String =
    "陪伴 ${p.companionDays}/14 · 信任 ${p.trust}/40 · 亲密 ${p.familiarity}/35 · 亲近 ${p.closeness}/30 · 消息 ${p.messageCount}/100"
