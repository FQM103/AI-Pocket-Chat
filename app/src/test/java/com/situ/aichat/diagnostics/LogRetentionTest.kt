package com.situ.aichat.diagnostics

import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日志容量轮转算术单测（批 D·纯函数）。断言从 iOS `LogService.trimOldLogs` + `fetchRetentionCount` 反推：
 * overflow=max(0,total-retention)；分界偏移=overflow-1；retention >0 取原值否则回退 100。
 */
class LogRetentionTest {

    @Test
    fun overflowIsTotalMinusRetentionFlooredAtZero() {
        assertEquals(50, LogRetention.overflow(total = 150, retention = 100))
        assertEquals(1, LogRetention.overflow(total = 101, retention = 100))
        assertEquals(0, LogRetention.overflow(total = 100, retention = 100))   // 刚好满，不删
        assertEquals(0, LogRetention.overflow(total = 30, retention = 100))    // 未超，不删
        assertEquals(0, LogRetention.overflow(total = 0, retention = 100))
    }

    @Test
    fun cutoffOffsetIsOverflowMinusOne() {
        assertEquals(49, LogRetention.cutoffOffset(50))
        assertEquals(0, LogRetention.cutoffOffset(1))
    }

    @Test
    fun sanitizedRetentionKeepsPositiveElseFallsBackTo100() {
        // 默认 = 100
        assertEquals(100, AppSettings().sanitizedLogRetentionCount)
        // 手填可超滑杆上限 500（1:1 iOS）
        assertEquals(250, AppSettings(logRetentionCount = 250).sanitizedLogRetentionCount)
        assertEquals(1, AppSettings(logRetentionCount = 1).sanitizedLogRetentionCount)
        assertEquals(500, AppSettings(logRetentionCount = 500).sanitizedLogRetentionCount)
        // 0 / 负数 → 回退默认
        assertEquals(100, AppSettings(logRetentionCount = 0).sanitizedLogRetentionCount)
        assertEquals(100, AppSettings(logRetentionCount = -5).sanitizedLogRetentionCount)
    }

    /** 端到端：sanitize→overflow→cutoffOffset 串起来（retention=100，total=150 → 删 50 条，分界=升序第 49）。 */
    @Test
    fun endToEndRotationArithmetic() {
        val retention = AppSettings(logRetentionCount = 100).sanitizedLogRetentionCount
        val overflow = LogRetention.overflow(total = 150, retention = retention)
        assertEquals(50, overflow)
        assertEquals(49, LogRetention.cutoffOffset(overflow))
    }
}
