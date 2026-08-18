package com.situ.aichat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批0（P15.2-P1）：iOS `spring(duration:bounce:)` → Compose `spring(dampingRatio, stiffness)`
 * 换算锁定。断言从 iOS 真值反推（AppTheme.swift MessageSpring / MomentPostCard.swift:191-205），
 * 防止移植期把 bounce/duration 抄错或换算公式回归。
 */
class AppMotionTest {

    @Test
    fun `damping ratio = 1 minus iOS bounce`() {
        // iOS MessageSpring.send: bounce 0.12 → ζ 0.88
        assertEquals(0.88f, iosSpringDampingRatio(bounce = 0.12f), 1e-6f)
        // iOS MessageSpring.receive: bounce 0.10 → ζ 0.90
        assertEquals(0.90f, iosSpringDampingRatio(bounce = 0.10f), 1e-6f)
        // iOS 点赞: bounce 0.5 → ζ 0.5（= Compose DampingRatioMediumBouncy）
        assertEquals(0.5f, iosSpringDampingRatio(bounce = 0.5f), 1e-6f)
        // 边界：bounce 0 = 临界阻尼
        assertEquals(1f, iosSpringDampingRatio(bounce = 0f), 1e-6f)
    }

    @Test
    fun `stiffness from iOS duration via omega squared`() {
        // duration 0.35s → (2π/0.35)² ≈ 322.27
        assertEquals(322.27f, iosSpringStiffness(durationSeconds = 0.35f), 0.05f)
        // duration 0.3s → (2π/0.3)² ≈ 438.65
        assertEquals(438.65f, iosSpringStiffness(durationSeconds = 0.3f), 0.05f)
        // 单调性：更短的 duration → 更硬的弹簧
        assertTrue(
            iosSpringStiffness(durationSeconds = 0.3f) > iosSpringStiffness(durationSeconds = 0.35f),
        )
    }

    @Test
    fun `stiffness rejects zero or negative duration (P5 safety valve)`() {
        // duration=0 → 旧码 ω=Infinity → k=Infinity（下游 NaN/卡死）；守卫改为快速失败。
        assertThrows(IllegalArgumentException::class.java) { iosSpringStiffness(durationSeconds = 0f) }
        assertThrows(IllegalArgumentException::class.java) { iosSpringStiffness(durationSeconds = -0.3f) }
        // 正常正值仍是有限正刚度（守卫不误伤）。
        assertTrue(iosSpringStiffness(durationSeconds = 0.35f).isFinite())
        assertTrue(iosSpringStiffness(durationSeconds = 0.35f) > 0f)
    }

    @Test
    fun `smoothSpring matches iOS smooth duration 0_3 bounce 0`() {
        // P1-11：iOS .smooth(duration: 0.3) = spring(duration: 0.3, bounce: 0)——临界阻尼 ζ=1、k=(2π/0.3)²。
        val spec = AppMotion.smoothSpring<Float>()
        assertEquals(1f, spec.dampingRatio, 1e-6f)
        assertEquals(438.65f, spec.stiffness, 0.05f)
    }

    @Test
    fun `AppMotion constants reflect iOS MessageSpring values`() {
        assertEquals(0.88f, AppMotion.messageSendDamping, 1e-6f)
        assertEquals(0.90f, AppMotion.messageReceiveDamping, 1e-6f)
        assertEquals(0.5f, AppMotion.likeBounceDamping, 1e-6f)
        // send/receive 同 0.35s 时长 → 刚度一致
        assertEquals(AppMotion.messageSendStiffness, AppMotion.messageReceiveStiffness, 1e-6f)
        // iOS .smooth(0.3)/.smooth(0.35)
        assertEquals(300, AppMotion.SMOOTH_MS)
        assertEquals(350, AppMotion.SMOOTH_LONG_MS)
    }
}
