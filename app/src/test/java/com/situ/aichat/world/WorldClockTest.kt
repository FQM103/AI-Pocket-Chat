package com.situ.aichat.world

import com.situ.aichat.world.WorldClock.DayPhase
import com.situ.aichat.world.WorldClock.Season
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [WorldClock] T1 纯函数测试（W2 图纸 §7 T1-1/T1-2/T1-3·断言从图纸 §3.1/§5 独立反推）。
 *
 * 相位/季节边界值全部照图纸 §3.1 锁死值验（E9）：改一位边界即红。时区用固定 UTC / Asia/Shanghai
 * 避开 DST 随机性——测试确定性、可重复。
 */
class WorldClockTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 在 [zone] 用 [date]（默认盛夏无 DST 的一天）+ 时分秒组一个 epoch 毫秒。 */
    private fun ms(
        h: Int,
        m: Int,
        s: Int = 0,
        date: LocalDate = LocalDate.of(2026, 6, 15),
        zone: ZoneId = utc,
    ): Long = LocalDateTime.of(date, java.time.LocalTime.of(h, m, s))
        .atZone(zone).toInstant().toEpochMilli()

    // ---- T1-1：相位全部边界值（E9·含 19:30 半点边界） ----

    @Test
    fun `T1-1 相位边界值全部锁死`() {
        assertEquals(DayPhase.NIGHT, WorldClock.phaseAt(ms(4, 59), utc))   // [00:00,05:00)
        assertEquals(DayPhase.DAWN, WorldClock.phaseAt(ms(5, 0), utc))     // 05:00 = DAWN
        assertEquals(DayPhase.DAWN, WorldClock.phaseAt(ms(6, 59), utc))    // 06:59 = DAWN
        assertEquals(DayPhase.DAY, WorldClock.phaseAt(ms(7, 0), utc))      // 07:00 = DAY
        assertEquals(DayPhase.DAY, WorldClock.phaseAt(ms(16, 59), utc))    // [07:00,17:00)
        assertEquals(DayPhase.DUSK, WorldClock.phaseAt(ms(17, 0), utc))    // 17:00 = DUSK
        assertEquals(DayPhase.DUSK, WorldClock.phaseAt(ms(19, 29), utc))   // 19:29 = DUSK
        assertEquals(DayPhase.NIGHT, WorldClock.phaseAt(ms(19, 30), utc))  // 19:30 = NIGHT
        assertEquals(DayPhase.NIGHT, WorldClock.phaseAt(ms(0, 0), utc))    // 午夜 = NIGHT
        assertEquals(DayPhase.NIGHT, WorldClock.phaseAt(ms(23, 59), utc))  // 深夜 = NIGHT
    }

    // ---- T1-2：季节四界 + dayProgress + dayIndexSince 跨月 ----

    @Test
    fun `T1-2 季节月份映射四界`() {
        fun season(month: Int) =
            WorldClock.seasonAt(ms(12, 0, date = LocalDate.of(2026, month, 15)), utc)
        // 图纸 §9 锁死边界月：
        assertEquals(Season.WINTER, season(2))   // 2月 = WINTER
        assertEquals(Season.SPRING, season(3))   // 3月 = SPRING
        assertEquals(Season.AUTUMN, season(11))  // 11月 = AUTUMN
        assertEquals(Season.WINTER, season(12))  // 12月 = WINTER
        // 四季各取一代表月：
        assertEquals(Season.SPRING, season(4))
        assertEquals(Season.SUMMER, season(7))
        assertEquals(Season.AUTUMN, season(10))
        assertEquals(Season.WINTER, season(1))
    }

    @Test
    fun `T1-2 dayProgress 午夜0f 正午0点5f`() {
        assertEquals(0f, WorldClock.dayProgress(ms(0, 0), utc), 1e-6f)
        assertEquals(0.5f, WorldClock.dayProgress(ms(12, 0), utc), 1e-6f)
        assertEquals(0.25f, WorldClock.dayProgress(ms(6, 0), utc), 1e-6f)
    }

    @Test
    fun `T1-2 dayIndexSince 跨月`() {
        val origin = ms(12, 0, date = LocalDate.of(2026, 1, 1))
        assertEquals(0L, WorldClock.dayIndexSince(origin, ms(20, 0, date = LocalDate.of(2026, 1, 1)), utc))
        assertEquals(1L, WorldClock.dayIndexSince(origin, ms(12, 0, date = LocalDate.of(2026, 1, 2)), utc))
        // 跨月：Jan(31) + Feb(28·2026 非闰) = 59 天到 3/1。
        assertEquals(59L, WorldClock.dayIndexSince(origin, ms(12, 0, date = LocalDate.of(2026, 3, 1)), utc))
        // 月末跨界：1/31 → 2/1 = 1 天。
        val janEnd = ms(12, 0, date = LocalDate.of(2026, 1, 31))
        assertEquals(1L, WorldClock.dayIndexSince(janEnd, ms(12, 0, date = LocalDate.of(2026, 2, 1)), utc))
    }

    // ---- W5 T1-1：resolveZone 三分支（🔵-1 上收·E3） ----

    @Test
    fun `W5 resolveZone_null与非法串回退systemDefault_合法串对应Zone`() {
        // null → systemDefault（未设时区·W13 首启前）
        assertEquals(ZoneId.systemDefault(), WorldClock.resolveZone(null))
        // 非法串 → systemDefault（不崩·图纸 §3.4 step1 / E8）
        assertEquals(ZoneId.systemDefault(), WorldClock.resolveZone("Not/AZone"))
        assertEquals(ZoneId.systemDefault(), WorldClock.resolveZone(""))
        // 合法串 → 对应 Zone（ZoneId.of("UTC") 是 ZoneRegion，非 ZoneOffset.UTC，故显式比 of("UTC")）
        assertEquals(shanghai, WorldClock.resolveZone("Asia/Shanghai"))
        assertEquals(ZoneId.of("UTC"), WorldClock.resolveZone("UTC"))
    }

    // ---- T1-3：同一 epochMs 在 Asia/Shanghai vs UTC 落不同 LocalDate ----

    @Test
    fun `T1-3 时区日切_同一瞬间两时区不同本地日`() {
        // UTC 2026-03-14 20:00 == Asia/Shanghai 2026-03-15 04:00（+8 时区跨了午夜）。
        val instantMs = LocalDateTime.of(2026, 3, 14, 20, 0)
            .atZone(utc).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 3, 14), WorldClock.localDateOf(instantMs, utc))
        assertEquals(LocalDate.of(2026, 3, 15), WorldClock.localDateOf(instantMs, shanghai))
    }
}
