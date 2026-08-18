package com.situ.aichat.ui.chat

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.situ.aichat.tts.EmotionType
import com.situ.aichat.ui.components.iosSpringDampingRatio
import com.situ.aichat.ui.components.iosSpringStiffness
import kotlinx.coroutines.delay

/**
 * P1-5（批5）：消息气泡情绪入场动画——1:1 iOS EmotionAnimationModifier.swift。
 *
 * 情绪分类**单源**于 [EmotionType]（tts 包，=iOS 让气泡动画与 TTS 共享同一 emoji 字典的结构）；
 * 本文件按其 KDoc 预留的扩展位补动画参数：三阶段弹簧全情绪固定
 * （phase1 spring(0.25s, bounce 0.15) / phase2 (0.3s, 0.08) / phase3 (0.25s, 0.05)，iOS :108-125），
 * per-emotion 差异只在两段停留时长（iOS :221-253）；几何变换（offset/scale/rotation/alpha）按
 * 情绪×阶段查表（iOS EmotionTransformModifier :134-216，phase 0 与 3 全恒等——气泡先以静止态
 * 出现、随后做一次「甩动」，不是从屏外入场）。整族零触觉零声音（iOS 同）。
 */

/** 第一阶段停留毫秒（=iOS EmotionAnimationModifier.swift:221-236；穷举 when=tts 侧增删 case 编译期报警防漂移）。 */
internal val EmotionType.phase1DurationMs: Long
    get() = when (this) {
        EmotionType.HAPPY, EmotionType.PLAYFUL -> 180L
        EmotionType.EXCITED -> 200L
        EmotionType.ANGRY -> 80L // 快速抖动
        EmotionType.SAD -> 300L // 缓慢
        EmotionType.SHOCKED -> 120L // 急促
        EmotionType.SHY -> 220L
        EmotionType.LOVE -> 250L
        EmotionType.THINKING -> 200L
        EmotionType.SCARED -> 80L
        EmotionType.SIGH -> 250L
        EmotionType.NEUTRAL -> 0L
    }

/** 第二阶段停留毫秒（=iOS :239-253）。 */
internal val EmotionType.phase2DurationMs: Long
    get() = when (this) {
        EmotionType.HAPPY, EmotionType.PLAYFUL -> 150L
        EmotionType.EXCITED -> 180L
        EmotionType.ANGRY -> 80L
        EmotionType.SAD -> 250L
        EmotionType.SHOCKED -> 120L
        EmotionType.SHY -> 180L
        EmotionType.LOVE -> 200L
        EmotionType.THINKING -> 200L
        EmotionType.SCARED -> 80L
        EmotionType.SIGH -> 200L
        EmotionType.NEUTRAL -> 0L
    }

/** 情绪几何变换（offset 单位 dp=iOS pt 1:1）。 */
internal data class EmotionTransform(
    val offsetXDp: Float,
    val offsetYDp: Float,
    val scale: Float,
    val rotationDeg: Float,
    val alpha: Float,
)

internal val EMOTION_IDENTITY = EmotionTransform(0f, 0f, 1f, 0f, 1f)

/**
 * 情绪×阶段几何变换查表——逐值 1:1 iOS EmotionTransformModifier（:146-215）。
 * phase 0（待触发）与 phase 3（完成归位）恒等；只有 phase 1/2 有形变。
 */
internal fun emotionTransform(emotion: EmotionType, phase: Int): EmotionTransform {
    if (phase != 1 && phase != 2) return EMOTION_IDENTITY
    val p1 = phase == 1
    return when (emotion) {
        EmotionType.HAPPY -> if (p1) EmotionTransform(0f, -5f, 1.02f, 0f, 1f) else EMOTION_IDENTITY
        EmotionType.EXCITED ->
            if (p1) EmotionTransform(0f, 0f, 0.92f, 1.5f, 1f) else EmotionTransform(0f, 0f, 1.06f, -1.5f, 1f)
        EmotionType.ANGRY -> EmotionTransform(if (p1) 3f else -3f, 0f, 1f, 0f, 1f)
        EmotionType.SAD -> if (p1) EmotionTransform(0f, -8f, 1f, 0f, 0.5f) else EMOTION_IDENTITY
        EmotionType.SHOCKED ->
            if (p1) EmotionTransform(2f, 0f, 0.85f, 0f, 1f) else EmotionTransform(-2f, 0f, 1.08f, 0f, 1f)
        EmotionType.SHY -> if (p1) EmotionTransform(0f, 0f, 0.95f, 0f, 0.7f) else EMOTION_IDENTITY
        EmotionType.LOVE -> if (p1) EmotionTransform(0f, -6f, 1.03f, 0f, 1f) else EMOTION_IDENTITY
        EmotionType.THINKING -> EmotionTransform(0f, 0f, 1f, if (p1) 2f else -2f, 1f)
        EmotionType.SCARED -> EmotionTransform(if (p1) 1.5f else -1.5f, 0f, 1f, 0f, 1f)
        EmotionType.PLAYFUL -> if (p1) EmotionTransform(0f, -4f, 1f, 2f, 1f) else EMOTION_IDENTITY
        EmotionType.SIGH ->
            if (p1) EmotionTransform(0f, 0f, 1.02f, 0f, 1f) else EmotionTransform(0f, 0f, 0.97f, 0f, 1f)
        EmotionType.NEUTRAL -> EMOTION_IDENTITY
    }
}

