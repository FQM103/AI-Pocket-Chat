package com.situ.aichat.story

/**
 * 阅读器视觉性能档（源自 iOS `StoryVisualPerformance`）。
 *
 * **2026-08-03 格式块精简后只剩动画一维**：reveal 渐显 + 文字逐帧 = readingAnimationsEnabled && !reduceMotion
 * （粒子维度连同 9 个计数字段、长章降档、帧率字段随天气粒子整族退役；lowPowerMode 因此不再是输入）。
 * 安卓 reduceMotion = `ANIMATOR_DURATION_SCALE==0`。纯数据。
 */
data class StoryVisualPerformance(
    val allowsRevealAnimations: Boolean,
    val allowsAnimatedText: Boolean,
) {
    companion object {
        fun current(
            readingAnimationsEnabled: Boolean,
            reduceMotion: Boolean,
        ): StoryVisualPerformance {
            // reveal 渐显 + 文字逐帧 → 归「阅读动画」开关；reduceMotion 抑制；低电量保留（轻量）。
            val animationsAllowed = readingAnimationsEnabled && !reduceMotion
            return StoryVisualPerformance(
                allowsRevealAnimations = animationsAllowed,
                allowsAnimatedText = animationsAllowed,
            )
        }
    }
}
