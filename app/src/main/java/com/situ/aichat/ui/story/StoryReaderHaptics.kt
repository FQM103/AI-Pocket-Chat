package com.situ.aichat.ui.story

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 阅读器触觉反馈（对应 iOS `StoryReaderFeedback` + `StoryHapticsEngine` 中阅读器实际用到的部分）。
 *
 * iOS 用 CoreHaptics/UIImpactFeedbackGenerator；安卓用原生 [Vibrator]。现存两类
 * （`heavy`/`heartbeat` 随 2026-08-03 屏幕特效整族退役——它们的唯一消费者是 effect 标签）：
 * - [light]：轻触（做选择 / 撤销选择 / 各按钮）。
 * - [selection]：选择区出现时的选择反馈（= iOS `.sensoryFeedback(.selection)`）。
 *
 * 无振动器 / 无 VIBRATE 时静默降级。决策③：iOS CoreHaptics 强弱映射到 Android 预设效果 TICK/HEAVY_CLICK。
 */
class StoryReaderHaptics(private val vibrator: Vibrator?) {

    fun light() = predefined(VibrationEffect.EFFECT_TICK)

    fun selection() = predefined(VibrationEffect.EFFECT_TICK)

    private fun predefined(effectId: Int) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching { v.vibrate(VibrationEffect.createPredefined(effectId)) }
    }
}

/** 从当前 Context 取系统振动器构造 [StoryReaderHaptics]（API 31+ 用 VibratorManager）。 */
@Composable
fun rememberStoryReaderHaptics(): StoryReaderHaptics {
    val context = LocalContext.current
    return remember {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        StoryReaderHaptics(vibrator)
    }
}
