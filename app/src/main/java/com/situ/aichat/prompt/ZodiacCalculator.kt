package com.situ.aichat.prompt

import java.time.Instant
import java.time.ZoneId

/** 1:1 port of iOS `ZodiacCalculator`. Local table lookup; no network. */
object ZodiacCalculator {

    /** 根据生日（epoch millis）计算星座（含符号），无法判定返回空串。 */
    fun zodiacSign(birthdayMillis: Long): String {
        val date = Instant.ofEpochMilli(birthdayMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val month = date.monthValue
        val day = date.dayOfMonth

        return when {
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
    }
}
