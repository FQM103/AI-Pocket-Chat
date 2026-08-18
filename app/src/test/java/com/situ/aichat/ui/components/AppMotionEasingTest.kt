package com.situ.aichat.ui.components

import androidx.compose.animation.core.Easing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chunk 0（参照 Telegram 缓动调色板）T1 单测：从「曲线该有的性质」独立反推断言，**不照搬实现常量**。
 *
 * - 端点：transform(0)=0、transform(1)=1（所有曲线）。
 * - EaseOut / EaseInOut / EaseOutQuint：单调不减、不过冲（值域 [0,1]）。
 * - EaseOutQuint：强减速 → 半程已走过大半路程（transform(0.5) 明显 > 0.8，线性应为 0.5）。
 * - EaseOutBack：必须过冲（采样峰值 > 1，证明「冲过头再回」）。
 */
class AppMotionEasingTest {

    private fun samples(n: Int): List<Float> = (0..n).map { it / n.toFloat() }

    private fun assertEndpoints(e: Easing) {
        assertEquals(0f, e.transform(0f), 1e-4f)
        assertEquals(1f, e.transform(1f), 1e-4f)
    }

    private fun assertMonotonicNonDecreasing(e: Easing) {
        var prev = e.transform(0f)
        for (x in samples(200).drop(1)) {
            val v = e.transform(x)
            assertTrue("应单调不减 @x=$x: $prev→$v", v >= prev - 1e-4f)
            prev = v
        }
    }

    private fun assertWithinUnit(e: Easing) {
        for (x in samples(200)) {
            val v = e.transform(x)
            assertTrue("应不过冲 @x=$x: $v", v >= -1e-3f && v <= 1f + 1e-3f)
        }
    }

    @Test
    fun easeOut_endpoints_monotonic_noOvershoot() {
        assertEndpoints(AppMotion.EaseOut)
        assertMonotonicNonDecreasing(AppMotion.EaseOut)
        assertWithinUnit(AppMotion.EaseOut)
    }

    @Test
    fun easeInOut_endpoints_monotonic_noOvershoot() {
        assertEndpoints(AppMotion.EaseInOut)
        assertMonotonicNonDecreasing(AppMotion.EaseInOut)
        assertWithinUnit(AppMotion.EaseInOut)
    }

    @Test
    fun emphasizedDecelerate_endpoints_monotonic() {
        assertEndpoints(AppMotion.EmphasizedDecelerate)
        assertMonotonicNonDecreasing(AppMotion.EmphasizedDecelerate)
    }

    @Test
    fun easeOutQuint_isStrongDecelerate() {
        assertEndpoints(AppMotion.EaseOutQuint)
        assertMonotonicNonDecreasing(AppMotion.EaseOutQuint)
        assertWithinUnit(AppMotion.EaseOutQuint)
        val mid = AppMotion.EaseOutQuint.transform(0.5f)
        assertTrue("EaseOutQuint(0.5) 应 > 0.8（强减速·前半程走过大半），实际 $mid", mid > 0.8f)
    }

    @Test
    fun easeOutBack_overshootsAboveOne() {
        assertEndpoints(AppMotion.EaseOutBack)
        val peak = samples(200).maxOf { AppMotion.EaseOutBack.transform(it) }
        assertTrue("EaseOutBack 应过冲 >1（冲过头再回），实际峰值 $peak", peak > 1f)
    }

    @Test
    fun overshootEasing_endpoints_andOvershoots() {
        // Chunk 1 按压松手相曲线（= Android OvershootInterpolator(5.0) 公式）：端点 0/1 + 明显过冲。
        val e = AppMotion.overshootEasing(5f)
        assertEquals(0f, e.transform(0f), 1e-4f)
        assertEquals(1f, e.transform(1f), 1e-4f)
        val peak = samples(200).maxOf { e.transform(it) }
        assertTrue("overshoot(5) 应明显过冲 >1（松手回弹），实际峰值 $peak", peak > 1.1f)
    }
}
