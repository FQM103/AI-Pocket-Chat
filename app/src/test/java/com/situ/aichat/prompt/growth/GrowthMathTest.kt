package com.situ.aichat.prompt.growth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 成长系统纯函数单测。**断言值从 iOS 真实逻辑推得**（GrowthAnalysisCoordinator.scaledDelta /
 * equilibriumPoint、RelationshipAnalysisCoordinator.computeNextMessageCount），用于抓移植偏差，
 * 不依赖真机/不烧 API key（`./gradlew :app:testDebugUnitTest`）。
 */
class GrowthMathTest {

    // MARK: - scaledDelta（软上限：高段涨慢跌快、低段反之，缩放后绝对值 ≥1，远离零取整）

    @Test fun scaledDelta_zeroIsZero() {
        assertEquals(0, scaledDelta(50, 0))
        assertEquals(0, scaledDelta(0, 0))
    }

    @Test fun scaledDelta_midBandNoScale() {
        assertEquals(3, scaledDelta(50, 3))    // 20..<60 → 1.0
        assertEquals(-3, scaledDelta(50, -3))
        assertEquals(5, scaledDelta(20, 5))    // 20 → default 1.0（不在 0..<20）
        assertEquals(5, scaledDelta(59, 5))    // 59 → default 1.0（不在 60..<80）
    }

    @Test fun scaledDelta_highBandSlowGrowthFastDrop() {
        assertEquals(2, scaledDelta(90, 5))    // 0.3 → 1.5 → ceil 2
        assertEquals(-10, scaledDelta(90, -5)) // 2.0 → -10
        assertEquals(2, scaledDelta(80, 5))    // 80 属 80+ → 0.3 → 1.5 → 2
        assertEquals(-10, scaledDelta(80, -5)) // 80+ → 2.0
    }

    @Test fun scaledDelta_upperMidBand() {
        assertEquals(3, scaledDelta(70, 5))    // 0.6 → 3.0
        assertEquals(-8, scaledDelta(70, -5))  // 1.5 → -7.5 → floor -8
        assertEquals(3, scaledDelta(60, 5))    // 60 属 60..<80 → 0.6 → 3.0
    }

    @Test fun scaledDelta_lowBandAccelRecoverProtectDrop() {
        assertEquals(5, scaledDelta(10, 3))    // 1.5 → 4.5 → ceil 5
        assertEquals(-2, scaledDelta(10, -3))  // 0.5 → -1.5 → floor -2
        assertEquals(8, scaledDelta(19, 5))    // 19 属 0..<20 → 1.5 → 7.5 → ceil 8
    }

    @Test fun scaledDelta_minAbsoluteValueIsOne() {
        assertEquals(1, scaledDelta(90, 1))    // 0.3 → 0.3 → ceil 1
        assertEquals(-1, scaledDelta(10, -1))  // 0.5 → -0.5 → floor -1
    }

    // MARK: - equilibriumPoint（按当前关系名关键词分层，顺序：亲密 > 亲近 > 朋友 > 疏远 > 默认）

    @Test fun equilibriumPoint_nullIsDefault() {
        assertEquals(35, equilibriumPoint(null))
    }

    @Test fun equilibriumPoint_intimate70() {
        assertEquals(70, equilibriumPoint("恋人"))
        assertEquals(70, equilibriumPoint("老夫老妻"))
        assertEquals(70, equilibriumPoint("灵魂伴侣"))
        assertEquals(70, equilibriumPoint("lover"))
        assertEquals(70, equilibriumPoint("Soulmate")) // 小写匹配
    }

    @Test fun equilibriumPoint_closeBeatsFriendOnOrder() {
        // "好朋友" 含 "朋友"，但 close 先于 friend 检测 → 55 而非 40（顺序关键）
        assertEquals(55, equilibriumPoint("好朋友"))
        assertEquals(55, equilibriumPoint("死党"))
        assertEquals(55, equilibriumPoint("暧昧"))
    }

    @Test fun equilibriumPoint_friendAndDistantAndCustom() {
        assertEquals(40, equilibriumPoint("朋友"))
        assertEquals(40, equilibriumPoint("网友"))
        assertEquals(20, equilibriumPoint("陌生人"))
        assertEquals(20, equilibriumPoint("点头之交"))
        assertEquals(35, equilibriumPoint("欢喜冤家")) // 无关键词的自定义关系 → 默认
    }

    // MARK: - computeNextMessageCount（changed→0；否则 -15 而非归零，保留累积）

    @Test fun computeNextMessageCount_changedResetsToZero() {
        assertEquals(0, computeNextMessageCount(80, true))
        assertEquals(0, computeNextMessageCount(0, true))
    }

    @Test fun computeNextMessageCount_unchangedSubtracts15FloorZero() {
        assertEquals(35, computeNextMessageCount(50, false))
        assertEquals(0, computeNextMessageCount(10, false)) // max(0, -5)
        assertEquals(0, computeNextMessageCount(0, false))
        assertEquals(85, computeNextMessageCount(100, false))
    }
}
