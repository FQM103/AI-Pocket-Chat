package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 刀4 日程旧戏份压缩（招3·2026-07-11 过审）行为测试：[✓已发生] 事件合并为一行流水账
 * （保留时段词·「→」串联·删钟点/地点/心情）；[▶️正在]/[⏳未来] 各行全细节不变；
 * 段标题「【你今天完整的日程】」与三个时态标签字面零碰（红线互指:DirtyMessageDetector/状态标签说明）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class ScheduleModulePastCompressionTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day = LocalDate.of(2026, 7, 11)
    private val now = LocalDateTime.of(2026, 7, 11, 13, 39).atZone(zone).toInstant()
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun scheduleText(): String {
        val sched = CharacterDailyScheduleEntity(
            uuid = "s1", characterUuid = "c1",
            date = day.atStartOfDay(zone).toInstant().toEpochMilli(), generatedAt = at(6, 0),
        )
        val events = listOf(
            ScheduleEventEntity("e1", "s1", at(8, 0), at(9, 0), "早上", "家里阳台", "晾被单", moodText = "惬意"),
            ScheduleEventEntity("e2", "s1", at(9, 0), at(11, 30), "上午", "咖啡店", "开店"),
            ScheduleEventEntity("e3", "s1", at(12, 30), at(14, 0), "午后", "店里二楼", "午休小憩"),
            ScheduleEventEntity("e4", "s1", at(14, 0), at(17, 30), "下午", "咖啡店", "拉花赶单"),
        )
        val msgs = PromptBuilder.buildMessages(
            character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L),
            sortedMessages = listOf(
                MessageEntity(messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "在干嘛", timestamp = now.toEpochMilli() - 60_000),
            ),
            userProfile = null, appSettings = AppSettings(), strings = PromptStrings(RuntimeEnvironment.getApplication()),
            todaySchedule = sched, todayScheduleEvents = events, now = now,
        )
        val system = msgs.first().content.orEmpty()
        return system.substringAfter("【你今天完整的日程】").substringBefore("【状态标签说明")
    }

    @Test
    fun 已发生事件合并一行_保时段词_删钟点地点心情() {
        val text = scheduleText()
        assertTrue("流水账行:时段词+活动+箭头串联", text.contains("[✓已发生] 早上 晾被单 → 上午 开店"))
        assertEquals("已发生标签只出现一次(合并成一行)", 1, Regex("\\[✓已发生\\]").findAll(text).count())
        assertFalse("已发生行不含钟点", text.contains("08:00"))
        assertFalse("已发生行不含地点", text.contains("家里阳台"))
        assertFalse("已发生行不含心情", text.contains("惬意"))
    }

    @Test
    fun 正在与未来各行全细节不变() {
        val text = scheduleText()
        assertTrue("正在行全细节", text.contains("[▶️正在] 午后 12:30-14:00 午休小憩（店里二楼）"))
        assertTrue("未来行全细节", text.contains("[⏳未来·尚未发生] 下午 14:00-17:30 拉花赶单（咖啡店）"))
    }
}
