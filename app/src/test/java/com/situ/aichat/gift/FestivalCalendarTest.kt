package com.situ.aichat.gift

import android.icu.util.ChineseCalendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * 节日日历单测。公历固定 + 第 N 周日走 GregorianCalendar；**农历走 Robolectric 提供的真实 `android.icu.ChineseCalendar`**
 * （= iOS `Calendar(.chinese)` 同 ICU）。农历断言双保险：① 自洽——用 ICU 定位某农历月/日的公历日，断言 matcher 命中
 * （抓 0-based month→+1 偏移 bug）；② 跨端锚点——断言 ICU 农历与**公认 2026 公历日期**一致（春节 2/17、中秋 9/25）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FestivalCalendarTest {

    private val shanghai: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private var original: TimeZone = TimeZone.getDefault()

    @Before fun setUp() {
        original = TimeZone.getDefault()
        TimeZone.setDefault(shanghai)
    }

    @After fun tearDown() {
        TimeZone.setDefault(original)
    }

    /** 公历日期（month 1-based）正午上海，避开午夜时区边界。 */
    private fun gdate(year: Int, month: Int, day: Int): Long =
        GregorianCalendar(shanghai).apply { clear(); set(year, month - 1, day, 12, 0, 0) }.timeInMillis

    private fun fest(id: String): Festival = FestivalCalendar.festivalById(id)!!

    /** 扫一年找出 ICU 农历某月(0-based)/日的公历日（首次出现=该年正历，闰月在后）。 */
    private fun findLunar(year: Int, icuMonth0: Int, day: Int): Long {
        val cc = ChineseCalendar()   // 默认时区 = 上海（@Before setDefault），= 生产 matches() 的无参构造

        val scan = GregorianCalendar(shanghai).apply { clear(); set(year, Calendar.JANUARY, 1, 12, 0, 0) }
        repeat(400) {
            cc.timeInMillis = scan.timeInMillis
            if (cc.get(Calendar.MONTH) == icuMonth0 && cc.get(Calendar.DAY_OF_MONTH) == day) return scan.timeInMillis
            scan.add(Calendar.DAY_OF_MONTH, 1)
        }
        return -1L
    }

    @Test fun catalog_has_16_unique() {
        assertEquals(16, FestivalCalendar.allFestivals.size)
        assertEquals(16, FestivalCalendar.allFestivals.map { it.id }.distinct().size)
    }

    @Test fun gregorian_fixed() {
        assertTrue(fest("new_year").matches(gdate(2026, 1, 1)))
        assertTrue(fest("valentines_day").matches(gdate(2026, 2, 14)))
        assertFalse(fest("valentines_day").matches(gdate(2026, 2, 15)))
        assertTrue(fest("confession_day").matches(gdate(2026, 5, 20)))
        assertTrue(fest("christmas").matches(gdate(2026, 12, 25)))
        assertTrue(fest("new_year_eve").matches(gdate(2026, 12, 31)))
    }

    @Test fun gregorian_nth_weekday() {
        // 母亲节 = 5 月第 2 个周日（用字段构造，避免手算具体日期）
        val secondSunMay = GregorianCalendar(shanghai).apply {
            clear(); set(Calendar.YEAR, 2026); set(Calendar.MONTH, Calendar.MAY)
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY); set(Calendar.DAY_OF_WEEK_IN_MONTH, 2)
            set(Calendar.HOUR_OF_DAY, 12)
        }.timeInMillis
        assertTrue(fest("mothers_day").matches(secondSunMay))
        // 第 1 个周日不命中（ordinal 1≠2）
        val firstSunMay = GregorianCalendar(shanghai).apply {
            clear(); set(Calendar.YEAR, 2026); set(Calendar.MONTH, Calendar.MAY)
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY); set(Calendar.DAY_OF_WEEK_IN_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
        }.timeInMillis
        assertFalse(fest("mothers_day").matches(firstSunMay))
        // 父亲节 = 6 月第 3 个周日
        val thirdSunJune = GregorianCalendar(shanghai).apply {
            clear(); set(Calendar.YEAR, 2026); set(Calendar.MONTH, Calendar.JUNE)
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY); set(Calendar.DAY_OF_WEEK_IN_MONTH, 3)
            set(Calendar.HOUR_OF_DAY, 12)
        }.timeInMillis
        assertTrue(fest("fathers_day").matches(thirdSunJune))
    }

    @Test fun lunar_offset_self_consistent() {
        // ICU 农历月 0-based：正月=0、五月=4、七月=6、八月=7。matcher 须 +1 对齐 iOS 1-based。
        assertTrue(fest("chinese_new_year").matches(findLunar(2026, 0, 1)))    // 正月初一
        assertTrue(fest("lantern_festival").matches(findLunar(2026, 0, 15)))   // 正月十五
        assertTrue(fest("dragon_boat").matches(findLunar(2026, 4, 5)))         // 五月初五
        assertTrue(fest("qixi").matches(findLunar(2026, 6, 7)))                // 七月初七
        assertTrue(fest("mid_autumn").matches(findLunar(2026, 7, 15)))         // 八月十五
    }

    @Test fun lunar_cross_platform_anchors() {
        // ICU 农历 == 公认 2026 公历日期（= iOS ICU）：春节 2/17、中秋 9/25
        assertEquals(gdate(2026, 2, 17), findLunar(2026, 0, 1))
        assertEquals(gdate(2026, 9, 25), findLunar(2026, 7, 15))
        // 非节日不误报
        assertFalse(fest("chinese_new_year").matches(gdate(2026, 2, 18)))
        assertTrue(FestivalCalendar.festivalsMatching(gdate(2026, 2, 17)).any { it.id == "chinese_new_year" })
        assertTrue(FestivalCalendar.festivalsMatching(gdate(2026, 7, 1)).isEmpty())
    }
}
