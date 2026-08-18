package com.situ.aichat.ui.voicecall

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.situ.aichat.ui.components.rememberReduceMotion
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.situ.aichat.voice.CallState
import kotlin.math.PI
import kotlin.math.sin

/**
 * The full-screen call waveform — Android port of iOS `VoiceCallWaveView`. Three overlaid sine curves,
 * redrawn every frame while the call is active (idle/ending = static low line). All animation constants are
 * 1:1 iOS (VoiceCallWaveView.swift:52-91): amplitude breathing for processing/dialing, audio-level driven
 * otherwise; per-curve frequency `1.5+i*0.5`, phase speed `2.0+i*0.35`, damping `1-i*0.22`, opacity
 * `0.34-i*0.09`. Drawn in white over the dark background.
 */
@Composable
fun VoiceCallWaveform(
    audioLevel: Float,
    state: CallState,
    modifier: Modifier = Modifier,
) {
    // P1-23：RM 冻结相位时钟 timeSeconds=0（=iOS 自家静态分支 waveCanvas(time: 0) 同帧：三正弦停
    // phase 0 不横移、呼吸幅停 sin(0) 中点）；amplitude 仍随 audioLevel 数据驱动=电平计信息保留。
    // iOS 波形不读 RM（VoiceCallWaveView.swift 仅 shouldAnimateContinuously），安卓门控=既定惯例加项。
    val animate = !rememberReduceMotion() && state != CallState.IDLE && state != CallState.ENDING

    // Free-running clock (elapsed seconds since the animation started, so the phase stays Float-precise even
    // on a long call). Only spins while animating; static otherwise = iOS `shouldAnimateContinuously`.
    var timeSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val startMs = withFrameMillis { it }
        while (true) {
            withFrameMillis { ms -> timeSeconds = (ms - startMs) / 1000f }
        }
    }

    // P1-17：压停（= iOS VoiceCallView.swift:87 .accessibilityHidden(true)；Canvas 本不可焦，防御+意图文档）。
    // D-5：三线换陶土三阶（浅→深·亮度递减保持原「前实后虚」层次），振幅/相位/阻尼机制零碰。
    val waveColors = listOf(
        VoiceCallPalette.waveBright.copy(alpha = 0.52f),
        VoiceCallPalette.waveMid.copy(alpha = 0.36f),
        VoiceCallPalette.waveDeep.copy(alpha = 0.28f),
    )
    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        val width = size.width
        val midY = size.height / 2f
        val amplitude = resolvedAmplitude(state, audioLevel, timeSeconds, size.height)
        val strokeWidth = 2.dp.toPx()
        for (index in 0 until 3) {
            drawPath(
                path = buildWavePath(index, timeSeconds, width, midY, amplitude),
                color = waveColors[index],
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

/** 1:1 iOS `resolvedAmplitude` (VoiceCallWaveView.swift:52-63). */
private fun resolvedAmplitude(state: CallState, audioLevel: Float, time: Float, height: Float): Float =
    when (state) {
        CallState.PROCESSING, CallState.DIALING -> {
            val breathing = 0.05f + 0.03f * (sin(time * 1.5f) + 1f) / 2f
            height * breathing
        }
        CallState.IDLE, CallState.ENDING -> height * 0.02f
        else -> {
            val level = audioLevel.coerceAtLeast(0.03f)
            height * level.coerceAtMost(1f) * 0.45f
        }
    }

/** 1:1 iOS `wavePath` (VoiceCallWaveView.swift:65-91): one damped sine sampled every 2 px. */
private fun buildWavePath(index: Int, time: Float, width: Float, midY: Float, amplitude: Float): Path {
    val frequency = 1.5 + index * 0.5
    val phase = time * (2.0 + index * 0.35)
    val dampedAmplitude = amplitude * (1.0 - index * 0.22)
    val path = Path()
    var x = 0f
    var started = false
    while (x <= width) {
        val relativeX = if (width > 0f) x / width else 0f
        val y = midY + (dampedAmplitude * sin(relativeX * PI * frequency * 2 + phase)).toFloat()
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
        x += 2f
    }
    return path
}
