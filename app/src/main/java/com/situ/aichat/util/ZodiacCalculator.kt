package com.situ.aichat.util

import java.time.Instant
import java.time.ZoneId

/**
 * 星座计算（本地查表，不需网络）。1:1 iOS `Utilities/ZodiacCalculator.swift` 的日期区间。
 *
 * 纯函数（[zodiacSign] 的 month/day 重载无依赖、便于单测）；日期区间与 iOS 逐段对齐。
 */
object ZodiacCalculator {

    /** 按月/日查表返回「星座名 + 符号」，无匹配返回空串（1:1 iOS switch (month, day)）。 */
    fun zodiacSign(month: Int, day: Int): String = when {
        (month == 1 && day in 20..31) || (month == 2 && day in 1..18) -> "水瓶座 ♒"
        (month == 2 && day in 19..29) || (month == 3 && day in 1..20) -> "双鱼座 ♓"
        (month == 3 && day in 21..31) || (month == 4 && day in 1..19) -> "白羊座 ♈"
        (month == 4 && day in 20..30) || (month == 5 && day in 1..20) -> "金牛座 ♉"
        (month == 5 && day in 21..31) || (month == 6 && day in 1..21) -> "双子座 ♊"
        (month == 6 && day in 22..30) || (month == 7 && day in 1..22) -> "巨蟹座 ♋"
        (month == 7 && day in 23..31) || (month == 8 && day in 1..22) -> "狮子座 ♌"
        (month == 8 && day in 23..31) || (month == 9 && day in 1..22) -> "处女座 ♍"
        (month == 9 && day in 23..30) || (month == 10 && day in 1..23) -> "天秤座 ♎"
        (month == 10 && day in 24..31) || (month == 11 && day in 1..22) -> "天蝎座 ♏"
        (month == 11 && day in 23..30) || (month == 12 && day in 1..21) -> "射手座 ♐"
        (month == 12 && day in 22..31) || (month == 1 && day in 1..19) -> "摩羯座 ♑"
        else -> ""
    }

    /** 按生日（epoch millis）算星座，使用设备时区与 iOS `Calendar.current` 等价。 */
    fun zodiacSign(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return zodiacSign(date.monthValue, date.dayOfMonth)
    }
}
