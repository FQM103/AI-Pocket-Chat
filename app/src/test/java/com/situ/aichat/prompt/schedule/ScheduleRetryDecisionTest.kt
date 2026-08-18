package com.situ.aichat.prompt.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日程失败自动延迟重试决策纯函数单测（P15·P0-6）。**断言从 iOS 真实逻辑反推**
 * （`ScheduleCoordinator.scheduleDelayedRetry`：retryDelays=[300,900,1800]、< 3 次才重试、延迟取可重试集最大次数、各 +1），
 * 抓移植偏差，不依赖真机 / WorkManager（`./gradlew :app:testDebugUnitTest`）。
 */
class ScheduleRetryDecisionTest {

    @Test
    fun firstFailure_delays300_andIncrementsToOne() {
        val d = nextScheduleRetry(setOf("A"), emptyMap())!!
        assertEquals(300L, d.delaySeconds)
        assertEquals(setOf("A"), d.retryable)
        assertEquals(1, d.updatedCounts["A"])
    }

    @Test
    fun secondFailure_delays900() {
        assertEquals(900L, nextScheduleRetry(setOf("A"), mapOf("A" to 1))!!.delaySeconds)
    }

    @Test
    fun thirdFailure_delays1800() {
        assertEquals(1800L, nextScheduleRetry(setOf("A"), mapOf("A" to 2))!!.delaySeconds)
    }

    @Test
    fun atMaxRetries_givesUp() {
        assertNull(nextScheduleRetry(setOf("A"), mapOf("A" to 3)))
    }

    @Test
    fun batchDelay_drivenByMaxCount() {
        // A=0、B=2 → maxCount=2 → 1800，两者都重试。
        val d = nextScheduleRetry(setOf("A", "B"), mapOf("A" to 0, "B" to 2))!!
        assertEquals(1800L, d.delaySeconds)
        assertEquals(setOf("A", "B"), d.retryable)
        assertEquals(1, d.updatedCounts["A"])
        assertEquals(3, d.updatedCounts["B"])
    }

    @Test
    fun exhaustedCharFilteredOut_delayFromRemaining() {
        // A=3（已满，过滤掉）、B=0 → 仅 B 重试，maxCount(可重试)=0 → 300。
        val d = nextScheduleRetry(setOf("A", "B"), mapOf("A" to 3, "B" to 0))!!
        assertEquals(300L, d.delaySeconds)
        assertEquals(setOf("B"), d.retryable)
        assertEquals(3, d.updatedCounts["A"]) // 满次角色保留原值不动
        assertEquals(1, d.updatedCounts["B"])
    }

    @Test
    fun allExhausted_givesUp() {
        assertNull(nextScheduleRetry(setOf("A", "B"), mapOf("A" to 3, "B" to 3)))
    }

    @Test
    fun crossDayReset_freshFailureDelays300Again() {
        // 跨日清空计数表后再失败 → 又从 300 起（验证计数跨日重置）。
        val cleared = emptyMap<String, Int>()
        assertEquals(300L, nextScheduleRetry(setOf("A"), cleared)!!.delaySeconds)
    }
}
