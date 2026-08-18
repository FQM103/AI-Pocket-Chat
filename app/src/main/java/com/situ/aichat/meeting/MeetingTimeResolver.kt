package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingTimeGranularity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 约定时间解析器（1:1 iOS `Services/MeetingTimeResolver.swift`）。
 * 把「LLM 给的时间」解析成绝对时刻（epoch millis）+ 精度 [MeetingTimeGranularity]。
 *
 * 纯函数、可注入 [Instant] now / [ZoneId]，便于确定性测试（不依赖系统当前时间）。用 `java.time`（minSdk 29 原生）。
 *
 * 策略：
 * 1. **主路**：解析 LLM 的具体 ISO / 常见格式时间，校验「未来 + ≤ horizon」。含时间分量 → exact；纯日期 → dayOnly（补 19 点）。
 * 2. **兜底**：对模型原话跑小而稳的中文相对短语解析（今天/明天/后天/大后天/周末/周X/下周X/N天后 + 时段），只接常见低歧义说法。
 * 3. **最终**：都不行 → [MeetingTimeGranularity.VAGUE]，占位次日 19 点，交确认卡让用户敲定。
 *
 * 关键设计：每个结果最终都在确认卡上给用户核对、可改期，所以「猜错代价低」——确认闸门是安全网。
 * 故追求「常见情况稳」，不追求「覆盖一切」。**AM/PM 有意不猜**（见 [parseClockHour]）；中文兜底只到「点」、
 * **有意不解析分钟**（如「3点半」「15:30」按整点算，分钟由 ISO 主路保留或确认卡敲定——兜底仅在模型未给 ISO 时走）。
 */
object MeetingTimeResolver {

    /** 只到天（无明确时段）时补的默认小时（傍晚 19 点，常见见面时段）。 */
    const val DEFAULT_DAY_ONLY_HOUR = 19

    /** 允许的最远跨度（天）。超过视为不合理 → vague。 */
    const val DEFAULT_HORIZON_DAYS = 365L

    /** 解析结果：绝对见面时刻（epoch millis）+ 精度。 */
    data class Resolution(
        val scheduledAtMillis: Long,
        val granularity: MeetingTimeGranularity,
    )

    /**
     * 主入口。
     * @param isoDateTime LLM 给的具体时间字符串（可空）
     * @param rawWhen 模型原话的时间说法（兜底）
     * @param now 当前时间（注入，便于测试）
     * @param zone 时区（注入，便于测试）
     * @param horizonDays 最远允许跨度（天）
     */
    fun resolve(
        isoDateTime: String?,
        rawWhen: String,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        horizonDays: Long = DEFAULT_HORIZON_DAYS,
    ): Resolution {
        // 1. 主路：LLM 具体时间
        val iso = isoDateTime?.trim()
        if (!iso.isNullOrEmpty()) {
            parseConcrete(iso, zone)?.let { if (isValid(it.scheduledAtMillis, now, horizonDays)) return it }
        }

        // 2. 兜底：中文相对短语
        parseRelativeChinese(rawWhen, now, zone)?.let { if (isValid(it.scheduledAtMillis, now, horizonDays)) return it }

        // 3. 最终：vague —— 占位次日默认时段，交确认卡敲定
        val tomorrow = now.atZone(zone).toLocalDate().plusDays(1)
        return Resolution(dayAtMillis(tomorrow, DEFAULT_DAY_ONLY_HOUR, zone), MeetingTimeGranularity.VAGUE)
    }

    // ── 校验 ──

    /** 必须晚于 now，且不超过 horizon。 */
    private fun isValid(millis: Long, now: Instant, horizonDays: Long): Boolean {
        if (millis <= now.toEpochMilli()) return false
        val horizon = now.plusSeconds(horizonDays * 24 * 60 * 60).toEpochMilli()
        return millis <= horizon
    }

    // ── 主路：具体时间字符串 ──

