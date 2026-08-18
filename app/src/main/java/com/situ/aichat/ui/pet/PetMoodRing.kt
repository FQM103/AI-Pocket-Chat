package com.situ.aichat.ui.pet

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.pet.PetCareAction
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import kotlin.math.cos
import kotlin.math.sin

private data class MoodSeg(val label: String, val value: Int, val color: Color, val urgent: Boolean)

/**
 * Fable-5 宠物「心情环」（替 Apple-Watch 四同心环 [PetStatusRingsView] 的 iOS systemColor 设计）：单环 4 段柔光弧
 * （饱食 / 清洁 / 心情 / 健康·莫兰迪暖四色 [AppTheme].colors.pet.*），每段填充 = 该项 0~100%；urgent 段（随
 * [petNeedHeadline][com.situ.aichat.pet.petNeedHeadline] 的重点动作）加深陶强调点 + 满段底。中心 [content] 留给 S3
 * 放宠物精灵（"宠物外圈"）。[showValues]=true 时环下接一行莫兰迪数值（S3 主屏集成改"行内四值"后可关）。
 * reduceMotion 门控填充弹簧；[clearAndSetSemantics] 压成一停拼读四项（= 旧环 a11y 等价·饱食取反 100-hunger）。
 */
@Composable
fun PetMoodRing(
    hunger: Int,
    cleanliness: Int,
    happiness: Int,
    health: Int,
    urgent: PetCareAction?,
    modifier: Modifier = Modifier,
    ringSize: Dp = 132.dp,
    showValues: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val petColors = AppTheme.colors.pet
    val deep = AppTheme.colors.accent.text // 深陶强调
    val segs = moodSegs(hunger, cleanliness, happiness, health, urgent, petColors)
    val reduceMotion = rememberReduceMotion()
    val springSpec = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)
    val fracs = segs.map { seg ->
        val target = seg.value / 100f
        if (reduceMotion) target else animateFloatAsState(target, springSpec, label = "moodSeg_${seg.label}").value
    }
    val a11y = "饱食度 ${segs[0].value}，清洁度 ${segs[1].value}，心情 ${segs[2].value}，健康 ${segs[3].value}"

    Column(
        modifier.clearAndSetSemantics { contentDescription = a11y },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val gapDeg = 14f
                val segSweep = 360f / 4f - gapDeg
                val stroke = size.minDimension * 0.075f
                val radius = (size.minDimension - stroke * 1.7f) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)
                segs.forEachIndexed { i, seg ->
                    val start = -90f + i * (segSweep + gapDeg) + gapDeg / 2f
                    // 轨道（urgent 段底色加深陶微染，强调该象限）
                    val trackColor = if (seg.urgent) deep.copy(alpha = 0.20f) else seg.color.copy(alpha = 0.16f)
                    drawArc(trackColor, start, segSweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    val fillSweep = segSweep * fracs[i].coerceIn(0f, 1f)
                    if (fillSweep > 0f) {
                        // 柔光：更宽更淡的底光 + 实填弧
                        drawArc(seg.color.copy(alpha = 0.30f), start, fillSweep, false, topLeft, arcSize, style = Stroke(stroke * 1.85f, cap = StrokeCap.Round))
                        drawArc(seg.color, start, fillSweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    // urgent 强调点：该段起点的深陶圆点
                    if (seg.urgent) {
                        val rad = Math.toRadians(start.toDouble())
                        val dot = Offset(center.x + radius * cos(rad).toFloat(), center.y + radius * sin(rad).toFloat())
                        drawCircle(deep, radius = stroke * 0.55f, center = dot)
                    }
                }
            }
            content()
        }
        if (showValues) {
            PetStatsValueRow(hunger, cleanliness, happiness, health, urgent)
        }
    }
}

/** 四项状态规格（饱食=100-hunger·清洁·心情=happiness·健康），urgent 段随 [petNeedHeadline] 重点动作。 */
private fun moodSegs(
    hunger: Int,
    cleanliness: Int,
    happiness: Int,
    health: Int,
    urgent: PetCareAction?,
    petColors: com.situ.aichat.ui.designsystem.AppPetColors,
): List<MoodSeg> = listOf(
    MoodSeg("饱食", (100 - hunger).coerceIn(0, 100), petColors.satiety, urgent == PetCareAction.FEED),
    MoodSeg("清洁", cleanliness.coerceIn(0, 100), petColors.cleanliness, urgent == PetCareAction.CLEAN),
    MoodSeg("心情", happiness.coerceIn(0, 100), petColors.mood, urgent == PetCareAction.PLAY),
    MoodSeg("健康", health.coerceIn(0, 100), petColors.health, urgent == PetCareAction.TREAT),
)

/** 行内四数值（莫兰迪色点 + 标签 + 值·urgent 项深陶加粗）——主屏「行内 4 数值」与环 [showValues] 共用。 */
@Composable
fun PetStatsValueRow(
    hunger: Int,
    cleanliness: Int,
    happiness: Int,
    health: Int,
    urgent: PetCareAction?,
    modifier: Modifier = Modifier,
) {
    val deep = AppTheme.colors.accent.text
    val segs = moodSegs(hunger, cleanliness, happiness, health, urgent, AppTheme.colors.pet)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        segs.forEach { MoodValueChip(it, deep) }
    }
}

/** 莫兰迪数值小条：色点 + 标签 + 值；urgent 项文字走深陶 + 加粗。 */
@Composable
private fun MoodValueChip(seg: MoodSeg, deep: Color) {
    val labelColor = if (seg.urgent) deep else AppTheme.colors.text.secondary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(seg.color))
        Text(
            "${seg.label} ${seg.value}",
            fontSize = 12.sp,
            fontWeight = if (seg.urgent) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor,
        )
    }
}
