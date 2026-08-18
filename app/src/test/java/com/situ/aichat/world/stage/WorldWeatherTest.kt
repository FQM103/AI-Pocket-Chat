package com.situ.aichat.world.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [WorldWeather] T1 纯函数测试（W9d 图纸 §7 T1-1·E1·断言从图纸 §4.1 独立反推）。
 *
 * 覆盖：确定性（同 seed/城/日恒同）· 季节月界（2冬/3春/5春/6夏/8夏/9秋/11秋/12冬）· 概率金标（分布反推）·
 * 昼夜界（6:59/7:00/18:59/19:00）· 词-emoji 表。概率用大样本分布验（独立于实现分支写法）。
 */
class WorldWeatherTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val seed = 0x1234_5678_9ABC_DEF0L

    private fun ms(h: Int, date: LocalDate = LocalDate.of(2026, 6, 15)): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(h, 0)).atZone(utc).toInstant().toEpochMilli()

    // ---- 确定性 ----

    @Test
    fun `E1 确定性_同seed城日恒同`() {
        val d = LocalDate.of(2026, 4, 15)
        val a = WorldWeather.kindOf(seed, "city_yunye", d)
        repeat(20) { assertEquals(a, WorldWeather.kindOf(seed, "city_yunye", d)) }
        // 不同城 / 不同日 = 独立抽样（不要求不同，但派生 salt 不同）——只钉「同参恒同」。
    }

    // ---- 季节 → 降水类型（冬=雪·非冬=雨·CLEAR 无降水） ----

    /** 在给定月扫描城，找出第一天「有降水」的结果类型。 */
    private fun firstPrecipKind(month: Int): WorldWeatherKind {
        val date = LocalDate.of(2026, month, 15)
        for (i in 0 until 10_000) {
            val k = WorldWeather.kindOf(seed, "city_$i", date)
            if (k != WorldWeatherKind.CLEAR) return k
        }
        error("month=$month 一万城无降水（概率上不可能）")
    }

    @Test
    fun `E1 季节界_降水类型`() {
        // 冬（2/12/1）降水 = 雪；非冬（3/5/6/8/9/11）降水 = 雨。
        assertEquals(WorldWeatherKind.SNOW, firstPrecipKind(2))   // 2 月 = 冬
        assertEquals(WorldWeatherKind.SNOW, firstPrecipKind(12))  // 12 月 = 冬
        assertEquals(WorldWeatherKind.SNOW, firstPrecipKind(1))   // 1 月 = 冬
        assertEquals(WorldWeatherKind.RAIN, firstPrecipKind(3))   // 3 月 = 春
        assertEquals(WorldWeatherKind.RAIN, firstPrecipKind(5))   // 5 月 = 春
        assertEquals(WorldWeatherKind.RAIN, firstPrecipKind(6))   // 6 月 = 夏
        assertEquals(WorldWeatherKind.RAIN, firstPrecipKind(8))   // 8 月 = 夏
        assertEquals(WorldWeatherKind.RAIN, firstPrecipKind(9))   // 9 月 = 秋
        assertEquals(WorldWeatherKind.RAIN, firstPrecipKind(11))  // 11 月 = 秋
    }

    @Test
    fun `E1 冬季无雨_春秋夏无雪`() {
        val winter = LocalDate.of(2026, 1, 15)
        val spring = LocalDate.of(2026, 4, 15)
        for (i in 0 until 3000) {
            assertTrue("冬季不应出现 RAIN", WorldWeather.kindOf(seed, "c$i", winter) != WorldWeatherKind.RAIN)
            assertTrue("春季不应出现 SNOW", WorldWeather.kindOf(seed, "c$i", spring) != WorldWeatherKind.SNOW)
        }
    }

    // ---- 概率金标（大样本分布·独立反推 §4.1 四值） ----

    /** 在 [month] 上跨 5000 城采样，返回「有降水」的比例。 */
    private fun precipFraction(month: Int): Double {
        val date = LocalDate.of(2026, month, 15)
        var hits = 0
        val n = 5000
        for (i in 0 until n) if (WorldWeather.kindOf(seed, "city_$i", date) != WorldWeatherKind.CLEAR) hits++
        return hits.toDouble() / n
    }

    @Test
    fun `E1 概率金标_四季分布`() {
        assertEquals(0.30, precipFraction(4), 0.03)   // 春 0.30
        assertEquals(0.25, precipFraction(7), 0.03)   // 夏 0.25
        assertEquals(0.20, precipFraction(10), 0.03)  // 秋 0.20
        assertEquals(0.25, precipFraction(1), 0.03)   // 冬 0.25（雪）
    }

    // ---- 昼夜界 ----

    @Test
    fun `E1 昼夜界_6_59夜_7_00昼_18_59昼_19_00夜`() {
        // hour < 7 || hour >= 19 → night
        assertTrue(WorldWeather.isNight(ms(6), utc))    // 06:xx = 夜
        assertTrue(!WorldWeather.isNight(ms(7), utc))   // 07:xx = 昼
        assertTrue(!WorldWeather.isNight(ms(12), utc))  // 正午 = 昼
        assertTrue(!WorldWeather.isNight(ms(18), utc))  // 18:xx = 昼
        assertTrue(WorldWeather.isNight(ms(19), utc))   // 19:xx = 夜
        assertTrue(WorldWeather.isNight(ms(0), utc))    // 午夜 = 夜
        assertTrue(WorldWeather.isNight(ms(23), utc))   // 深夜 = 夜
    }

    // ---- 词 / emoji 表 ----

    @Test
    fun `E1 词表六格`() {
        assertEquals("晴", WorldWeather.word(WorldWeatherKind.CLEAR, night = false))
        assertEquals("夜", WorldWeather.word(WorldWeatherKind.CLEAR, night = true))
        assertEquals("雨天", WorldWeather.word(WorldWeatherKind.RAIN, night = false))
        assertEquals("雨夜", WorldWeather.word(WorldWeatherKind.RAIN, night = true))
        assertEquals("雪天", WorldWeather.word(WorldWeatherKind.SNOW, night = false))
        assertEquals("雪夜", WorldWeather.word(WorldWeatherKind.SNOW, night = true))
    }

    @Test
    fun `E1 emoji表`() {
        assertEquals("☀️", WorldWeather.emoji(WorldWeatherKind.CLEAR, night = false))
        assertEquals("🌙", WorldWeather.emoji(WorldWeatherKind.CLEAR, night = true))
        assertEquals("🌧️", WorldWeather.emoji(WorldWeatherKind.RAIN, night = false))
        assertEquals("🌧️", WorldWeather.emoji(WorldWeatherKind.RAIN, night = true))
        assertEquals("❄️", WorldWeather.emoji(WorldWeatherKind.SNOW, night = false))
        assertEquals("❄️", WorldWeather.emoji(WorldWeatherKind.SNOW, night = true))
    }
}
