package com.situ.aichat.data.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备日历**写入**（P5.3b）。对齐 iOS `EventKitService` 的 create/update/deleteEvent（仅事件，提醒类平台缺口延后）。
 *
 * 用 `CalendarContract.Events` 增改删；写入前需 WRITE_CALENDAR 授权。事件落在主日历（无主日历则第一个可写日历）。
 * 创建时默认时长 1 小时（对齐 iOS endDate ?? start+3600）+ 尽力添加事件前 15 分钟系统提醒（对齐 iOS EKAlarm(-900)）。
 *
 * **取舍**：修改/删除按事件 `_ID` 作用于整个事件（含周期事件整条），不做单实例例外——AI 管理的多为单次事件，
 * 与 iOS `span: .thisEvent` 在非周期事件上等价；周期事件单实例编辑非本期范围（保持简单）。
 */
@Singleton
class CalendarWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 写入失败的可读错误（上层 catch 后 toast/snackbar）。 */
    class CalendarWriteException(message: String) : Exception(message)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 创建事件。[endMillis] 为空 → 起始后 1 小时。
     *
     * [addSystemReminder]（P6.3）：是否附带系统 15 分钟提醒。默认 true = iOS 行为；当「日历提醒方式」为
     * 「仅角色提醒」([com.situ.aichat.notification.CalendarReminderMode.CHARACTER]) 时由调用方传 false——
     * 此时不写系统提醒，改由 app 侧事件前 30min 角色通知负责（decision②）。
     */
    suspend fun createEvent(
        title: String,
        startMillis: Long,
        endMillis: Long?,
        notes: String?,
        location: String?,
        addSystemReminder: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        requirePermission()
        val resolver = context.contentResolver
        val calendarId = defaultCalendarId(resolver)
            ?: throw CalendarWriteException("找不到可写入的日历账户")
        val end = endMillis ?: (startMillis + 3_600_000L)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            notes?.takeIf { it.isNotEmpty() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            location?.takeIf { it.isNotEmpty() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.HAS_ALARM, if (addSystemReminder) 1 else 0)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw CalendarWriteException("创建日历事件失败")
        val eventId = ContentUris.parseId(uri)
        if (addSystemReminder) addDefaultReminder(resolver, eventId)
    }

    /** 修改事件（仅更新非空字段，对齐 iOS updateEvent）。[title] 为空串视为不改。 */
    suspend fun updateEvent(
        eventId: Long,
        title: String?,
        startMillis: Long?,
        endMillis: Long?,
        notes: String?,
        location: String?,
    ) = withContext(Dispatchers.IO) {
        requirePermission()
        val values = ContentValues().apply {
            title?.takeIf { it.isNotEmpty() }?.let { put(CalendarContract.Events.TITLE, it) }
            startMillis?.let { put(CalendarContract.Events.DTSTART, it) }
            endMillis?.let { put(CalendarContract.Events.DTEND, it) }
            notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        if (values.size() == 0) return@withContext
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = context.contentResolver.update(uri, values, null, null)
        if (rows == 0) throw CalendarWriteException("找不到该日历事件，可能已被删除或改动")
    }

    /** 删除事件。 */
    suspend fun deleteEvent(eventId: Long) = withContext(Dispatchers.IO) {
        requirePermission()
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = context.contentResolver.delete(uri, null, null)
        if (rows == 0) throw CalendarWriteException("找不到该日历事件，可能已被删除或改动")
    }

    private fun requirePermission() {
        if (!hasPermission()) throw CalendarWriteException("未获得日历写入权限，请在系统设置中允许")
    }

    /** 事件前 15 分钟系统提醒（尽力而为，对齐 iOS EKAlarm(relativeOffset:-900)）。失败不影响事件创建。 */
    private fun addDefaultReminder(resolver: ContentResolver, eventId: Long) {
        runCatching {
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, 15)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        }
    }

    /** 解析默认写入日历：优先主日历，否则第一个可写（访问级别 ≥ CONTRIBUTOR）日历。 */
    private fun defaultCalendarId(resolver: ContentResolver): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        return runCatching {
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val iAccess = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                var firstId: Long? = null
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    if (firstId == null) firstId = id
                    val access = c.getInt(iAccess)
                    if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) return id
                }
                firstId
            }
        }.getOrNull()
    }
}
