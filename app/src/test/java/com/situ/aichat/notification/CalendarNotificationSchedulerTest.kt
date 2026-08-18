package com.situ.aichat.notification

import com.situ.aichat.data.calendar.CalendarReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日历事件通知纯逻辑单测（P6.3）。
 *
 * 核心保护对象：
 * 1) [CalendarNotificationContent.formatBody] 的占位语义——iOS Localizable.xcstrings 把 `%1$@`(标题) /
 *    `%2$lld`(分钟) **重排**到中文语序，安卓须用 `%1$s` / `%2$d` 对应。断言从 iOS 真实 zh 文案逐字反推，
 *    确保移植没把「标题」和「分钟数」插反（这是最容易出的移植 bug）。
 * 2) [CalendarNotificationScheduler.notifyTimeMillis]「事件前 N 分钟」算式。
 * 3) [CalendarNotificationScheduler.upcomingNotifications]「触发时刻仍在未来才排」筛选（对齐 iOS
 *    `notifyDate > now` 守卫）+ 保序。
 */
class CalendarNotificationSchedulerTest {

    private val title = "团队会议"

    // iOS CalendarNotificationService 的 5 条 zh 模板（Localizable.xcstrings 逐字），%1$s=标题 %2$d=分钟。
    private val zhTemplates = listOf(
        "你%2\$d分钟后有「%1\$s」哦，准备好了吗～",
        "提醒你一下，%2\$d分钟后的「%1\$s」别忘了！",
        "%2\$d分钟后你有「%1\$s」，加油～",
        "快到「%1\$s」的时间了，还有%2\$d分钟～",
        "嘿，别忘了%2\$d分钟后的「%1\$s」！",
    )

    @Test
    fun formatBody_zh_占位重排正确_标题与分钟不插反() {
        assertEquals("你30分钟后有「团队会议」哦，准备好了吗～", CalendarNotificationContent.formatBody(zhTemplates[0], title, 30))
        assertEquals("提醒你一下，30分钟后的「团队会议」别忘了！", CalendarNotificationContent.formatBody(zhTemplates[1], title, 30))
        assertEquals("30分钟后你有「团队会议」，加油～", CalendarNotificationContent.formatBody(zhTemplates[2], title, 30))
        assertEquals("快到「团队会议」的时间了，还有30分钟～", CalendarNotificationContent.formatBody(zhTemplates[3], title, 30))
        assertEquals("嘿，别忘了30分钟后的「团队会议」！", CalendarNotificationContent.formatBody(zhTemplates[4], title, 30))
    }

    @Test
    fun formatBody_分钟数随入参变化() {
        // minutesBefore 非硬编码——传 15 应渲染 15（防止把 30 写死进模板）。
        assertEquals("你15分钟后有「团队会议」哦，准备好了吗～", CalendarNotificationContent.formatBody(zhTemplates[0], title, 15))
    }

    @Test
    fun notifyTimeMillis_事件前30分钟() {
        val start = 1_000_000_000_000L
        assertEquals(start - 30L * 60_000, CalendarNotificationScheduler.notifyTimeMillis(start, 30))
        assertEquals(start - 15L * 60_000, CalendarNotificationScheduler.notifyTimeMillis(start, 15))
    }

    @Test
    fun upcomingNotifications_只排触发时刻仍在未来的事件_并保序() {
        val now = 1_000_000_000_000L
        val min = 60_000L
        fun ev(id: Long, beginOffsetMin: Long) =
            CalendarReader.CalEvent(title = "E$id", begin = now + beginOffsetMin * min, end = now, location = null, eventId = id)

        val e1 = ev(1, 60)  // 触发 = now+30min → 未来，排
        val e2 = ev(2, 20)  // 触发 = now-10min → 过去，跳
        val e3 = ev(3, 30)  // 触发 = now（恰好相等）→ 不满足 >now，跳（对齐 iOS notifyDate > now 严格大于）
        val e4 = ev(4, 90)  // 触发 = now+60min → 未来，排

        val result = CalendarNotificationScheduler.upcomingNotifications(listOf(e1, e2, e3, e4), now, 30)

        // 仅 e1、e4 入选，且保持原始（按开始时间升序）顺序。
        assertEquals(2, result.size)
        assertEquals(1L, result[0].first.eventId)
        assertEquals(now + 30 * min, result[0].second)
        assertEquals(4L, result[1].first.eventId)
        assertEquals(now + 60 * min, result[1].second)
    }
}
