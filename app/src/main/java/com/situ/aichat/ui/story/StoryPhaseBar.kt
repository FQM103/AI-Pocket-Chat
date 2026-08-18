package com.situ.aichat.ui.story

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.story.StoryGenPhase
import com.situ.aichat.story.StoryProgressModel
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 故事生成的四段圆头阶段条（灵动岛卷一 §4.1）——阅读器遮罩与书架卡片共用，与灵动岛药丸同信号源。
 *
 * 四段宽度 = [StoryProgressModel.SEGMENT_WEIGHTS]（15/60/17/8）；撰写段独占 60% 因为它是全程唯一的连续真进度。
 * 已完成段满填、活跃段 shimmer 呼吸 + 段内按比例填充、未来段 sunken。
 *
 * 两档：标准档（阅读器遮罩·6dp 高/3dp 缺口）与 mini 档（书架卡片·3dp 高/2dp 缺口）。
 */
@Composable
fun StoryPhaseBar(
    genPhase: StoryGenPhase,
    progress: Double,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
) {
    val height: Dp = if (mini) 3.dp else 6.dp
    val gap: Dp = if (mini) 2.dp else 3.dp
    val reduceMotion = rememberReduceMotion()

    // 段内推进走 gentle 弹簧平滑：真实事件是跳变的（一次 preview 可能跳好几个百分点），直接渲染会一格一格蹦。
    // 同样保持 State：两个读点（semantics / drawBehind）都在延迟块里，弹簧逐帧不触发重组。
    val animatedProgress = animateFloatAsState(
        targetValue = progress.toFloat(),
        animationSpec = spring(
            dampingRatio = AppMotion.gentleDamping,
            stiffness = AppMotion.gentleStiffness,
        ),
        label = "storyPhaseProgress",
    )

    // 活跃段呼吸：全周期 1.8s = tween 半程 900ms + Reverse（Compose 的 tween duration 是半程，别照 CSS 全周期填）。
    // 保持 State 不拆包：唯一读点在下面的 graphicsLayer 块里 → 呼吸只重绘图层、不重组（照 ClickableScale 先例）。
    val shimmerState: State<Float> = if (reduceMotion) {
        remember { mutableFloatStateOf(1f) }
    } else {
        val transition = rememberInfiniteTransition(label = "storyPhaseShimmer")
        transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "storyPhaseShimmerAlpha",
        )
    }
    val shimmerAlpha by shimmerState

    val activeIndex = StoryProgressModel.segIndex(genPhase)
    val accent = AppTheme.colors.accent.primary
    val future = AppTheme.colors.surface.sunken

    Row(
        modifier = modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(animatedProgress.value, 0f..1f)
        },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        StoryProgressModel.SEGMENT_WEIGHTS.forEachIndexed { index, weight ->
            val segmentModifier = Modifier
                .weight(weight.toFloat())
                .height(height)
                .clip(AppTheme.shapes.full)
            when {
                // 已完成段（DONE 时四段全满）：满填。
                index < activeIndex || genPhase == StoryGenPhase.DONE ->
                    Box(segmentModifier.fillMaxWidth().background(accent))

                // 活跃段：淡轨道 + 段内比例填充，整段一起呼吸。
                // 填充改绘制期画（原来是子 Box）：链序 = 轨道背景先、填充后，与原来「背景在下、子内容在上」同一叠序；
                // CornerRadius(h/2) = 原 clip(full) 的胶囊（窄于高时 Skia 等比收敛到 w/2，与 50% 百分比圆角同结果）。
                index == activeIndex -> Box(
                    segmentModifier
                        .graphicsLayer { alpha = shimmerAlpha }
                        .background(accent.copy(alpha = 0.16f))
                        .drawBehind {
                            val fraction = segmentFraction(animatedProgress.value, index, weight)
                            if (fraction > 0f) {
                                drawRoundRect(
                                    color = accent,
                                    size = Size(size.width * fraction, size.height),
                                    cornerRadius = CornerRadius(size.height / 2),
                                )
                            }
                        },
                )

                // 未来段。
                else -> Box(segmentModifier.fillMaxWidth().background(future))
            }
        }
    }
}

/** 段内填充比例：总进度落在本段的位置 ÷ 本段跨度，钳 [0,1]（只有撰写段会非零）。 */
private fun segmentFraction(progress: Float, index: Int, weight: Int): Float =
    ((progress - StoryProgressModel.SEG_START[index]) / (weight / 100.0)).toFloat().coerceIn(0f, 1f)
