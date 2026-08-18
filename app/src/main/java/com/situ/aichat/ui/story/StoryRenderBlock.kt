package com.situ.aichat.ui.story

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.situ.aichat.story.StoryReaderRenderItem
import com.situ.aichat.story.StoryReaderTypography
import com.situ.aichat.story.StoryTextStyle
import kotlinx.coroutines.delay

/**
 * 单个渲染项的包装（源自 iOS `StoryRenderItemView`，`StoryReaderAnimatedBlocks.swift:78-140`）。
 *
 * 职责两件：① 渐显动画（透明度 0→1 + 上移 10dp→0，0.45s ease-out）；
 * ② trembling/angry/excited 文字接入 18fps 逐帧时钟。
 *
 * **2026-08-03 格式块精简**：原第三、四职（出现时回调更新 mood/weather、进入视区后延迟触发屏幕特效）
 * 随氛围演出层整族退役——pause 延迟起显、attachedEffects 触发链一并删除。
 */
@Composable
fun StoryRenderBlock(
    item: StoryReaderRenderItem,
    animationsEnabled: Boolean,
    animatedTextEnabled: Boolean,
    isDark: Boolean,
    typography: StoryReaderTypography,
    modifier: Modifier = Modifier,
) {
    var revealed by remember(item.id) { mutableStateOf(!animationsEnabled) }
    val revealAlpha = animateFloatAsState(if (revealed) 1f else 0f, tween(450, easing = EaseOut), label = "reveal")
    val revealOffset = animateFloatAsState(if (revealed) 0f else 10f, tween(450, easing = EaseOut), label = "revealY")

    // 渐显（= iOS .task）。
    LaunchedEffect(item.id) {
        revealed = true
    }

    // 动画文字逐帧时钟（仅 trembling/angry/excited 且开启文字动画）。
    val style = (item.kind as? StoryReaderRenderItem.Kind.Text)?.style
    val isAnimatedStyle = style == StoryTextStyle.TREMBLING ||
        style == StoryTextStyle.ANGRY ||
        style == StoryTextStyle.EXCITED
    val motionTime = remember(item.id) { mutableDoubleStateOf(0.0) }
    if (animatedTextEnabled && isAnimatedStyle) {
        LaunchedEffect(item.id) {
            val startNanos = System.nanoTime()
            while (true) {
                motionTime.doubleValue = (System.nanoTime() - startNanos) / 1_000_000_000.0
                delay(55) // ~18fps，= iOS TimelineView(minimumInterval: 1/18)
            }
        }
    }

    // 段距只加在正文文字块之间（随字号档缩放）；场景分隔 / 章末装饰自带上下留白，不叠加。
    val blockSpacing = if (item.kind is StoryReaderRenderItem.Kind.Text) StoryReaderLayout.paragraphSpacing(typography) else 0.dp
    Box(
        modifier
            .padding(bottom = blockSpacing)
            .graphicsLayer {
                alpha = if (animationsEnabled) revealAlpha.value else 1f
                translationY = if (animationsEnabled) revealOffset.value * density else 0f
            },
    ) {
        when (val kind = item.kind) {
            is StoryReaderRenderItem.Kind.Text -> StoryReaderTextBlock(
                text = kind.text,
                style = kind.style,
                isFirstParagraph = item.isFirstParagraph,
                isDark = isDark,
                typography = typography,
                motionTime = { motionTime.doubleValue },
            )
            is StoryReaderRenderItem.Kind.Scene -> StorySceneDivider(kind.text, isDark)
            StoryReaderRenderItem.Kind.ChapterEnd -> StoryChapterEndDivider(isDark)
        }
    }
}
