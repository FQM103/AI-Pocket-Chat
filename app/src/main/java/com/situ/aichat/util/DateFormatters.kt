package com.situ.aichat.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Date helpers ported from iOS `Utilities/DateFormatters.swift`. The relative-day strings are
 * **hardcoded Chinese**, matching iOS (they are LLM-facing prompt content, not UI text — same convention
 * as the hardcoded Chinese guards in `PromptBuilder`).
 */
object DateFormatters {

    /**
     * 1:1 port of iOS `DateFormatters.relativeDay(from:to:)` — 相对时间描述，支持过去和未来：
     * 今天 / 昨天 / X天前 ；明天 / 后天 / X天后。
     *
     * 用设备时区的「日历日」做差（iOS 用 `Calendar.current` + `startOfDay`）。`diff` = 从 [fromMillis] 到
     * [nowMillis] 的天数：过去为正、未来为负。
     */
    fun relativeDay(fromMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val fromDay = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val toDay = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val diff = ChronoUnit.DAYS.between(fromDay, toDay)
        return when {
            diff == 0L -> "今天"
            diff == 1L -> "昨天"
            diff == -1L -> "明天"
            diff == -2L -> "后天"
            diff > 1L -> "${diff}天前"
            diff < -2L -> "${abs(diff)}天后"
            else -> ""
        }
    }

