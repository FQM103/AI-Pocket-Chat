package com.situ.aichat.world.stage

import com.situ.aichat.world.WorldSeeds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 世界天气三态（W9d 图纸 §4.1·锁死）。 */
enum class WorldWeatherKind { CLEAR, RAIN, SNOW }

/**
 * 世界天气源（W9d 图纸 §4.1·§9 禁改·拍板②「室内窗景 + 日程输入跟真实系统时间」）。
 *
 * 种子确定性 per(城,本地日)——同 (seed,城,日) 恒同结果（可重放、零 token·同 W3 派生族）；
 * 室内窗景（昼/夜 × 晴/雨/雪）与日程生成天气输入**同源**。全部常量（概率四值 / 派生盐 `"weather"` +
 * `fnv1a64("$cityId:$epochDay")` / 月份分季 / 7·19 昼夜界 / 词-emoji 表）= 世界物理常数，改一位 = 全世界穿越。
 */
object WorldWeather {

    /** [localDate] 月份 → 季节降水概率（§4.1·春0.30雨 / 夏0.25雨 / 秋0.20雨 / 冬0.25雪）。 */
    private fun precipProbOf(month: Int): Double = when (month) {
        3, 4, 5 -> 0.30    // 春
        6, 7, 8 -> 0.25    // 夏
        9, 10, 11 -> 0.20  // 秋
        else -> 0.25       // 冬（12/1/2）
    }

    /** 冬季降水 = 雪，其余季 = 雨（§4.1）。 */
    private fun isWinter(month: Int): Boolean = month == 12 || month == 1 || month == 2

    /**
     * (seed,城,本地日) → 天气（§4.1·锁死）：
     * `rand = randomOf(derive(seed,"weather",fnv1a64("$cityId:$epochDay")))`；`rand.nextDouble() < p` → 降水。
     */
    fun kindOf(worldSeed: Long, cityId: String, localDate: LocalDate): WorldWeatherKind {
        val month = localDate.monthValue
        val p = precipProbOf(month)
        val salt = WorldSeeds.fnv1a64("$cityId:${localDate.toEpochDay()}")
        val rand = WorldSeeds.randomOf(WorldSeeds.derive(worldSeed, "weather", salt))
        if (rand.nextDouble() >= p) return WorldWeatherKind.CLEAR
        return if (isWinter(month)) WorldWeatherKind.SNOW else WorldWeatherKind.RAIN
    }

    /** 昼夜（§4.1·本地时·`night = hour < 7 || hour >= 19`）。 */
    fun isNight(atMs: Long, zone: ZoneId): Boolean {
        val hour = Instant.ofEpochMilli(atMs).atZone(zone).hour
        return hour < 7 || hour >= 19
    }

    /**
     * 天气词（§4.1 表·chrome 副标 / 日程入库 / 天气行共用）。chrome 走 [R.string] `world_weather_*`（§4.10），
     * 本函数供日程入库（`weatherCondition`）与生成 prompt 天气行（逻辑层·非资源）——两侧字面必须一致（§9 锁死）。
     */
    fun word(kind: WorldWeatherKind, night: Boolean): String = when (kind) {
        WorldWeatherKind.CLEAR -> if (night) "夜" else "晴"
        WorldWeatherKind.RAIN -> if (night) "雨夜" else "雨天"
        WorldWeatherKind.SNOW -> if (night) "雪夜" else "雪天"
    }

    /** 天气 emoji（§4.1 表·日程入库 `weatherEmoji`）。 */
    fun emoji(kind: WorldWeatherKind, night: Boolean): String = when (kind) {
        WorldWeatherKind.CLEAR -> if (night) "🌙" else "☀️"  // 🌙 / ☀️
        WorldWeatherKind.RAIN -> "🌧️"                              // 🌧️
        WorldWeatherKind.SNOW -> "❄️"                                    // ❄️
    }
}