    /** 解析 ISO / 常见格式。含时间分量 → exact；纯日期 → dayOnly（补默认时段）。 */
    private fun parseConcrete(s: String, zone: ZoneId): Resolution? {
        // 带时区偏移：2026-06-27T15:00:00+08:00 / ...Z
        runCatching { OffsetDateTime.parse(s) }.getOrNull()?.let {
            return Resolution(it.toInstant().toEpochMilli(), MeetingTimeGranularity.EXACT)
        }
        // 带时间无时区：2026-06-27T15:00(:00) → 按注入 zone 解释
        runCatching { LocalDateTime.parse(s) }.getOrNull()?.let {
            return Resolution(it.atZone(zone).toInstant().toEpochMilli(), MeetingTimeGranularity.EXACT)
        }
        // 纯日期：2026-06-27 → dayOnly 补默认时段（用 zone 取当天，规避时区午夜偏移）
        runCatching { LocalDate.parse(s) }.getOrNull()?.let {
            return Resolution(dayAtMillis(it, DEFAULT_DAY_ONLY_HOUR, zone), MeetingTimeGranularity.DAY_ONLY)
        }
        return null
    }

    // ── 兜底：中文相对短语 ──

    private fun parseRelativeChinese(raw: String, now: Instant, zone: ZoneId): Resolution? {
        val phrase = raw.trim()
        if (phrase.isEmpty()) return null

        val today = now.atZone(zone).toLocalDate()
        val todayWeekday = iosWeekday(today) // 周日=1 … 周六=7（对齐 iOS Calendar）

        // 先确定「哪一天」，认不出就放弃（→ vague）
        val dayOffset = parseDayOffset(phrase, todayWeekday) ?: return null

        // 再确定时段
        val (hour, explicit) = parseTimeOfDay(phrase)

        val targetDate = today.plusDays(dayOffset.toLong())
        return Resolution(
            dayAtMillis(targetDate, hour, zone),
            if (explicit) MeetingTimeGranularity.EXACT else MeetingTimeGranularity.DAY_ONLY,
        )
    }

    /** 解析「哪一天」→ 相对今天的天偏移。认不出返回 null。 */
    private fun parseDayOffset(phraseRaw: String, todayWeekday: Int): Int? {
        // 简繁归一（仅影响本函数的日期判定）：把「個→个 / 週→周 / 禮→礼」归到简体，再做匹配。否则下面的
        // 「下星期 / 下個星期」清单**漏了简体「下个星期」**（「下个星期五」不含「下星期」也不含「下個星期」）→ 落到
        // offsetToComing 误判成**本周五**而非下周五（早 7 天）；「下個周末 / 下个星期末」同理漏配。时段词（夜里/夜裡 等）
        // 在 parseTimeOfDay 各自已列简繁两形，不在此归一。
        val phrase = phraseRaw.replace('個', '个').replace('週', '周').replace('禮', '礼')

        // 绝对常见词（注意顺序：大后天 在 后天 之前）
        if (phrase.contains("大后天")) return 3
        if (phrase.contains("后天")) return 2
        if (phrase.contains("明天") || phrase.contains("明儿") || phrase.contains("明日")) return 1
        if (phrase.contains("今天") || phrase.contains("今晚") || phrase.contains("今儿") || phrase.contains("今日")) return 0

        // 「N天后」/「过N天」
        parseDaysLater(phrase)?.let { return it }

        val saturday = 7 // iOS 体系周六=7

        // 下周末 → 下周六；周末 / 这周末 → 最近的周六（归一后简体即覆盖简繁；含「个」变体）
        if (phrase.contains("下周末") || phrase.contains("下个周末") ||
            phrase.contains("下星期末") || phrase.contains("下个星期末")
        ) {
            return offsetToNextWeek(saturday, todayWeekday)
        }
        if (phrase.contains("周末")) {
            return offsetToComing(saturday, todayWeekday)
        }

        // 下周X / 周X（归一后简体即覆盖简繁；含「个」变体：下个星期X / 下个礼拜X）
        weekdayInPhrase(phrase)?.let { wd ->
            if (phrase.contains("下周") || phrase.contains("下个周") ||
                phrase.contains("下星期") || phrase.contains("下个星期") ||
                phrase.contains("下礼拜") || phrase.contains("下个礼拜")
            ) {
                return offsetToNextWeek(wd, todayWeekday)
            }
            return offsetToComing(wd, todayWeekday)
        }

        return null
    }

    /** 最近一次该星期几（同一天视为下周，因为是「未来」约定）。 */
    private fun offsetToComing(targetWeekday: Int, todayWeekday: Int): Int {
        val diff = (targetWeekday - todayWeekday + 7) % 7
        return if (diff == 0) 7 else diff
    }