    /**
     * 本地化短时间（对齐 iOS `DateFormatter` dateStyle:.none timeStyle:.short）。用于线下见面入场/离场标记的
     * 「时间：」字段——AI 可见人读文本，跟随系统区域（如 zh "下午3:30" / en "3:30 PM"）。
     */
    fun shortTime(millis: Long): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(millis))

    /**
     * 本地化「中等日期 + 短时间」（对齐 iOS `Date.formatted(date: .abbreviated, time: .shortened)`，
     * .abbreviated≈MEDIUM / .shortened≈SHORT）。跟随系统区域：zh「2026年4月22日 15:45」/ en「Apr 22, 2026, 3:45 PM」。
     * 用于收礼详情等永久记录的精确时间显示（gift-1：取代只到天的 relativeDay）。
     */
    fun mediumDateShortTime(millis: Long): String =
        java.text.DateFormat
            .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(millis))

    /**
     * 本地化「长日期 + 短时间」（对齐 iOS `Date.formatted(date: .long, time: .shortened)`，.long≈LONG / .shortened≈SHORT）。
     * zh「2026年4月22日 15:45」/ en「April 22, 2026, 3:45 PM」。用于朋友圈详情页点按切换的精确时间（moments-ui-4）。
     */
    fun longDateShortTime(millis: Long): String =
        java.text.DateFormat
            .getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT)
            .format(java.util.Date(millis))

    /**
     * 本地化「相对时间（短）」（对齐 iOS `RelativeDateTimeFormatter(unitsStyle: .short)`）：刚刚 / X分钟前 / X小时前 /
     * X天前…，跟随系统区域。借安卓系统 DateUtils 达成同一用户效果（注：>1 周后系统会转显绝对日期，与 iOS 始终相对的
     * 细微差异——DIY 礼物多为近期可忽略）。用于 DIY 礼物详情等含小时/分钟粒度的相对时间（gift-2：取代只到天的 relativeDay）。
     */
    fun relativeTimeSpanShort(millis: Long, nowMillis: Long): String =
        android.text.format.DateUtils.getRelativeTimeSpanString(
            millis,
            nowMillis,
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()

    // 下列格式器模板只含数字/字面量、无 locale 敏感符号（无 EEE/MMM）→ 用 Locale.ROOT 恒输出 ASCII 数字
    // （对齐 iOS en_US_POSIX；修隐患：设备系统区域用非拉丁数字[如阿拉伯-印度数字]时，喂给 LLM 的 prompt 时间串 /
    // 通知标题 / UI 日期会变成非 ASCII 数字）。唯一 locale 敏感的周几名见下方 weekdayHourMinuteFormatter()。
    /** "M/d HH:mm"（对齐 iOS `threadSafeDateMDHM`）。用于通知物化兜底新建会话的标题。 */
    private val monthDayHourMinuteFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M/d HH:mm", Locale.ROOT)

    fun monthDayHourMinute(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(monthDayHourMinuteFormatter)

    /** "yyyy-MM-dd HH:mm"（对齐 iOS `threadSafeDateYMDHM`）。用于日记生成 prompt 的「当前时间」段。 */
    private val ymdHourMinuteFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun yearMonthDayHourMinute(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(ymdHourMinuteFormatter)

    /**
     * 日记「当前时间」段用：在 [yearMonthDayHourMinute] 基础上追加**本地化周几全名**，如
     * "2026-07-13 04:12 星期日" / "2026-07-13 04:12 Sunday"。EEEE 周几名随当前显示语言变化，故按当前
     * [Locale.getDefault] 构建、不缓存（同 [weekdayHourMinuteFormatter] 理由：应用内切语言不重启进程）。
     * 让日记 LLM 能从 exact time + 周几 + 日期自行判断「时段/季节」，无需硬编码词、也不编造。
     */
    fun yearMonthDayHourMinuteWithWeekday(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val weekday = Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
        return "${yearMonthDayHourMinute(millis, zone)} $weekday"
    }

    /** "M月d日 HH:mm"（对齐 iOS `threadSafeChineseMDHM`）。用于日记生成喂入的聊天记录时间前缀。 */
    private val chineseMonthDayHourMinuteFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M'月'd'日' HH:mm", Locale.ROOT)

    fun chineseMonthDayHourMinute(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(chineseMonthDayHourMinuteFormatter)

    /**
     * 1:1 port of iOS `DateFormatters.threadSafeMomentTimeDescription(_:now:)` — 朋友圈帖子/评论时间描述：
     * 绝对时间「M月d日 HH:mm」+ 可选相对后缀「· 刚刚 / X分钟前 / X小时前(同日) / 昨天 / X天前(2–7天)」。
     * 未来时间或 >7 天 → 仅绝对时间。供朋友圈发帖/评论 prompt 注入帖子时间。纯函数。
     */
    fun momentTimeDescription(millis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val absolute = chineseMonthDayHourMinute(millis, zone)
        val intervalSec = (nowMillis - millis) / 1000
        if (intervalSec < 0) return absolute
        val relative: String? = when {
            intervalSec < 60 -> "刚刚"
            intervalSec < 3600 -> "${intervalSec / 60}分钟前"
            else -> {
                val from = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                val to = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
                when {
                    from == to -> "${intervalSec / 3600}小时前"
                    from == to.minusDays(1) -> "昨天"
                    else -> {
                        val dayDiff = ChronoUnit.DAYS.between(from, to)
                        if (dayDiff in 2..7) "${dayDiff}天前" else null
                    }
                }
            }
        }
        return if (relative == null) absolute else "$absolute · $relative"
    }

    /** 本地化模板，由 UI 层注入到 [relativeTimeString]（安卓资源模型；iOS 用 `String(localized:)` 内联）。 */
    data class RelativeTimeStrings(
        /** "刚刚" / "Just now" */
        val justNow: String,
        /** "%1$d 分钟前" / "%1$d minutes ago" */
        val minutesAgo: String,
        /** "%1$d 小时前" / "%1$d hours ago" */
        val hoursAgo: String,
        /** "昨天 %1$s" / "Yesterday %1$s" */
        val yesterday: String,
    )

    private val hourMinuteFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** "HH:mm"。聊天气泡内联时间戳/时间分隔的热路径（原 ChatScreen 每次渲染各 new 一个 SimpleDateFormat → 复用本单例）。 */
    fun hourMinute(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(hourMinuteFormatter)

    private val monthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d", Locale.ROOT)
    private val yearMonthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.ROOT)
    /** "yyyy/M/d HH:mm"（=iOS yMdHm 模板）。P1-1 detailed 相对时间跨年尾分支；同年复用上方 [monthDayHourMinuteFormatter]。 */
    private val yearMonthDayHourMinuteFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.ROOT)

    /**
     * "EEE HH:mm"：EEE 周几名随当前显示语言变化（zh 周一 / en Mon），故每次按当前 [Locale.getDefault] 构建、
     * **不缓存**——避免应用内切语言（LocaleManager + recreate，进程不重启）后周几名陈旧（=ConstantLocale 正解；
     * 对齐 iOS 用当前 locale 显示周几）。
     */
    private fun weekdayHourMinuteFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())

    private fun fmt(millis: Long, zone: ZoneId, f: DateTimeFormatter): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(f)

    /** "M/d"（无前导零，对齐 iOS en_US_POSIX "M/d"）。用于见面摘要软上限合并行「4/1 公园 · 散步」。 */
    fun monthDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        fmt(millis, zone, monthDayFormatter)

    /** "yyyy/M/d"（对齐 iOS `dateYMD` 的 yMd 模板，年/月/日）。用于故事卡片 updatedAt / 章节列表 createdAt 的日期显示。 */
    fun dateYMD(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        fmt(millis, zone, yearMonthDayFormatter)

    /**
     * 1:1 port of iOS `DateFormatters.relativeTimeString(from:style:)` —— 朋友圈帖子卡片头部的相对时间。
     * 阈值逐字对齐 iOS：刚刚(<60s) / X分钟前(<1h) / X小时前(**同一自然日**且 ≥1h) / 昨天 HH:mm / 周X HH:mm(滚动 7
     * 天内) / 尾分支按档：compact（默认）M/d(同年)·yyyy/M/d(跨年)，[detailed]=true（P1-1 气泡朗读）
     * M/d HH:mm·yyyy/M/d HH:mm（=iOS .detailed 的 MdHm/yMdHm 模板）。未来时间(时钟偏移) → HH:mm。
     *
     * **注意 iOS 用 `isDateInToday`/`isDateInYesterday`（自然日），非区间**：昨天 23:00 在今天 00:30 看 → "昨天 23:00"
     * 而非 "1 小时前"。本地化串由调用方经 [RelativeTimeStrings] 注入，保持本函数纯、可单测。
     */
    fun relativeTimeString(
        millis: Long,
        nowMillis: Long,
        strings: RelativeTimeStrings,
        zone: ZoneId = ZoneId.systemDefault(),
        detailed: Boolean = false,
    ): String {
        val intervalSec = (nowMillis - millis) / 1000
        if (intervalSec < 0) return fmt(millis, zone, hourMinuteFormatter)
        if (intervalSec < 60) return strings.justNow
        if (intervalSec < 3600) return strings.minutesAgo.format(intervalSec / 60)
        val from = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val to = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        if (from == to) return strings.hoursAgo.format(intervalSec / 3600)
        if (from == to.minusDays(1)) return strings.yesterday.format(fmt(millis, zone, hourMinuteFormatter))
        if (intervalSec < 7 * 86400) return fmt(millis, zone, weekdayHourMinuteFormatter())
        return if (from.year == to.year) {
            fmt(millis, zone, if (detailed) monthDayHourMinuteFormatter else monthDayFormatter)
        } else {
            fmt(millis, zone, if (detailed) yearMonthDayHourMinuteFormatter else yearMonthDayFormatter)
        }
    }

    /** 当天 0 点的 epoch 毫秒（设备时区日历日，等价 iOS `Calendar.startOfDay`）。 */
    fun startOfDayMillis(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
