package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定成长分析「最近一周日常活动模式」补充材料（断言反推 iOS `GrowthAnalysisService.swift:299-361`）：
 * 滤 userInteraction / Top5（次数降序+同次数名升序）/ 近 2 天新活动 / byte-exact 文案。
 * 回归方向：安卓此前 scheduleAnalysis 恒空串，成长分析 prompt 永远不含日程材料。
 */
class BuildScheduleAnalysisTest {

    private val now = 1_700_000_000_000L
    private val oneDay = 86_400_000L
    private val fiveDaysAgo = now - 5 * oneDay // < now-2d → 「旧」
    private val oneDayAgo = now - 1 * oneDay   // >= now-2d → 「新」

    private fun ev(activity: String, startTime: Long, type: String = "planned") = ScheduleEventEntity(
        uuid = "e-$activity-$startTime-$type",
        scheduleUuid = "s1",
        startTime = startTime,
        endTime = startTime + 3_600_000L,
        activity = activity,
        eventTypeRaw = type,
    )

    @Test fun `无事件返回空串`() = assertEquals("", buildScheduleAnalysis(emptyList(), now))

    @Test fun `只有 userInteraction 事件返回空串——被过滤后无可用事件`() {
        val events = listOf(ev("和用户聊天", fiveDaysAgo, "userInteraction"), ev("和用户见面", oneDayAgo, "userInteraction"))
        assertEquals("", buildScheduleAnalysis(events, now))
    }

    @Test fun `完整格式——Top5 次数降序 + 近 2 天新活动 + byte-exact 文案`() {
        val events = listOf(
            ev("看书", fiveDaysAgo), ev("看书", fiveDaysAgo), ev("看书", fiveDaysAgo), // 旧×3
            ev("健身", fiveDaysAgo), ev("健身", oneDayAgo),                              // 旧1+新1
            ev("画画", oneDayAgo),                                                       // 新×1
        )
        // 看书(3) > 健身(2) > 画画(1)；新活动 = 近2天有但更早没有 = {画画}
        val expected = "\n【最近一周的日常活动模式】\n- 看书（3次）\n- 健身（2次）\n- 画画（1次）\n" +
            "最近新出现的活动：画画\n请参考这些活动模式来分析角色的兴趣变化和成长趋势。"
        assertEquals(expected, buildScheduleAnalysis(events, now))
    }

    @Test fun `同次数按活动名升序——游泳(0x6E38) 在 跑步(0x8DD1) 前`() {
        val events = listOf(
            ev("跑步", fiveDaysAgo), ev("跑步", fiveDaysAgo),
            ev("游泳", fiveDaysAgo), ev("游泳", fiveDaysAgo),
            ev("做饭", fiveDaysAgo),
        )
        val result = buildScheduleAnalysis(events, now)
        // 跑步/游泳 同为 2 次 → 名升序：游泳 先于 跑步（1:1 iOS key 升序）
        val 游泳Idx = result.indexOf("- 游泳（2次）")
        val 跑步Idx = result.indexOf("- 跑步（2次）")
        assertTrue(游泳Idx in 0 until 跑步Idx)
        assertTrue(result.contains("- 做饭（1次）"))
        // 全旧 → 无「最近新出现的活动」行
        assertFalse(result.contains("最近新出现的活动"))
    }

    @Test fun `最多 5 个活动`() {
        val events = (1..7).map { ev("活动$it", fiveDaysAgo) } // 7 个各 1 次
        val result = buildScheduleAnalysis(events, now)
        assertEquals(5, Regex("- 活动").findAll(result).count())
    }

    @Test fun `userInteraction 事件不计入活动统计`() {
        val events = listOf(
            ev("看书", fiveDaysAgo),
            ev("和用户散步", fiveDaysAgo, "userInteraction"), // 不应出现
        )
        val result = buildScheduleAnalysis(events, now)
        assertTrue(result.contains("- 看书（1次）"))
        assertFalse(result.contains("和用户散步"))
    }
}
