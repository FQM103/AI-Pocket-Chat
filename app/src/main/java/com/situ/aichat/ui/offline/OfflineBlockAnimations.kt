package com.situ.aichat.ui.offline

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

// MARK: 逐块淡入 / 独白呼吸 / 场景过渡展开（对应 iOS OfflineBlockAnimations.swift 的 ViewModifier）。
// 对话「呼吸光晕」（iOS 第 4 个 modifier）已按用户拍板移除（2026-06-18·teal 光晕在浅背景上显成绿色底、视觉欠佳）。
// rememberReduceMotion 已于批0（2026-06-10）提升到 com.situ.aichat.ui.components.AppMotion（全工程动效共用）。

/**
 * 逐块揭示入场（D1·2026-07-06 拍板，取代旧 index×650ms 的 `offlineBlockEntry` Modifier）：
 * 到点前**不占位**（expandVertically 高度随淡入展开·消灭「先见一段空白再等填充」），节奏由
 * [OfflineRevealPacing] 阅读驱动——[epochUptimeMillis]（本条消息揭示起点·SystemClock.uptimeMillis 口径）
 * + [delayFromEpochMs]（相对起点的目标延迟）定揭示时刻；流式晚组合的块自动扣除已流逝时间、不过度顺延。
 *
 * 已播放（[hasPlayed]）/ 开关关 / 减弱动画 → 直接显示不动画（决策在首次组合冻结，防播完后分支切换）。
 * [onRevealed] 在块开始展开的那一帧回调（父层贴底跟随）；[onPlayed] 在入场完成后回调（父层记「已播放」）。
 */
@Composable
fun OfflineBlockReveal(
    epochUptimeMillis: Long,
    delayFromEpochMs: Long,
    hasPlayed: Boolean,
    enabled: Boolean,
    reduceMotion: Boolean,
    onRevealed: () -> Unit,
    onPlayed: () -> Unit,
    content: @Composable () -> Unit,
) {
    val animate = remember { enabled && !reduceMotion && !hasPlayed }
    if (!animate) {
        LaunchedEffect(Unit) { if (!hasPlayed) onPlayed() }
        content()
        return
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val wait = epochUptimeMillis + delayFromEpochMs - SystemClock.uptimeMillis()
        if (wait > 0) delay(wait)
        visible = true
        onRevealed()
        delay(400L)
        onPlayed()
    }
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(durationMillis = 320), expandFrom = Alignment.Top) +
            fadeIn(tween(durationMillis = 400)),
    ) {
        content()
    }
}

/**
 * 内心独白「呼吸透明度」（1:1 iOS `OfflineMonologueBreathingModifier`）：透明度 0.82↔1.0 微脉动，
 * 营造思绪飘忽感；reduceMotion 时恒 1.0。
 */
fun Modifier.offlineMonologueBreathing(reduceMotion: Boolean): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "offlineMonologueBreathing")
    val animatedAlpha by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "offlineMonologueBreathingAlpha",
    )
    Modifier.alpha(if (reduceMotion) 1f else animatedAlpha)
}

/**
 * 场景过渡分隔线「水平展开」入场（1:1 iOS `OfflineSceneTransitionEntryModifier`）：scaleX 0→1 + 淡入，
 * spring 弹性；reduceMotion 时直接展开。
 */
fun Modifier.offlineSceneTransitionEntry(reduceMotion: Boolean): Modifier = composed {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow),
        label = "offlineSceneTransitionExpand",
    )
    val applied = if (reduceMotion) 1f else progress
    Modifier.graphicsLayer {
        scaleX = applied
        alpha = applied
    }
}
