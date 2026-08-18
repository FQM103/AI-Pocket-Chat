package com.situ.aichat.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 全局分级触觉反馈（对应 iOS 全 app 用到的 `.sensoryFeedback` 语义档）。
 *
 * iOS 用 UIFeedbackGenerator / CoreHaptics（impact .light/.medium/.heavy、.soft、.selection、.success、.error）；
 * 安卓沿用 [com.situ.aichat.ui.story.StoryReaderHaptics] 已在阅读器验证过的原生 [Vibrator] 范式
 * （createPredefined / createWaveform + hasVibrator 守卫 + runCatching 静默降级）。语义档 1:1 映射 iOS
 * （见 ChatView.swift:338-340 send=impact.light / receive=impact.soft 0.6 / error=.error，及全 app sensoryFeedback 用法）：
 * - [light]     = impact(.light)        → EFFECT_TICK
 * - [medium]    = impact(.medium)       → EFFECT_CLICK
 * - [heavy]     = impact(.heavy)        → EFFECT_HEAVY_CLICK
 * - [soft]      = impact(.soft, 0.6)    → 低幅单脉冲（无幅度控制回退 TICK）
 * - [selection] = .selection            → EFFECT_TICK
 * - [success]   = .success              → 上扬双脉冲
 * - [error]     = .error                → 三段较重脉冲
 *
 * 无振动器 / 无 VIBRATE 权限时静默降级。本类为 P15.2a 共享底座，下游各模块逐点接入（点赞 / 礼物揭晓 /
 * 宠物护理 / 红包礼物送出 / 贴纸长按 / 写日记选心情 / 语音上滑取消 等），DRY 一处实现。
 */
class AppHaptics(private val vibrator: Vibrator?) {

    fun light() = predefined(VibrationEffect.EFFECT_TICK)

    fun medium() = predefined(VibrationEffect.EFFECT_CLICK)

    fun heavy() = predefined(VibrationEffect.EFFECT_HEAVY_CLICK)

    fun selection() = predefined(VibrationEffect.EFFECT_TICK)

    /** 柔触：对齐 iOS impact(.soft, intensity 0.6)，低幅单脉冲。 */
    fun soft() {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(20, 120)
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        }
        runCatching { v.vibrate(effect) }
    }

    /** 成功：上扬双脉冲（对齐 iOS .success 的两段确认感）。 */
    fun success() = waveform(longArrayOf(0, 30, 80, 45), intArrayOf(0, 150, 0, 220))

    /** 失败：三段较重脉冲（对齐 iOS .error）。 */
    fun error() = waveform(longArrayOf(0, 50, 60, 50, 60, 60), intArrayOf(0, 200, 0, 200, 0, 230))

    private fun predefined(effectId: Int) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching { v.vibrate(VibrationEffect.createPredefined(effectId)) }
    }

    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        runCatching { v.vibrate(effect) }
    }

    companion object {
        /** 从任意 Context 取系统振动器构造（API 31+ 用 VibratorManager）。 */
        fun from(context: Context): AppHaptics {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            return AppHaptics(vibrator)
        }
    }
}

/** 应用根注入的全局触觉入口（[com.situ.aichat.ui.AppRoot] provide），任意 composable 经 `LocalAppHaptics.current` 取用。 */
val LocalAppHaptics = staticCompositionLocalOf<AppHaptics> {
    error("LocalAppHaptics 未提供（应在 AppRoot 注入）")
}

/** 取（缓存）一个绑定 applicationContext 的 [AppHaptics]，用于尚未经 [LocalAppHaptics] 的局部场景。 */
@Composable
fun rememberAppHaptics(): AppHaptics {
    val context = LocalContext.current
    return remember { AppHaptics.from(context.applicationContext) }
}
