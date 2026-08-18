package com.situ.aichat.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日志 token 展示格式化单测（批 D·纯函数）。断言从 iOS `formatTokenCount`/`tokenDisplayText`/`percentageText` 反推。
 * 避开 .x5 半值边界（C-locale 半偶 vs Java HALF_UP 差异），只锁无歧义口径。
 */
class LogTokenFormatTest {

    @Test
    fun compactUsesKAboveThousand() {
        assertEquals("0", LogTokenFormat.compact(0))
        assertEquals("999", LogTokenFormat.compact(999))
        assertEquals("1.0k", LogTokenFormat.compact(1000))
        assertEquals("1.5k", LogTokenFormat.compact(1500))
        assertEquals("12.3k", LogTokenFormat.compact(12345))
    }

    @Test
    fun estimatePrefixOnlyWhenEstimated() {
        assertEquals("≈1.5k", LogTokenFormat.withEstimatePrefix(1500, isEstimated = true))
        assertEquals("1.5k", LogTokenFormat.withEstimatePrefix(1500, isEstimated = false))
        assertEquals("≈42", LogTokenFormat.withEstimatePrefix(42, isEstimated = true))
    }

    @Test
    fun percentHandlesZeroTotalSubOnePercentAndNormal() {
        assertEquals("0%", LogTokenFormat.percent(part = 50, total = 0))    // total≤0 守卫
        assertEquals("0%", LogTokenFormat.percent(part = 0, total = -1))
        assertEquals("<1%", LogTokenFormat.percent(part = 0, total = 1000)) // 0% 也走 <1%（1:1 iOS）
        assertEquals("<1%", LogTokenFormat.percent(part = 1, total = 1000)) // 0.1%
        assertEquals("1%", LogTokenFormat.percent(part = 10, total = 1000)) // 1.0% 恰达阈值
        assertEquals("25%", LogTokenFormat.percent(part = 250, total = 1000))
    }
}
