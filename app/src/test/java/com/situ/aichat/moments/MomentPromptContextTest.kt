package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Parity with iOS `buildNowContextPrompt(for:)` / `buildSchedulePrompt(for:context:)`
 * (Services/MomentGenerationService+ContentGeneration.swift). UTC zone makes the hour deterministic;
 * `hour = millis / 3_600_000` within 1970-01-01.
 */
class MomentPromptContextTest {

    private val utc = ZoneId.of("UTC")
    private fun atHour(hour: Int, minute: Int = 0): Long = (hour * 3600L + minute * 60L) * 1000L

    // ---- now-context time buckets ----

    @Test fun `now-context period buckets match iOS ranges`() {
        // 5..<9 清晨 / 9..<12 上午 / 12..<14 中午 / 14..<18 下午 / 18..<22 晚上 / else 深夜
        val cases = mapOf(
            5 to "清晨", 8 to "清晨", 9 to "上午", 11 to "上午", 12 to "中午", 13 to "中午",
            14 to "下午", 17 to "下午", 18 to "晚上", 21 to "晚上", 22 to "深夜", 4 to "深夜",
            23 to "深夜", 0 to "深夜",
        )
        for ((hour, label) in cases) {
            val out = MomentPromptContext.buildNowContext(MomentPromptContext.NowScenario.POST, atHour(hour), utc)
            assertTrue("hour $hour should be $label", out.contains("（$label）"))
        }
    }

    @Test fun `post now-context uses strict header, comment uses soft header`() {
        val post = MomentPromptContext.buildNowContext(MomentPromptContext.NowScenario.POST, atHour(10), utc)
        assertTrue(post.startsWith("【此刻的时间】"))
        assertTrue(post.contains("不要写出与当前时间明显矛盾的场景"))

        val comment = MomentPromptContext.buildNowContext(MomentPromptContext.NowScenario.COMMENT, atHour(10), utc)
        assertTrue(comment.startsWith("【此刻的时间参考】"))
        assertTrue(comment.contains("评论专注回应帖子内容即可"))
    }

    // ---- schedule prompt text ----

    private fun ev(
        start: Int, end: Int, activity: String,
        location: String = "", moodText: String? = null, periodLabel: String = "",
        companions: String? = null, source: String = "generated", sortOrder: Int = 0,
        type: String = "planned",
    ) = ScheduleEventEntity(
        uuid = "e$start", scheduleUuid = "s", startTime = atHour(start), endTime = atHour(end),
        periodLabel = periodLabel, location = location, activity = activity, moodText = moodText,
        relatedCharacterNames = companions, sourceRaw = source, sortOrder = sortOrder, eventTypeRaw = type,
    )

    @Test fun `empty or userInteraction-only events yield empty schedule prompt`() {
        assertEquals("", MomentPromptContext.buildSchedulePromptText(emptyList(), atHour(12), utc, "小樱"))
        val onlyUI = listOf(ev(9, 10, "聊天", type = "userInteraction"))
        assertEquals("", MomentPromptContext.buildSchedulePromptText(onlyUI, atHour(12), utc, "小樱"))
    }

    @Test fun `current, next and interesting past event assemble with hard constraints`() {
        val events = listOf(
            ev(9, 10, "晨跑", location = "公园", periodLabel = "清晨", sortOrder = 0),     // past, outdoor → interesting
            ev(11, 13, "开会", location = "公司", moodText = "专注", sortOrder = 1),         // current (now=12)
            ev(14, 15, "健身", sortOrder = 2),                                               // future
        )
        val out = MomentPromptContext.buildSchedulePromptText(events, atHour(12), utc, "小樱")
        assertTrue(out.contains("【当前状态】小樱正在：开会（在公司），心情：专注"))
        assertTrue(out.contains("接下来要：健身"))
        assertTrue(out.contains("今天小樱做了这些事，可以作为朋友圈素材："))
        assertTrue(out.contains("- 清晨 晨跑（在公园）"))
        assertTrue(out.contains("【重要约束】你发的朋友圈必须与你当前的日程状态一致："))
    }

    @Test fun `companion past event is interesting and shows the companion`() {
        val events = listOf(ev(9, 10, "喝咖啡", location = "家里", companions = "小明", sortOrder = 0))
        val out = MomentPromptContext.buildSchedulePromptText(events, atHour(12), utc, "小樱")
        // location 家里 (not outdoor) but has a companion → still interesting.
        assertTrue(out.contains("，和小明一起"))
    }

    @Test fun `boring at-home past events fall back to the last two`() {
        val events = listOf(
            ev(8, 9, "在家A", location = "家里", periodLabel = "清晨", sortOrder = 0),
            ev(10, 11, "在家B", location = "家", periodLabel = "上午", sortOrder = 1),
            ev(12, 13, "在家C", location = "家里", periodLabel = "中午", sortOrder = 2),
            ev(14, 15, "在家D", location = "家", periodLabel = "下午", sortOrder = 3),
        )
        // now=20:00 → all past, none interesting (home, no companion, generated) → takeLast(2) = C, D.
        val out = MomentPromptContext.buildSchedulePromptText(events, atHour(20), utc, "小樱")
        assertFalse(out.contains("在家A"))
        assertFalse(out.contains("在家B"))
        assertTrue(out.contains("- 中午 在家C"))
        assertTrue(out.contains("- 下午 在家D"))
    }
}
