package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ChatListScheduleStatus.currentStatus] 单测——断言反推 iOS `ScheduleStatusProvider.currentStatus` +
 * `CharacterDailySchedule.currentEvent(at:)`：当前进行中事件（startTime<=now<=endTime 闭区间，按
 * sortOrder→startTime 升序取首）的 "活动 心情emoji"（trim）；无命中或空串 → null。
 */
class ChatListScheduleStatusTest {

    private fun event(
        start: Long,
        end: Long,
        activity: String = "工作中",
        emoji: String = "💼",
        sortOrder: Int = 0,
        uuid: String = "e-$start-$end",
    ) = ScheduleEventEntity(
        uuid = uuid,
        scheduleUuid = "s1",
        startTime = start,
        endTime = end,
        activity = activity,
        moodEmoji = emoji,
        sortOrder = sortOrder,
    )

    private fun status(events: List<ScheduleEventEntity>, now: Long) =
        ChatListScheduleStatus.currentStatus(events, now)

    @Test fun activeEvent_returnsActivityAndEmoji() {
        assertEquals("工作中 💼", status(listOf(event(100, 200)), now = 150))
    }

    @Test fun startBoundary_inclusive() {
        assertEquals("工作中 💼", status(listOf(event(100, 200)), now = 100))
    }

    @Test fun endBoundary_inclusive() {
        assertEquals("工作中 💼", status(listOf(event(100, 200)), now = 200))
    }

    @Test fun beforeAllEvents_null() {
        assertNull(status(listOf(event(100, 200)), now = 99))
    }

    @Test fun afterAllEvents_null() {
        assertNull(status(listOf(event(100, 200)), now = 201))
    }

    @Test fun noEvents_null() {
        assertNull(status(emptyList(), now = 150))
    }

    @Test fun gapBetweenEvents_null() {
        val events = listOf(event(100, 200, activity = "上班"), event(300, 400, activity = "下班"))
        assertNull(status(events, now = 250))
    }

    @Test fun overlapping_lowerSortOrderWins() {
        // 两个都在进行中，sortOrder 小的优先（= iOS sortedEvents 先按 sortOrder 升序）。
        val a = event(100, 300, activity = "会议", sortOrder = 1, uuid = "a")
        val b = event(100, 300, activity = "午休", sortOrder = 0, uuid = "b")
        assertEquals("午休 💼", status(listOf(a, b), now = 150))
    }

    @Test fun overlapping_sameSortOrder_earlierStartWins() {
        // sortOrder 相同 → 再按 startTime 升序取首（输入乱序也应稳定）。
        val later = event(120, 300, activity = "晚的", sortOrder = 0, uuid = "later")
        val earlier = event(100, 300, activity = "早的", sortOrder = 0, uuid = "earlier")
        assertEquals("早的 💼", status(listOf(later, earlier), now = 150))
    }

    @Test fun activityOnly_noEmoji_trimsTrailingSpace() {
        assertEquals("工作中", status(listOf(event(100, 200, emoji = "")), now = 150))
    }

    @Test fun emojiOnly_noActivity_trimsLeadingSpace() {
        assertEquals("💼", status(listOf(event(100, 200, activity = "")), now = 150))
    }

    @Test fun bothEmpty_null() {
        assertNull(status(listOf(event(100, 200, activity = "", emoji = "")), now = 150))
    }
}
