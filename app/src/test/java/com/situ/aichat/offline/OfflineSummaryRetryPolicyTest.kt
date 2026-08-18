package com.situ.aichat.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfflineSummaryRetryPolicy] 单测——断言反推 iOS `OfflineSummaryRetryPolicyTests` 真实值：
 * 退避表边界 60/300/1800/7200/86400 秒（毫秒）+ failCount==0/null 放行 + index 钳位 + 兜底阈值 5。
 */
class OfflineSummaryRetryPolicyTest {

    private val min = 60_000L
    private val now = 1_000_000_000_000L

    // ---- shouldAttempt: 放行条件 ----

    @Test fun neverAttempted_allows() {
        // lastAttemptAt==null → 始终允许（无论 failCount，= iOS guard let lastAttemptAt）。
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(failCount = 0, lastAttemptAt = null, now = now))
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(failCount = 3, lastAttemptAt = null, now = now))
    }

    @Test fun failCountZero_allows() {
        // failCount==0 即使有 lastAttemptAt 也允许（= iOS guard failCount > 0）。
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(failCount = 0, lastAttemptAt = now - 1, now = now))
    }

    // ---- shouldAttempt: 退避窗口边界（窗口=对应秒×1000；elapsed >= window 才放行）----

    @Test fun fail1_window60s() {
        val w = 60 * min / 60 // 60_000
        assertFalse(OfflineSummaryRetryPolicy.shouldAttempt(1, now - (w - 1), now)) // 差 59.999s → 拦
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(1, now - w, now))         // 差 60s → 放（>=）
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(1, now - (w + 1), now))
    }

    @Test fun fail2_window5min() {
        val w = 300_000L
        assertFalse(OfflineSummaryRetryPolicy.shouldAttempt(2, now - (w - 1), now))
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(2, now - w, now))
    }

    @Test fun fail3_window30min() {
        val w = 1_800_000L
        assertFalse(OfflineSummaryRetryPolicy.shouldAttempt(3, now - (w - 1), now))
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(3, now - w, now))
    }

    @Test fun fail4_window2h() {
        val w = 7_200_000L
        assertFalse(OfflineSummaryRetryPolicy.shouldAttempt(4, now - (w - 1), now))
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(4, now - w, now))
    }

    @Test fun fail5_window1day() {
        val w = 86_400_000L
        assertFalse(OfflineSummaryRetryPolicy.shouldAttempt(5, now - (w - 1), now))
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(5, now - w, now))
    }

    @Test fun failBeyond5_clampsToLastWindow() {
        // index = min(failCount-1, 4) → failCount=6/7… 仍用 86400s 窗口（钳位保险项，= iOS）。
        val w = 86_400_000L
        assertFalse(OfflineSummaryRetryPolicy.shouldAttempt(6, now - (w - 1), now))
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(6, now - w, now))
        assertTrue(OfflineSummaryRetryPolicy.shouldAttempt(99, now - w, now))
    }

    // ---- shouldFallbackNow: 阈值 5 ----

    @Test fun shouldFallbackNow_threshold5() {
        assertFalse(OfflineSummaryRetryPolicy.shouldFallbackNow(0))
        assertFalse(OfflineSummaryRetryPolicy.shouldFallbackNow(4))
        assertTrue(OfflineSummaryRetryPolicy.shouldFallbackNow(5))
        assertTrue(OfflineSummaryRetryPolicy.shouldFallbackNow(6))
    }

    @Test fun maxAttemptsConstant() {
        org.junit.Assert.assertEquals(5, OfflineSummaryRetryPolicy.MAX_ATTEMPTS_BEFORE_FALLBACK)
    }
}
