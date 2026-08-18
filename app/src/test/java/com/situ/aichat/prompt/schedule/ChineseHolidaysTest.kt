package com.situ.aichat.prompt.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * T1-1（图纸 2026-07-10 日程专项 §7·E1/E2/E3）：2026 官方节假日表逐日打点。
 * 期望值从国办发明电〔2025〕7号原文独立反推（放假区间首尾 ±1 天 + 6 个补班日全点名 + 表外回退）。
 */
class ChineseHolidaysTest {

    private fun holiday(y: Int, m: Int, d: Int): String? =
        (ChineseHolidays.dayInfoFor(LocalDate.of(y, m, d)) as? ChineseHolidays.DayInfo.Holiday)?.name

    private fun isMakeup(y: Int, m: Int, d: Int): Boolean =
        ChineseHolidays.dayInfoFor(LocalDate.of(y, m, d)) == ChineseHolidays.DayInfo.MakeupWorkday

    @Test
    fun `元旦区间与边界`() {
        assertEquals("元旦", holiday(2026, 1, 1))
        assertEquals("元旦", holiday(2026, 1, 3))
        // 1月4日是调休补班（周日上班），不是假期也不是普通日
        assertTrue2(isMakeup(2026, 1, 4))
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 1, 5))) // 周一普通工作日
    }

    @Test
    fun `春节九连休与两个补班周六`() {
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 2, 13))) // 假期前一日（周五）普通日
        assertTrue2(isMakeup(2026, 2, 14)) // 补班周六
        assertEquals("春节", holiday(2026, 2, 15))
        assertEquals("春节", holiday(2026, 2, 23))
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 2, 24))) // 假期次日普通日
        assertTrue2(isMakeup(2026, 2, 28)) // 补班周六
    }

    @Test
    fun `清明不调休`() {
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 4, 3)))
        assertEquals("清明节", holiday(2026, 4, 4))
        assertEquals("清明节", holiday(2026, 4, 6))
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 4, 7)))
    }

    @Test
    fun `劳动节五天与补班`() {
        assertEquals("劳动节", holiday(2026, 5, 1))
        assertEquals("劳动节", holiday(2026, 5, 5))
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 5, 6)))
        assertTrue2(isMakeup(2026, 5, 9))
    }

    @Test
    fun `端午不调休`() {
        assertEquals("端午节", holiday(2026, 6, 19))
        assertEquals("端午节", holiday(2026, 6, 21))
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 6, 22)))
    }

    @Test
    fun `中秋不调休`() {
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 9, 24)))
        assertEquals("中秋节", holiday(2026, 9, 25))
        assertEquals("中秋节", holiday(2026, 9, 27)) // 周日=假期身份优先于周末
    }

    @Test
    fun `国庆七天与九月提前补班`() {
        assertTrue2(isMakeup(2026, 9, 20)) // 补班在假期前 11 天（周日）
        assertEquals("国庆节", holiday(2026, 10, 1))
        assertEquals("国庆节", holiday(2026, 10, 7))
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 10, 8)))
        assertTrue2(isMakeup(2026, 10, 10))
    }

    @Test
    fun `表外日期回退null_E1`() {
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2025, 12, 31))) // 覆盖起点前一日
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2027, 1, 1)))   // 覆盖终点后一日（元旦也不认——未公布年份不预填）
        assertNull(ChineseHolidays.dayInfoFor(LocalDate.of(2026, 7, 10)))  // 今天：普通周五
    }

    private fun assertTrue2(actual: Boolean) = assertEquals(true, actual)
}
