package com.situ.aichat.ui.meeting

import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.meeting.MeetingTimeResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 表单时段计算纯函数测（8c）：开关 → 精度 + 落库毫秒。断言从规格独立反推
 * （exact = 用所选时刻；dayOnly = 补 19 点；毫秒 = 该 zone 当地墙钟 → epoch），不照搬实现。
 */
class FutureMeetingFormScheduleTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 6, 27) // 周六

    /** 当地墙钟 → epoch millis（独立反推用）。 */
    private fun wall(zone: ZoneId, h: Int, m: Int): Long =
        date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun exactTime_usesChosenClock_andExactGranularity() {
        val (millis, gran) = resolveFormSchedule(date, useExactTime = true, time = LocalTime.of(15, 30), zone = shanghai)
        assertEquals(MeetingTimeGranularity.EXACT, gran)
        assertEquals(wall(shanghai, 15, 30), millis)
    }

    @Test fun dayOnly_ignoresChosenClock_fillsDefaultEveningHour() {
        // 关「具体时间」：所选 15:30 应被忽略，补默认时段（19 点）。
        val (millis, gran) = resolveFormSchedule(date, useExactTime = false, time = LocalTime.of(15, 30), zone = shanghai)
        assertEquals(MeetingTimeGranularity.DAY_ONLY, gran)
        assertEquals(wall(shanghai, MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR, 0), millis)
        assertEquals(wall(shanghai, 19, 0), millis) // 钉死默认时段=19 点（规格值）
    }

    @Test fun millis_respectsZone_sameWallClockDiffersAcrossZones() {
        val tokyo = ZoneId.of("Asia/Tokyo") // UTC+9，比上海(UTC+8)早 1h → 同墙钟 epoch 小 1h
        val (cn, _) = resolveFormSchedule(date, useExactTime = true, time = LocalTime.of(9, 0), zone = shanghai)
        val (jp, _) = resolveFormSchedule(date, useExactTime = true, time = LocalTime.of(9, 0), zone = tokyo)
        assertEquals(60 * 60 * 1000L, cn - jp)
    }

    @Test fun midnight_dayOnly_toggleOffStillEvening() {
        // 边界：所选 00:00 但关具体时间 → 仍补 19 点（不被 00:00 带偏）。
        val (millis, gran) = resolveFormSchedule(date, useExactTime = false, time = LocalTime.MIDNIGHT, zone = shanghai)
        assertEquals(MeetingTimeGranularity.DAY_ONLY, gran)
        assertEquals(wall(shanghai, 19, 0), millis)
    }
}
