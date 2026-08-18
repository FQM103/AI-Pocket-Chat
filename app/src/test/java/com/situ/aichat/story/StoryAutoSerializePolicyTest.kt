package com.situ.aichat.story

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * `StoryAutoSerializePolicy`（11.1g-2）测试，断言反推 iOS `StoryScheduleService.shouldRunNow` +
 * `StoryScheduleActor.checkAndGenerateStories` 跳过判定：10min 防抖、free 跳过、今日已更跳过、本地同日。
 */
class StoryAutoSerializePolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = 24 * 60 * 60 * 1000L

    // ── 防抖（iOS minimumCheckInterval = 600s）──

    @Test fun debounce_first_run_allowed() {
        assertTrue(StoryAutoSerializePolicy.isDebounceElapsed(lastCheckAtMillis = 0L, nowMillis = 1_000_000L))
    }

    @Test fun debounce_blocks_within_ten_minutes() {
        val now = 100_000_000L
        assertFalse(StoryAutoSerializePolicy.isDebounceElapsed(lastCheckAtMillis = now - 5 * 60 * 1000L, nowMillis = now))
    }

    @Test fun debounce_allows_after_ten_minutes() {
        val now = 100_000_000L
        // 恰好 10 分钟 → 放行（iOS `< interval` 才拦，等于不拦）
        assertTrue(StoryAutoSerializePolicy.isDebounceElapsed(lastCheckAtMillis = now - 10 * 60 * 1000L, nowMillis = now))
        assertTrue(StoryAutoSerializePolicy.isDebounceElapsed(lastCheckAtMillis = now - 11 * 60 * 1000L, nowMillis = now))
    }

    // ── 每故事跳过判定 ──

    @Test fun free_mode_never_auto_generates() {
        assertFalse(
            StoryAutoSerializePolicy.shouldAutoGenerate(
                updateMode = StoryUpdateMode.FREE,
                latestChapterCreatedAt = null,
                nowMillis = 1_700_000_000_000L,
                zoneId = zone,
            ),
        )
    }

    @Test fun chase_never_generated_generates() {
        assertTrue(
            StoryAutoSerializePolicy.shouldAutoGenerate(
                updateMode = StoryUpdateMode.CHASE,
                latestChapterCreatedAt = null,
                nowMillis = 1_700_000_000_000L,
                zoneId = zone,
            ),
        )
    }

    @Test fun chase_generated_today_skips() {
        val now = 1_700_000_000_000L
        // 同一本地日早些时候已生成 → 跳过
        assertFalse(
            StoryAutoSerializePolicy.shouldAutoGenerate(
                updateMode = StoryUpdateMode.CHASE,
                latestChapterCreatedAt = now - 60 * 60 * 1000L,
                nowMillis = now,
                zoneId = zone,
            ),
        )
    }

    @Test fun chase_generated_yesterday_generates() {
        val now = 1_700_000_000_000L
        assertTrue(
            StoryAutoSerializePolicy.shouldAutoGenerate(
                updateMode = StoryUpdateMode.CHASE,
                latestChapterCreatedAt = now - day - 60 * 60 * 1000L,
                nowMillis = now,
                zoneId = zone,
            ),
        )
    }

    @Test fun same_local_day_respects_timezone() {
        // 一个 UTC 时间戳在上海与 UTC 可能跨日；这里仅验同区内同日/跨日。
        val base = 1_700_000_000_000L
        assertTrue(StoryAutoSerializePolicy.isSameLocalDay(base, base + 60 * 1000L, zone))
        assertFalse(StoryAutoSerializePolicy.isSameLocalDay(base, base + day, zone))
    }
}
