package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 见面时间线注记纯函数 T1（记忆改造二期·部件④·图纸 §3.1·T1-1/T1-2）。断言从锁定规格独立反推（行格式在测试里
 * 重新打字成字面量、weekday 独立计算），非照搬实现。UTC zone + Instant.parse 锚点保确定性。
 */
class MeetingTimelineAnnotationTest {

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-07-10T12:00:00Z") // 2026-07-10 = 周五

    private fun row(
        started: Long,
        location: String = "",
        activity: String = "",
        kind: String = "meeting",
    ) = OfflineMeetingMemoryEntity(
        uuid = "mm$started",
        characterUuid = "c1",
        kindRaw = kind,
        startedAtMillis = started,
        location = location,
        activity = activity,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ── T1-1 行格式逐字节（含 location 空→某地 / activity 空省略 / formatLabel 三档·E13）──

    @Test fun `lineFor full location and activity past day`() {
        // 往日档：M月D日 周X（2026-07-03 = 周五，独立计算）。
        val line = MeetingTimelineAnnotation.lineFor(row(at("2026-07-03T19:20:00Z"), "江边", "散步"), now, zone)
        assertEquals("【时间 · 7月3日 周五 19:20 · 这中间你们线下见了一面：江边，散步】", line)
    }

    @Test fun `lineFor empty location falls back to 某地 today`() {
        // 今天档 + location 空 → 某地。
        val line = MeetingTimelineAnnotation.lineFor(row(at("2026-07-10T09:05:00Z"), "", "散步"), now, zone)
        assertEquals("【时间 · 今天 09:05 · 这中间你们线下见了一面：某地，散步】", line)
    }

    @Test fun `lineFor empty activity omits comma yesterday`() {
        // 昨天档 + activity 空 → 无「，activity」后缀。
        val line = MeetingTimelineAnnotation.lineFor(row(at("2026-07-09T19:20:00Z"), "公园", ""), now, zone)
        assertEquals("【时间 · 昨天 19:20 · 这中间你们线下见了一面：公园】", line)
    }

    @Test fun `lineFor both empty gives 某地 and no activity`() {
        val line = MeetingTimelineAnnotation.lineFor(row(at("2026-07-10T23:00:00Z"), "", ""), now, zone)
        assertEquals("【时间 · 今天 23:00 · 这中间你们线下见了一面：某地】", line)
    }

    // ── T1-2 跨度筛选（窗外/legacy/边界等值排除）+ 超 5 取最新 5（E3）──

    @Test fun `selectEligible filters by kind window and strict boundaries`() {
        val rows = listOf(
            row(started = 500),                    // < firstTs → 出
            row(started = 1000),                   // == firstTs → 出（开区间·严格）
            row(started = 2000),                   // 窗内 → 留
            row(started = 5000, kind = "legacy"),  // kind != meeting → 出
            row(started = 8000),                   // 窗内 → 留
            row(started = 10000),                  // == lastTs → 出（开区间·严格）
            row(started = 12000),                  // > lastTs → 出
            row(started = 0, kind = "legacy"),     // legacy startedAt=0 → 出
        )
        val eligible = MeetingTimelineAnnotation.selectEligible(rows, firstTs = 1000, lastTs = 10000)
        assertEquals(listOf(2000L, 8000L), eligible.map { it.startedAtMillis })
    }

    @Test fun `selectEligible keeps newest 5 ascending when over cap`() {
        // 7 场都在窗口内 → 只留最新 5（takeLast·startedAt 升序）；乱序输入也先排序。
        val rows = listOf(7000L, 1000L, 4000L, 2000L, 6000L, 3000L, 5000L).map { row(started = it) }
        val eligible = MeetingTimelineAnnotation.selectEligible(rows, firstTs = 0, lastTs = 100_000)
        assertEquals(listOf(3000L, 4000L, 5000L, 6000L, 7000L), eligible.map { it.startedAtMillis })
    }

    @Test fun `selectEligible empty when no meeting rows in window`() {
        val rows = listOf(row(started = 0, kind = "legacy"), row(started = 500))
        assertEquals(emptyList<Long>(), MeetingTimelineAnnotation.selectEligible(rows, firstTs = 1000, lastTs = 10000).map { it.startedAtMillis })
    }
}
