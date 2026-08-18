package com.situ.aichat.notification

import com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能时机·学习反馈纯算法单测（P6.1e）。断言全部从 iOS `NotificationLearningService` 的常量/公式反推：
 * - clampScore = min(0.95, max(0.1, v))
 * - 正反馈平滑 = clamp(s·(1−0.3) + 0.3)；负反馈平滑 = clamp(s·(1−0.12))
 * - repetitionPenalty：<12h 罚 0.16 / <24h 罚 0.08 / 否则 0（边界闭区间对齐 iOS `<`）
 * - selectResponseCandidate：2h 反馈窗内、scheduledAt 最近的一条
 */
class NotificationLearningServiceTest {

    private val hour = 60L * 60 * 1000

    // MARK: - clampScore

    @Test
    fun `clampScore 钳到 0_1 到 0_95`() {
        assertEquals(0.5, NotificationLearningService.clampScore(0.5), 1e-9)
        assertEquals(0.95, NotificationLearningService.clampScore(1.0), 1e-9)
        assertEquals(0.95, NotificationLearningService.clampScore(0.96), 1e-9)
        assertEquals(0.1, NotificationLearningService.clampScore(0.0), 1e-9)
        assertEquals(0.1, NotificationLearningService.clampScore(0.09), 1e-9)
    }

    // MARK: - 平滑分（从 iOS 公式逐步反推）

    @Test
    fun `正反馈平滑分递增`() {
        // 0.5 → 0.5·0.7 + 0.3 = 0.65 → 0.65·0.7 + 0.3 = 0.755
        assertEquals(0.65, NotificationLearningService.respondedScore(0.5), 1e-9)
        assertEquals(0.755, NotificationLearningService.respondedScore(0.65), 1e-9)
        // 高分被 clamp：0.95·0.7 + 0.3 = 0.965 → 0.95
        assertEquals(0.95, NotificationLearningService.respondedScore(0.95), 1e-9)
    }

    @Test
    fun `负反馈平滑分递减`() {
        // 0.5 → 0.5·0.88 = 0.44 → 0.44·0.88 = 0.3872
        assertEquals(0.44, NotificationLearningService.expiredScore(0.5), 1e-9)
        assertEquals(0.3872, NotificationLearningService.expiredScore(0.44), 1e-9)
        // 低分被 clamp：0.1·0.88 = 0.088 → 0.1
        assertEquals(0.1, NotificationLearningService.expiredScore(0.1), 1e-9)
    }

    // MARK: - repetitionPenalty（边界闭区间对齐 iOS `<`）

    @Test
    fun `repetitionPenalty 按间隔分档`() {
        assertEquals(0.0, NotificationTimePlanner.repetitionPenalty(null, 100 * hour), 1e-9)
        assertEquals(0.16, NotificationTimePlanner.repetitionPenalty(0L, 11 * hour), 1e-9)
        // 恰好 12h：非 <12h → 落入 <24h → 0.08
        assertEquals(0.08, NotificationTimePlanner.repetitionPenalty(0L, 12 * hour), 1e-9)
        assertEquals(0.08, NotificationTimePlanner.repetitionPenalty(0L, 13 * hour), 1e-9)
        // 恰好 24h：非 <24h → 0
        assertEquals(0.0, NotificationTimePlanner.repetitionPenalty(0L, 24 * hour), 1e-9)
        assertEquals(0.0, NotificationTimePlanner.repetitionPenalty(0L, 25 * hour), 1e-9)
    }

    // MARK: - selectResponseCandidate（2h 反馈窗内取最近）

    private fun rec(scheduledAt: Long) = NotificationDeliveryRecordEntity(
        characterId = "c",
        category = "streak_remind",
        requestIdentifier = "r",
        conversationUuid = "conv",
        notificationBody = "b",
        windowId = "w",
        windowStartMinute = 0,
        windowEndMinute = 30,
        scheduledAt = scheduledAt,
    )

    @Test
    fun `响应候选取 2h 窗内最近一条`() {
        val responseAt = 100L * hour
        val tooOld = rec(responseAt - 3 * hour)   // 超 2h 窗，排除
        val oneHour = rec(responseAt - 1 * hour)  // 窗内
        val recent = rec(responseAt - 30 * 60 * 1000) // 窗内、最近 → 期望
        val future = rec(responseAt + 10 * 60 * 1000) // 未来，排除
        val picked = NotificationLearningService.selectResponseCandidate(
            listOf(tooOld, oneHour, recent, future), responseAt,
        )
        assertEquals(recent.scheduledAt, picked?.scheduledAt)
    }

    @Test
    fun `响应候选边界恰好 2h 仍计入`() {
        val responseAt = 100L * hour
        val exactlyTwoHours = rec(responseAt - 2 * hour) // responseAt - scheduledAt == 2h，<= 窗 → 计入
        val picked = NotificationLearningService.selectResponseCandidate(listOf(exactlyTwoHours), responseAt)
        assertEquals(exactlyTwoHours.scheduledAt, picked?.scheduledAt)
    }

    @Test
    fun `响应候选全在窗外返回 null`() {
        val responseAt = 100L * hour
        val tooOld = rec(responseAt - 5 * hour)
        val future = rec(responseAt + 1 * hour)
        assertNull(NotificationLearningService.selectResponseCandidate(listOf(tooOld, future), responseAt))
        assertNull(NotificationLearningService.selectResponseCandidate(emptyList(), responseAt))
    }

    // MARK: - cancelScheduled 仅撤未投递（deliveredAt==null），防误杀已发出待反馈台账

    @Test
    fun `cancel 仅命中未投递且 id 匹配的台账`() {
        val set = setOf("k1")
        val pending = rec(0L).copy(requestIdentifier = "k1", deliveredAt = null) // 未发出 → 应撤
        val delivered = rec(0L).copy(requestIdentifier = "k1", deliveredAt = 123L) // 已发出、等反馈 → 不撤
        val otherKey = rec(0L).copy(requestIdentifier = "k2", deliveredAt = null) // id 不匹配 → 不撤
        assertTrue(NotificationLearningService.shouldCancel(pending, set))
        assertFalse(NotificationLearningService.shouldCancel(delivered, set))
        assertFalse(NotificationLearningService.shouldCancel(otherKey, set))
    }
}
