package com.situ.aichat.story

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryVisualPerformance.current` 测试：2026-08-03 格式块精简后只剩动画一维——
 * reveal 渐显 + 文字逐帧 = readingAnimationsEnabled && !reduceMotion
 * （粒子维度、长章降档、帧率与 lowPowerMode 输入随天气粒子整族退役，对应用例一并删除）。
 */
class StoryVisualPerformanceTest {

    @Test fun normal_all_on() {
        val p = StoryVisualPerformance.current(readingAnimationsEnabled = true, reduceMotion = false)
        assertTrue(p.allowsRevealAnimations)
        assertTrue(p.allowsAnimatedText)
    }

    @Test fun reduce_motion_disables_animations() {
        val p = StoryVisualPerformance.current(readingAnimationsEnabled = true, reduceMotion = true)
        assertFalse(p.allowsRevealAnimations)
        assertFalse(p.allowsAnimatedText) // reduceMotion 是无障碍底线：开关开着也压制
    }

    @Test fun animations_toggle_off_disables_both() {
        val p = StoryVisualPerformance.current(readingAnimationsEnabled = false, reduceMotion = false)
        assertFalse(p.allowsRevealAnimations)
        assertFalse(p.allowsAnimatedText)
    }

    @Test fun animations_off_plus_reduce_motion_still_off() {
        val p = StoryVisualPerformance.current(readingAnimationsEnabled = false, reduceMotion = true)
        assertFalse(p.allowsRevealAnimations)
        assertFalse(p.allowsAnimatedText)
    }
}