    /** 下一周（自然周 · 周一为周首）的该星期几。 */
    private fun offsetToNextWeek(targetWeekday: Int, todayWeekday: Int): Int {
        val daysSinceMonday = (todayWeekday - 2 + 7) % 7
        val nextMondayOffset = -daysSinceMonday + 7
        val targetFromMonday = (targetWeekday - 2 + 7) % 7
        return nextMondayOffset + targetFromMonday
    }

    /** 从短语识别「周X / 星期X / 礼拜X」的星期几（iOS 体系：周日=1 … 周六=7）。取标记词后第一个字符。 */
    private fun weekdayInPhrase(phrase: String): Int? {
        val map = mapOf('一' to 2, '二' to 3, '三' to 4, '四' to 5, '五' to 6, '六' to 7, '日' to 1, '天' to 1)
        val markers = listOf("星期", "礼拜", "禮拜", "周", "週")
        for (marker in markers) {
            val idx = phrase.indexOf(marker)
            if (idx >= 0) {
                val afterIdx = idx + marker.length
                if (afterIdx < phrase.length) {
                    map[phrase[afterIdx]]?.let { return it }
                }
            }
        }
        return null
    }

    /** 解析「N天后 / N天之后 / 过N天」。 */
    private fun parseDaysLater(phrase: String): Int? {
        val patterns = listOf(
            Regex("([0-9]{1,3})\\s*天\\s*(后|後|之后|之後)"),
            Regex("过\\s*([0-9]{1,3})\\s*天"),
            Regex("過\\s*([0-9]{1,3})\\s*天"),
        )
        for (re in patterns) {
            re.find(phrase)?.let { m ->
                val n = m.groupValues[1].toIntOrNull()
                if (n != null && n > 0) return n
            }
        }
        return null
    }

    /** 解析时段 → (小时, 是否给了明确时间)。无明确时段返回默认时段且 explicit=false（→ dayOnly）。 */
    private fun parseTimeOfDay(phrase: String): Pair<Int, Boolean> {
        parseClockHour(phrase)?.let { return it to true }
        if (phrase.contains("凌晨")) return 5 to true
        if (phrase.contains("早上") || phrase.contains("上午") || phrase.contains("早晨") || phrase.contains("一早")) return 9 to true
        if (phrase.contains("中午") || phrase.contains("正午")) return 12 to true
        if (phrase.contains("下午")) return 15 to true
        if (phrase.contains("傍晚")) return 18 to true
        if (phrase.contains("晚上") || phrase.contains("夜里") || phrase.contains("夜裡") || phrase.contains("今晚") || phrase.contains("晚")) return 20 to true
        return DEFAULT_DAY_ONLY_HOUR to false
    }

    /**
     * 解析「X点 / X:00 / X时」（阿拉伯数字）。带 下午/晚/傍晚/夜 且 X<12 → +12。
     *
     * **AM/PM 歧义有意不猜**：裸「7点」（无时段词）按字面取 7（上午）。任何「裸小时偏晚间」启发式都可能反向错
     * （有人就是约早 7 点）。两道安全网：① 主路 ISO 已含 AM/PM，本兜底仅 ISO 缺失 / 非法才走；
     * ② 结果都在确认卡给用户核对、可改期。故宁可不猜，把歧义交给用户。
     */
    private fun parseClockHour(phrase: String): Int? {
        val m = Regex("([0-9]{1,2})\\s*[点點时時:：]").find(phrase) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        if (h < 12 && (phrase.contains("下午") || phrase.contains("晚") || phrase.contains("傍晚") || phrase.contains("夜"))) {
            h += 12
        }
        // 12 点 + 夜间语境（晚上/夜里/凌晨）= 午夜 0 点，而非正午（中午/正午/裸「12点」仍取 12）。落在「那天 00:00」——
        // 跨日界细节（其实指次日凌晨）交确认卡兜底，但时段取 0 点已纠正旧实现「晚上12点误判成正午 12:00」。
        if (h == 12 && (phrase.contains("晚") || phrase.contains("夜") || phrase.contains("凌晨"))) h = 0
        if (h == 24) h = 0
        return if (h in 0..23) h else null
    }

    // ── 工具 ──

    /** 把某天规整到「当天指定小时」（分秒归零），用给定 zone，返回 epoch millis。 */
    private fun dayAtMillis(date: LocalDate, hour: Int, zone: ZoneId): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** java.time DayOfWeek（周一=1 … 周日=7）→ iOS Calendar weekday（周日=1 … 周六=7）。 */
    private fun iosWeekday(date: LocalDate): Int = (date.dayOfWeek.value % 7) + 1
}
