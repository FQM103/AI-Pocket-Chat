package com.situ.aichat.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.situ.aichat.util.DateFormatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备日历只读访问（P5.3a / P5.3b）。对齐 iOS `EventKitService`「近期事件」读取 + `[#E1]` 编号格式化
 * （fetchUpcomingEvents = 此刻 → 后天 0 点 = 今天剩余 + 明天全天；formatEventsForPromptWithRefs）。
 *
 * 安卓无独立「提醒事项」系统 → 本期只做日历**事件**（reminders 平台缺口延后）。仅在已授权 READ_CALENDAR 时读取；
 * 行格式化 [formatEventsBlock] 抽成 internal 纯函数便于单测。
 *
 * P5.3b：同时返回 `#E{n}` → 事件 `_ID` 的[编号映射表][UpcomingEvents.eventRefMap]，供写入闭环把 AI 引用的 `#E1`
 * 解析回真实事件 id（对齐 iOS `formatEventsForPromptWithRefs` 的 refMap + ChatViewModel `calendarEventRefMap`）。
 */
@Singleton
class CalendarReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 单条日历事件（cursor → 纯数据，便于 [formatEventsBlock] 纯函数测试）。[eventId] 为 Events `_ID`，供写入解析 ref。 */
    data class CalEvent(
        val title: String,
        val begin: Long,
        val end: Long,
        val location: String?,
        val eventId: Long = 0L,
    )

    /** 近期事件块：注入提示词的[文本][text] + `#E{n}` → 事件 id 的[映射表][eventRefMap]。 */
    data class UpcomingEvents(val text: String, val eventRefMap: Map<String, Long>)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 近期事件的 `[#E1]` 提示词块 + 编号映射；无权限 / 无事件 → null。
     * 窗口对齐 iOS：此刻 [nowMillis] → 后天 0 点。
     */
    suspend fun upcomingEvents(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): UpcomingEvents? =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext null
            val events = queryEvents(nowMillis, endOfWindow(nowMillis, zone))
            if (events.isEmpty()) return@withContext null
            formatEventsBlock(events, nowMillis, zone)
        }

    /**
     * 近期事件的**原始列表**（P6.3 日历事件通知用）：供 [com.situ.aichat.notification.CalendarNotificationScheduler]
     * 为每个事件排「事件前 N 分钟」的角色通知。窗口同 [upcomingEvents]（此刻 → 后天 0 点 = 今天剩余 + 明天全天，
     * 对齐 iOS `fetchUpcomingEvents`）。无权限 → 空表（优雅降级）。
     */
    suspend fun upcomingRawEvents(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            queryEvents(nowMillis, endOfWindow(nowMillis, zone))
        }

    /** 任意时间窗 `[startMillis, endMillis)` 内的原始事件（M07 日记取当天事件）。无权限 → 空。 */
    suspend fun eventsInRange(startMillis: Long, endMillis: Long): List<CalEvent> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            queryEvents(startMillis, endMillis)
        }

    /** 窗口结束（后天 0 点，独占）。对齐 iOS `fetchUpcomingEvents` = startOfDay(now) + 2 天。 */
    private fun endOfWindow(nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone)
            .toLocalDate().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun queryEvents(beginMillis: Long, endMillis: Long): List<CalEvent> {
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, beginMillis)
        ContentUris.appendId(builder, endMillis)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.EVENT_ID,
        )
        val result = mutableListOf<CalEvent>()
        try {
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val iTitle = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val iBegin = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val iEnd = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val iLoc = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                val iEventId = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                while (c.moveToNext()) {
                    result.add(
                        CalEvent(
                            title = c.getString(iTitle) ?: "",
                            begin = c.getLong(iBegin),
                            end = c.getLong(iEnd),
                            location = c.getString(iLoc),
                            eventId = c.getLong(iEventId),
                        ),
                    )
                }
            }
        } catch (_: SecurityException) {
            // 权限在读取瞬间被撤销等 → 当作无数据，优雅降级。
            return emptyList()
        }
        return result
    }

    companion object {
        private val MD_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("M'月'd'日' HH:mm")
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * 事件列表 → `[#E1] 标题（相对日 M月d日 HH:mm~HH:mm · 地点）` 多行块 + `#E{n}` → 事件 id 映射。纯函数，单测。
         *
         * **P12.6 D7**：格式由「`时间 标题（地点）`」改为「`标题（时间 · 地点）`」——**标题在前、时间(+地点)入括号**。
         * 卡片解析器 [com.situ.aichat.prompt.CalendarItemParser.splitTitleAndDate]「括号即日期」会正确得 标题 / 时间·地点，
         * 不再把「时间+标题」当标题、「地点」当日期（修 iOS formatEventsForPromptWithRefs↔splitTitleAndDate 的格式错配小毛病）。
         * AI 被指示**照抄此行**（PromptBuilderCalendar）渲染成卡片，故格式器与提示词示例须一致。**有意偏离 iOS（变好）**。
         */
        internal fun formatEventsBlock(events: List<CalEvent>, nowMillis: Long, zone: ZoneId): UpcomingEvents {
            val refMap = mutableMapOf<String, Long>()
            val text = events.mapIndexed { index, e ->
                val ref = "#E${index + 1}"
                refMap[ref] = e.eventId
                val start = MD_HM.withZone(zone).format(Instant.ofEpochMilli(e.begin))
                val end = HM.withZone(zone).format(Instant.ofEpochMilli(e.end))
                val relative = DateFormatters.relativeDay(e.begin, nowMillis, zone)
                val timeDesc = if (relative.isEmpty()) "$start~$end" else "$relative $start~$end"
                val title = e.title.trim().ifEmpty { "无标题" }
                val location = e.location?.trim()?.takeIf { it.isNotEmpty() }
                val detail = if (location == null) timeDesc else "$timeDesc · $location"
                "[$ref] $title（$detail）"
            }.joinToString("\n")
            return UpcomingEvents(text, refMap)
        }
    }
}