/** phase1 = iOS spring(duration 0.25, bounce 0.15)（EmotionAnimationModifier.swift:108）。 */
internal val emotionPhase1Spring: SpringSpec<Float> =
    spring(iosSpringDampingRatio(bounce = 0.15f), iosSpringStiffness(durationSeconds = 0.25f))

/** phase2 = iOS spring(duration 0.3, bounce 0.08)（:116）。 */
internal val emotionPhase2Spring: SpringSpec<Float> =
    spring(iosSpringDampingRatio(bounce = 0.08f), iosSpringStiffness(durationSeconds = 0.3f))

/** phase3 = iOS spring(duration 0.25, bounce 0.05)（:123）。 */
internal val emotionPhase3Spring: SpringSpec<Float> =
    spring(iosSpringDampingRatio(bounce = 0.05f), iosSpringStiffness(durationSeconds = 0.25f))

/**
 * 情绪入场修饰符（挂消息整行=iOS ChatView+MessageList.swift:51 对整条时间线行施加）。
 *
 * [play]=false 时零开销直通（先例 userBubbleEntryScale）；为 true 时锁存首判——[onPlayed] 在
 * phase3 弹簧启动后立即标记（=iOS :126，不等 settle），外层门控随即翻 false，任何自然重组
 * 不得中途拆树把未 settle 的 phase3 截断。快情绪（angry/scared 80ms）的相切是中途重定向
 * （retarget），animateFloatAsState 天然支持——禁止改成 Animatable 串行等 settle（会拖长抖动）。
 * 非空 tag 映射 NEUTRAL（如 😐🙂）→ 立即标记不播（=iOS :102-104）。
 */
internal fun Modifier.emotionBubbleEntry(
    emotionTag: String?,
    play: Boolean,
    onPlayed: () -> Unit,
): Modifier = composed {
    val shouldRun = remember { play }
    if (!shouldRun) return@composed Modifier
    val emotion = remember { EmotionType.from(emotionTag) }
    if (emotion == EmotionType.NEUTRAL) {
        LaunchedEffect(Unit) { onPlayed() }
        return@composed Modifier
    }
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        phase = 1
        delay(emotion.phase1DurationMs)
        phase = 2
        delay(emotion.phase2DurationMs)
        phase = 3
        onPlayed()
    }
    val spec = when (phase) {
        1 -> emotionPhase1Spring
        2 -> emotionPhase2Spring
        else -> emotionPhase3Spring
    }
    val transform = emotionTransform(emotion, phase)
    val offsetX by animateFloatAsState(transform.offsetXDp, spec, label = "emotionOffsetX")
    val offsetY by animateFloatAsState(transform.offsetYDp, spec, label = "emotionOffsetY")
    val scale by animateFloatAsState(transform.scale, spec, label = "emotionScale")
    val rotation by animateFloatAsState(transform.rotationDeg, spec, label = "emotionRotation")
    val alphaValue by animateFloatAsState(transform.alpha, spec, label = "emotionAlpha")
    // iOS 链序 offset→scale→rotation→opacity 与 graphicsLayer 内部固定序不严格一致；受影响的只有
    // 组合情绪（shocked/playful 等），误差为 1.08×2dp 量级二阶小量，登记于此、不为此造嵌套 layer。
    Modifier.graphicsLayer {
        translationX = offsetX.dp.toPx()
        translationY = offsetY.dp.toPx()
        scaleX = scale
        scaleY = scale
        rotationZ = rotation
        alpha = alphaValue
    }
}
