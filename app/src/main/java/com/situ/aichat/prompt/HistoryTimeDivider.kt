package com.situ.aichat.prompt

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 历史对话「时间分割线」生成器（Fable-5 时间感知优化）。
 *
 * 背景：聊天历史按 user/assistant 合并成纯文本流后，每条消息**自带的发生时刻被丢弃**——一段横跨数天、
 * 在不同真实时刻分别生成的对话被压成「看似连续」的一坨；LLM 只能靠 suffix 区单条 <time_context> 对齐，
 * 于是把几天前白天说的「下午三点」当成此刻（深夜）的状态。本生成器在「相邻两条消息间隔够久」处补一条
 * 独立的 system 时间分割线（微信式），把时间骨架还给 LLM。
 *
 * 设计要点：
 * - **变化才标**：仅在间隔 ≥ [GAP_THRESHOLD_SECONDS] 或跨自然日时插入；连发消息不切碎（信息密度刚好，
 *   也让「时间跳变」这个关键信号最突出）。历史第一条仅当落在往日才给起始锚——首条若就在「今天」，
 *   与 suffix 区 <time_context> 的当前时间重复，省略以免每条 prompt 都顶一行冗余。
 * - **独立 system 消息**（由调用方包成 ROLE_SYSTEM），不塞进消息正文：assistant 正文里的方括号时间戳
 *   会被 [ReplyParser.decontaminateAssistantContent] 的首行时间戳正则剥掉、圆括号旁白会被
 *   [ReplyParser.stripAssistantParentheticalNarration] 剥掉；且塞正文会诱导 LLM few-shot 模仿。system
 *   消息天然免疫模仿。
 * - **横线包裹格式**避开 [DirtyMessageDetector] 的所有保留标记（不含 `<event time=`/`【…】`/`[系统记录`）；
 *   万一 LLM 仍模仿，[ReplyParser] 输出端有横线 echo 兜底。
 * - **相对当前时间**表达（今天/昨天/M月D日 周X），与 [TimeAnchorFormatter] 同口径；zone 注入便于确定性单测。
 */
object HistoryTimeDivider {

    /** 间隔阈值：相邻两条消息相隔 ≥ 30 分钟即视为「隔了一阵又来」，插分割线。 */
    const val GAP_THRESHOLD_SECONDS: Long = 30 * 60

    /**
     * 分割线整行格式的前/后缀（独立 system 消息）。用项目一致的【】系统标签，**不用横线装饰**——LLM 对
     * 「看到【】= 系统信息」先验最强（同【此刻】、日程等系统段），比 ──── 横线更可能被当成可信时间锚而非
     * 排版噪音。前缀避开 [DirtyMessageDetector] 的保留标记（不撞【见面 ·】/【长期事实】/【你今天完整的日程】等）。
     */
    const val OPEN = "【时间 · "
    const val CLOSE = "】"

    private val timeOnlyFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 手写星期映射（日=0），不随设备 locale，与 [TimeAnchorFormatter] 一致。 */
    private val chineseWeekdayChars = listOf("日", "一", "二", "三", "四", "五", "六")

    /**
     * 计算某条历史消息前是否要插时间分割线——要插则返回整行文本（含横线包裹），否则返回 null。
     *
     * @param messageTimeMillis 当前消息发生时刻（epoch millis，= `MessageEntity.timestamp`）
     * @param previousTimeMillis 上一条已遍历消息的时刻；null = 这是历史第一条（总给起始锚）
     * @param now 当前真实时间（用于「今天/昨天」相对表达）
     * @param zone 时区（注入以便确定性单测；线上传 `ZoneId.systemDefault()`）
     */
    fun lineFor(
        messageTimeMillis: Long,
        previousTimeMillis: Long?,
        now: Instant,
        zone: ZoneId,
    ): String? {
        if (previousTimeMillis == null) {
            // 历史第一条：仅当落在往日才给起始锚（消除「后面的消息钟点反而更早」的跨日歧义）；
            // 首条就在今天则省略——与 <time_context> 当前时间重复。
            if (dayDifference(messageTimeMillis, now.toEpochMilli(), zone) == 0) return null
            return wrap(messageTimeMillis, now, zone)
        }
        val gapSeconds = (messageTimeMillis - previousTimeMillis) / 1000
        val crossesDay = dayDifference(previousTimeMillis, messageTimeMillis, zone) != 0
        if (gapSeconds < GAP_THRESHOLD_SECONDS && !crossesDay) return null
        return wrap(messageTimeMillis, now, zone)
    }

    /** 某条消息内容是否是本生成器产出的时间分割线整行（清理悬空分割线 / 输出端剥 echo 用）。 */
    fun isDivider(content: String): Boolean {
        val t = content.trim()
        return t.startsWith(OPEN) && t.endsWith(CLOSE)
    }

    private fun wrap(messageTimeMillis: Long, now: Instant, zone: ZoneId): String =
        "$OPEN${formatLabel(messageTimeMillis, now, zone)}$CLOSE"

    /** 相对 now 的可读时刻：今天 HH:mm / 昨天 HH:mm / M月D日 周X HH:mm。 */
    internal fun formatLabel(messageTimeMillis: Long, now: Instant, zone: ZoneId): String {
        val msgDate = Instant.ofEpochMilli(messageTimeMillis).atZone(zone)
        val hhmm = msgDate.toLocalTime().format(timeOnlyFmt)
        return when (dayDifference(messageTimeMillis, now.toEpochMilli(), zone)) {
            0 -> "今天 $hhmm"
            1 -> "昨天 $hhmm"
            else -> {
                val weekday = "周${chineseWeekdayChars[msgDate.dayOfWeek.value % 7]}"
                "${msgDate.monthValue}月${msgDate.dayOfMonth}日 $weekday $hhmm"
            }
        }
    }

    /** 自然日差（基于本地日期，不受时分干扰）：to_date − from_date。 */
    private fun dayDifference(fromMillis: Long, toMillis: Long, zone: ZoneId): Int {
        val fromDay = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val toDay = Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(fromDay, toDay).toInt()
    }
}
