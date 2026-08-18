package com.situ.aichat.world

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 世界时钟（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §7 / W2 图纸 §3.1）：把 epoch 毫秒 + 用户时区
 * 折成「昼夜相位 / 季节 / 日进度 / 日序号」的**纯函数**——零依赖、零状态、参数带 [zone] 便于测。
 *
 * 「世界 = 现实时间」（决策 7）：全星球单时间，用户首启设的时区是本地锚。本对象不做任何结算/推进
 * （那属 [WorldSettlementCoordinator]），只把时间点翻译成世界的昼夜与季节。相位/季节边界**锁死**
 * （图纸 §9 禁改）：数值即世界的「物理常数」，改一位就是全世界穿越。
 */
object WorldClock {

    /** 昼夜相位（驱动 W9 地图光照与角色作息呈现）。 */
    enum class DayPhase { DAWN, DAY, DUSK, NIGHT }

    /** 季节（驱动地图光与景重绘；节日挪二期·决策 28）。 */
    enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

    // 相位边界（锁死·本地时间·图纸 §3.1/§9）：
    // DAWN [05:00, 07:00) · DAY [07:00, 17:00) · DUSK [17:00, 19:30) · NIGHT 其余（含跨午夜的 [00:00,05:00)）。
    private val DAWN_START: LocalTime = LocalTime.of(5, 0)
    private val DAY_START: LocalTime = LocalTime.of(7, 0)
    private val DUSK_START: LocalTime = LocalTime.of(17, 0)
    private val NIGHT_START: LocalTime = LocalTime.of(19, 30)

    /** [atMs] 落在 [zone] 本地时间的哪个昼夜相位。 */
    fun phaseAt(atMs: Long, zone: ZoneId): DayPhase {
        val t = localTimeOf(atMs, zone)
        return when {
            t < DAWN_START -> DayPhase.NIGHT   // [00:00, 05:00)
            t < DAY_START -> DayPhase.DAWN     // [05:00, 07:00)
            t < DUSK_START -> DayPhase.DAY     // [07:00, 17:00)
            t < NIGHT_START -> DayPhase.DUSK   // [17:00, 19:30)
            else -> DayPhase.NIGHT             // [19:30, 24:00)
        }
    }

    // 季节（锁死·月份映射·图纸 §3.1/§9）：3–5 SPRING · 6–8 SUMMER · 9–11 AUTUMN · 12/1/2 WINTER。
    /** [atMs] 落在 [zone] 本地日期的哪个季节。 */
    fun seasonAt(atMs: Long, zone: ZoneId): Season =
        when (localDateOf(atMs, zone).monthValue) {
            3, 4, 5 -> Season.SPRING
            6, 7, 8 -> Season.SUMMER
            9, 10, 11 -> Season.AUTUMN
            else -> Season.WINTER // 12, 1, 2
        }

    /**
     * 当日进度 ∈ [0, 1)：本地午夜起经过的秒数 / 86400f（驱动 W9 光照插值）。
     * 用墙钟当日秒数（`toSecondOfDay`）除以固定 86400——图纸 §3.1 锁死的定义。
     */
    fun dayProgress(atMs: Long, zone: ZoneId): Float =
        localTimeOf(atMs, zone).toSecondOfDay() / 86400f

    /** 两个本地日期（[originMs] → [atMs]）相隔的整天数（`ChronoUnit.DAYS`·可负）。 */
    fun dayIndexSince(originMs: Long, atMs: Long, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(localDateOf(originMs, zone), localDateOf(atMs, zone))

    /**
     * 用户时区串 → [ZoneId]（🔵-1 上收·世界结算/联动统一时区解析入口）：null/空/非法串一律回退
     * `systemDefault`，**绝不崩**（W2 图纸 §3.4 step1 / E8）。原 [WorldSettlementCoordinator] 与
     * [com.situ.aichat.world.social.WorldRelationshipEngine] 各有一份内联副本，W5 上收至此消灭重复。
     */
    fun resolveZone(timezoneId: String?): ZoneId =
        try {
            timezoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }

    /** [atMs] 在 [zone] 落到的本地日期（内部与测试共用）。 */
    fun localDateOf(atMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

    private fun localTimeOf(atMs: Long, zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalTime()
}
