package com.situ.aichat.util

import kotlin.math.PI
import kotlin.math.cos

/**
 * 月相纯函数（见面回忆「那晚的天色」·契约 FABLE5_MEETING_MEMORY_SKY_PROPOSAL §2.3·D-2 拍板真实月相）。
 * 历元 = 2000-01-06T18:14Z 新月，朔望月 29.530588853 天取模——视觉级精度（误差数小时量级），非天文历法。
 */
object MoonPhase {
    private const val SYNODIC_MONTH_DAYS = 29.530588853
    private const val NEW_MOON_EPOCH_MILLIS = 947_182_440_000L // 2000-01-06T18:14:00Z
    private const val DAY_MILLIS = 86_400_000.0

    /** 相位 [0,1)：0 = 朔（新月）· 0.5 = 望（满月）；历元之前的时间同样正确取模。 */
    fun fraction(epochMillis: Long): Double {
        val days = (epochMillis - NEW_MOON_EPOCH_MILLIS) / DAY_MILLIS
        val f = (days / SYNODIC_MONTH_DAYS) % 1.0
        return if (f < 0) f + 1.0 else f
    }

    /** 照亮率 [0,1]：0 = 朔 · 1 = 望（余弦近似）。 */
    fun illumination(epochMillis: Long): Double = (1.0 - cos(2.0 * PI * fraction(epochMillis))) / 2.0

    /** 盈月（true·朔→望·亮面在右）/ 亏月（false·望→朔·亮面在左）。 */
    fun isWaxing(epochMillis: Long): Boolean = fraction(epochMillis) < 0.5
}
