package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import java.text.BreakIterator

/**
 * 聊天列表行的「日程状态」派生（13.5 chat-ui-11）。
 *
 * 1:1 iOS `ScheduleStatusProvider.currentStatus` + `CharacterDailySchedule.currentEvent(at:)`：
 * 在当天日程里取**当前进行中**的事件（`startTime <= now <= endTime`，闭区间；按 `sortOrder` 升序、
 * 同序再按 `startTime` 升序后取首个命中），状态串 = `"活动 心情emoji"`（trim 首尾空白）；
 * 无进行中事件、或状态串为空 → null（行内不显示日程状态）。
 *
 * 纯函数、无 IO，便于单测（断言从 iOS 真值反推）。仅在 `scheduleSystemEnabled` 时由 ViewModel 调用。
 */
internal object ChatListScheduleStatus {
    fun currentStatus(events: List<ScheduleEventEntity>, nowMillis: Long): String? {
        val current = events
            .sortedWith(compareBy({ it.sortOrder }, { it.startTime }))
            .firstOrNull { it.startTime <= nowMillis && nowMillis <= it.endTime }
            ?: return null
        return "${current.activity} ${current.moodEmoji}".trim().ifEmpty { null }
    }
}

/**
 * 聊天会话顶栏副标题的日程状态截断（P0-17）。1:1 iOS `ChatView+Navigation.subtitleText`：
 * `count > 8 ? prefix(8) + "…" : self`，**以字素簇（grapheme cluster）为单位**（Swift `.count`/`.prefix` 即字素簇）——
 * 状态串结尾常是多码点 emoji，按 UTF-16 长度截会把 emoji 截半，故用 [BreakIterator] 取字素簇边界。纯函数、单测覆盖。
 */
internal fun truncateScheduleSubtitle(status: String): String {
    val bi = BreakIterator.getCharacterInstance()
    bi.setText(status)
    val boundaries = ArrayList<Int>()
    boundaries.add(bi.first())
    var b = bi.next()
    while (b != BreakIterator.DONE) {
        boundaries.add(b)
        b = bi.next()
    }
    val clusterCount = boundaries.size - 1 // boundaries[i] = 第 i 个字素簇结束偏移；总数 = size-1
    return if (clusterCount > 8) status.substring(0, boundaries[8]) + "…" else status
}
