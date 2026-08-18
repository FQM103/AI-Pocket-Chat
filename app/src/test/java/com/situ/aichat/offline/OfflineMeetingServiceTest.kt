package com.situ.aichat.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 线下见面状态机纯函数测试（10.2c-3c）：见面时长 / 结束原因文案，**断言反推 iOS**
 * `finalizeOfflineMode`（ToolCalling.swift:418-445）的边界与映射，抓移植 bug（向零截断 / 小时分钟拼接 / 文案字节）。
 */
class OfflineMeetingServiceTest {

    private fun duration(seconds: Long) = OfflineMeetingService.durationText(0L, seconds * 1000L)

    @Test fun duration_under_one_minute_floors_to_special_text() {
        assertEquals("不到1分钟", duration(0))
        assertEquals("不到1分钟", duration(59)) // 0 分钟（向零截断，= iOS Int(秒/60)）
    }

    @Test fun duration_minutes_below_an_hour() {
        assertEquals("约1分钟", duration(60))
        assertEquals("约1分钟", duration(119)) // 1 分钟（截断）
        assertEquals("约59分钟", duration(59 * 60))
    }

    @Test fun duration_whole_hours_omit_minutes() {
        assertEquals("约1小时", duration(60 * 60)) // rem=0 → 省略分钟
        assertEquals("约2小时", duration(120 * 60))
    }

    @Test fun duration_hours_and_minutes() {
        assertEquals("约1小时30分钟", duration(90 * 60))
        assertEquals("约2小时5分钟", duration(125 * 60))
    }

    @Test fun reason_text_maps_each_exit_reason() {
        assertEquals("用户主动结束了这次见面", OfflineMeetingService.reasonText(OfflineMeetingService.ExitReason.USER_ENDED))
        assertEquals(
            "这次见面因为中断而结束（用户选择不继续）",
            OfflineMeetingService.reasonText(OfflineMeetingService.ExitReason.USER_ABORTED),
        )
        // AI_ENDED 是 iOS 死分支（保留不用），但文案仍须忠实。
        assertEquals("你们自然地结束了这次见面", OfflineMeetingService.reasonText(OfflineMeetingService.ExitReason.AI_ENDED))
    }
}
