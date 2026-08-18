package com.situ.aichat.ui.promise

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** UI 侧格式化助手（三期）：日期 pattern 从字符串资源传入（promise_date_pattern_md / _ymd·双语）。 */
object PromiseUiFormat {
    fun format(millis: Long, pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(zone))

    /** 来源 label 资源：chat → 聊天中(定下)；meeting / meeting_backfill → 见面时(定下)（与注入 renderOpenLine 同口径）。 */
    fun sourceLabelRes(sourceRaw: String, short: Boolean): Int = when (sourceRaw) {
        PromiseSource.CHAT -> if (short) R.string.promise_source_chat_short else R.string.promise_source_chat
        else -> if (short) R.string.promise_source_meeting_short else R.string.promise_source_meeting
    }

    /** 到期天数差（本地日历日·E19）：正 = 还差 N 天；0 = 今天；负 = 已过 |N| 天。 */
    fun dueDayDiff(dueAtMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val dueDay = Instant.ofEpochMilli(dueAtMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(today, dueDay)
    }

    /** 判定方式推断（D-4 闭环不变量）：证据空 = 手动标记。 */
    fun isManualResolution(promise: PromiseEntity): Boolean = promise.resolutionEvidence.isBlank()
}
