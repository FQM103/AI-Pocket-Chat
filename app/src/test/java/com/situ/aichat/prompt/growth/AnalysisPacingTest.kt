package com.situ.aichat.prompt.growth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 活人感一期 P3 · T1-6（E14）：[AnalysisPacing] 三阶梯纯函数全边界。断言从规格独立反推（首次 10 / 第二次 25 /
 * 之后用户值；userInterval 为硬上限 minOf），非照搬实现。
 */
class AnalysisPacingTest {

    // ---- growthInterval：首次 10 / 第二次 25 / 之后 userInterval，且 userInterval 恒为上限 ----

    @Test fun `growth ladder at default interval 30`() {
        assertEquals(10, AnalysisPacing.growthInterval(totalAnalysisCount = 0, userInterval = 30))
        assertEquals(25, AnalysisPacing.growthInterval(totalAnalysisCount = 1, userInterval = 30))
        assertEquals(30, AnalysisPacing.growthInterval(totalAnalysisCount = 2, userInterval = 30))
        assertEquals(30, AnalysisPacing.growthInterval(totalAnalysisCount = 9, userInterval = 30))
    }

    @Test fun `growth ladder clamps to a smaller user interval`() {
        // 用户把 interval 调到 5 → 阶梯每档都不得超过 5（min）。
        assertEquals(5, AnalysisPacing.growthInterval(totalAnalysisCount = 0, userInterval = 5))
        assertEquals(5, AnalysisPacing.growthInterval(totalAnalysisCount = 1, userInterval = 5))
        assertEquals(5, AnalysisPacing.growthInterval(totalAnalysisCount = 2, userInterval = 5))
    }

    @Test fun `growth ladder defends against negative count`() {
        // 防御：count<0 归入首次档。
        assertEquals(10, AnalysisPacing.growthInterval(totalAnalysisCount = -1, userInterval = 30))
    }

    @Test fun `growth ladder with user interval between 10 and 25`() {
        // interval=20：首次 min(10,20)=10，第二次 min(25,20)=20，之后 20。
        assertEquals(10, AnalysisPacing.growthInterval(totalAnalysisCount = 0, userInterval = 20))
        assertEquals(20, AnalysisPacing.growthInterval(totalAnalysisCount = 1, userInterval = 20))
        assertEquals(20, AnalysisPacing.growthInterval(totalAnalysisCount = 2, userInterval = 20))
    }

    // ---- structuredInterval：首次 min(10, interval)，之后 interval ----

    @Test fun `structured first-ever accelerates then reverts`() {
        assertEquals(10, AnalysisPacing.structuredInterval(lastExtractionDate = null, userInterval = 30))
        assertEquals(30, AnalysisPacing.structuredInterval(lastExtractionDate = 1_000L, userInterval = 30))
    }

    @Test fun `structured first-ever clamps to smaller interval`() {
        assertEquals(5, AnalysisPacing.structuredInterval(lastExtractionDate = null, userInterval = 5))
        assertEquals(5, AnalysisPacing.structuredInterval(lastExtractionDate = 1_000L, userInterval = 5))
    }

    // ---- relationshipChainThreshold：从未评估过 10，否则 30 ----

    @Test fun `relationship chain threshold first-ever 10 else 30`() {
        assertEquals(10, AnalysisPacing.relationshipChainThreshold(lastRelationshipAnalysisDate = null))
        assertEquals(30, AnalysisPacing.relationshipChainThreshold(lastRelationshipAnalysisDate = 1_000L))
        assertEquals(30, AnalysisPacing.relationshipChainThreshold(lastRelationshipAnalysisDate = 0L))
    }
}
