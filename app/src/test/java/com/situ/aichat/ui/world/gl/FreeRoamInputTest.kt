package com.situ.aichat.ui.world.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FreeRoamInput] T1-2（W15 图纸 §7·断言从 §4A.1 规格独立反推）：累积→consume 取走复位；lastPan=末事件；
 * pinch 乘积；twist/tilt 死区闩锁（阈内含跨阈那次丢弃·解锁后段内全量生效·endTwoFinger 重上锁）；
 * clear() 全清含 focal。
 */
class FreeRoamInputTest {

    private fun input(deadzone: Float = 10f) = FreeRoamInput(tiltDeadzonePx = deadzone)

    @Test
    fun pan_accumulates_lastIsFinalEvent_consumeResets() {
        val input = input()
        input.onPanBy(1f, 2f)
        input.onPanBy(3f, 4f)
        val f = input.consume()
        assertEquals("panDx 累计 1+3", 4f, f.panDx, 0f)
        assertEquals("panDz 累计 2+4", 6f, f.panDz, 0f)
        assertEquals("lastPanDx = 末事件", 3f, f.lastPanDx, 0f)
        assertEquals("lastPanDz = 末事件", 4f, f.lastPanDz, 0f)
        val f2 = input.consume()
        assertEquals("取走后复位", 0f, f2.panDx, 0f)
        assertEquals("取走后复位", 0f, f2.panDz, 0f)
    }

    @Test
    fun pinch_isProductOfRatios_resetsTo1() {
        val input = input()
        input.onPinchBy(0.9f)
        input.onPinchBy(1.1f)
        assertEquals("乘积 0.9·1.1", 0.99f, input.consume().pinch, 1e-6f)
        assertEquals("复位为 1", 1f, input.consume().pinch, 0f)
    }

    @Test
    fun twist_deadzoneLatch_discardsInThreshold_thenFullyCounts() {
        val input = input()
        input.beginTwoFinger()
        input.onTwistBy(0.05f) // Σ|·|=0.05 < 0.06 → 仍锁
        assertEquals("阈内 twist 恒 0", 0f, input.consume().twist, 0f)
        input.onTwistBy(0.02f) // Σ=0.07 > 0.06 → 解锁·但跨阈那次丢弃
        assertEquals("跨阈那次丢弃", 0f, input.consume().twist, 0f)
        input.onTwistBy(0.03f) // 已解锁 → 全量计
        assertEquals("解锁后全量", 0.03f, input.consume().twist, 1e-6f)
    }

    @Test
    fun tilt_deadzoneUsesCtorParam_thenCounts() {
        val input = input(deadzone = 12f)
        input.beginTwoFinger()
        input.onTiltBy(8f) // 8 < 12 → 锁
        assertEquals(0f, input.consume().tiltDy, 0f)
        input.onTiltBy(6f) // 14 > 12 → 解锁·丢弃
        assertEquals(0f, input.consume().tiltDy, 0f)
        input.onTiltBy(5f) // 解锁 → 计
        assertEquals(5f, input.consume().tiltDy, 1e-6f)
    }

    @Test
    fun endTwoFinger_reLocksDeadzones() {
        val input = input()
        input.beginTwoFinger()
        input.onTwistBy(0.05f) // Σ=0.05 < 0.06 → 锁
        input.onTwistBy(0.05f) // Σ=0.10 > 0.06 → 解锁·跨阈那次丢弃
        input.onTwistBy(0.04f) // 已解锁 → 计
        assertEquals("解锁后计", 0.04f, input.consume().twist, 1e-6f)
        input.endTwoFinger() // 重上锁
        input.beginTwoFinger()
        input.onTwistBy(0.05f) // 新段内 0.05 < 0.06 → 仍锁
        assertEquals("重上锁后阈内恒 0", 0f, input.consume().twist, 0f)
    }

    @Test
    fun focal_persistsAcrossConsume_clearedByEndAndClear() {
        val input = input()
        input.setPinchFocal(5f, 3f)
        val f = input.consume()
        assertTrue(f.hasFocal); assertEquals(5f, f.focalX, 0f); assertEquals(3f, f.focalZ, 0f)
        assertTrue("focal 跨 consume 保留", input.consume().hasFocal)
        input.endTwoFinger()
        assertFalse("endTwoFinger 清 focal", input.consume().hasFocal)
        input.setPinchFocal(1f, 2f)
        input.clear()
        assertFalse("clear 清 focal", input.consume().hasFocal)
    }

    @Test
    fun clear_wipesAllAccumulators() {
        val input = input()
        input.onPanBy(9f, 9f)
        input.onPinchBy(2f)
        input.beginTwoFinger()
        repeat(3) { input.onTwistBy(0.05f); input.onTiltBy(20f) } // 解锁两通道
        input.onTwistBy(0.1f); input.onTiltBy(30f)
        input.clear()
        val f = input.consume()
        assertEquals(0f, f.panDx, 0f); assertEquals(0f, f.panDz, 0f)
        assertEquals(1f, f.pinch, 0f)
        assertEquals(0f, f.twist, 0f); assertEquals(0f, f.tiltDy, 0f)
        assertFalse(f.hasFocal)
    }
}
