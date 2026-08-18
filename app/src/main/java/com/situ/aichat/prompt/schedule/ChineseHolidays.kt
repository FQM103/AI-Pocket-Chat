package com.situ.aichat.prompt.schedule

import java.time.LocalDate

/**
 * 中国大陆法定节假日与调休硬表（图纸 2026-07-10 日程专项 C3）。数据源 = **2026 年官方安排**
 * （国办发明电〔2025〕7号·2025-11-04 发布，已逐日核对人民网/新华网原文）；**每年随版本手工更新**
 * （用户拍板⑦）：官方通常 11 月公布次年安排，届时追加下一年区间，绝不凭农历推算预填未公布年份。
 *
 * 纯本地零依赖（铁律 #3），仅日程生成的「工作日/休息日」判定与节日氛围行消费。
 * 覆盖区间外返回 null = 调用方回退现行「周六/日=休息日」逻辑（优雅降级，字节级兼容旧行为）。
 */
object ChineseHolidays {

    /** 某一天的节假日属性。 */
    sealed interface DayInfo {
        /** 法定节假日（含调休连休日），[name] = 节日名（元旦/春节/清明节/劳动节/端午节/中秋节/国庆节）。 */
        data class Holiday(val name: String) : DayInfo

        /** 调休补班日（周末上班）。 */
        data object MakeupWorkday : DayInfo
    }

    /** 表覆盖起点（含）。 */
    val COVERAGE_START: LocalDate = LocalDate.of(2026, 1, 1)

    /** 表覆盖终点（含）。 */
    val COVERAGE_END: LocalDate = LocalDate.of(2026, 12, 31)

    /** 2026 放假区间（首日..末日，闭区间）→ 节日名。逐日对齐国办发明电〔2025〕7号。 */
    private val HOLIDAY_RANGES: List<Triple<LocalDate, LocalDate, String>> = listOf(
        Triple(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3), "元旦"),
        Triple(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 23), "春节"),
        Triple(LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 6), "清明节"),
        Triple(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5), "劳动节"),
        Triple(LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 21), "端午节"),
        Triple(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 27), "中秋节"),
        Triple(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), "国庆节"),
    )

    /** 2026 调休补班日（周末上班）。逐日对齐官方通知。 */
    private val MAKEUP_WORKDAYS: Set<LocalDate> = setOf(
        LocalDate.of(2026, 1, 4),   // 日·元旦补
        LocalDate.of(2026, 2, 14),  // 六·春节补
        LocalDate.of(2026, 2, 28),  // 六·春节补
        LocalDate.of(2026, 5, 9),   // 六·劳动节补
        LocalDate.of(2026, 9, 20),  // 日·国庆补
        LocalDate.of(2026, 10, 10), // 六·国庆补
    )

    /**
     * 查某日的节假日属性：法定假 → [DayInfo.Holiday]；调休补班 → [DayInfo.MakeupWorkday]；
     * 表内普通日 / **表外日期一律 null**（调用方回退周末逻辑）。
     */
    fun dayInfoFor(date: LocalDate): DayInfo? {
        if (date.isBefore(COVERAGE_START) || date.isAfter(COVERAGE_END)) return null
        for ((start, end, name) in HOLIDAY_RANGES) {
            if (!date.isBefore(start) && !date.isAfter(end)) return DayInfo.Holiday(name)
        }
        if (date in MAKEUP_WORKDAYS) return DayInfo.MakeupWorkday
        return null
    }
}
