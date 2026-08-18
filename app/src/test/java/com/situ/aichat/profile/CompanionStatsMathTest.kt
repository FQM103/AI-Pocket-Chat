package com.situ.aichat.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CompanionStatsMath] 单测——断言反推 iOS `CompanionStats`：
 * 陪伴天数 max(1, days+1)（创建当天=第 1 天）；记忆条目数=memorySummary 按行拆分去空白计数。
 */
class CompanionStatsMathTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    @Test fun companionDays_creationDayCountsAsDayOne() {
        assertEquals(1, CompanionStatsMath.companionDays(now, now))
        assertEquals(1, CompanionStatsMath.companionDays(now - day + 1, now)) // 不满一天
        assertEquals(2, CompanionStatsMath.companionDays(now - day, now))     // 整一天
        assertEquals(8, CompanionStatsMath.companionDays(now - 7 * day, now)) // 一周
    }

    @Test fun companionDays_neverBelowOne() {
        assertEquals(1, CompanionStatsMath.companionDays(now + day, now)) // 创建时间在未来（时钟偏移）也保底 1
    }

    @Test fun memoryEntryCount_countsNonBlankLines() {
        assertEquals(0, CompanionStatsMath.memoryEntryCount(""))
        assertEquals(0, CompanionStatsMath.memoryEntryCount("   \n  \n"))
        assertEquals(3, CompanionStatsMath.memoryEntryCount("a\nb\nc"))
        assertEquals(2, CompanionStatsMath.memoryEntryCount("a\n\n  \nb")) // 空白行不计
        assertEquals(2, CompanionStatsMath.memoryEntryCount("a\r\nb"))     // CRLF 算一个分隔
    }
}
