package com.situ.aichat.profile

import kotlin.math.max

/**
 * 陪伴档案统计（资料页统计条）。1:1 iOS `Models/CompanionStats.swift`：
 * 全部从数据库实时聚合，不经大模型。
 *
 * - [companionDays] 陪伴天数（创建当天算第 1 天）
 * - [messageCount] 消息数（排除 system 角色 + 空内容）
 * - [characterCount] 字数（每会话最近 100 条内容长度求和，与 iOS 同口径）
 * - [memoryEntryCount] 记忆条目数（memorySummary 按行拆分非空计数）
 * - [offlineMeetingCount] 线下见面次数（去重 session）
 */
data class CompanionStats(
    val companionDays: Int,
    val messageCount: Int,
    val characterCount: Int,
    val memoryEntryCount: Int,
    val offlineMeetingCount: Int,
)

/** 可独立单测的纯计算（断言从 iOS 真实数值反推）。 */
object CompanionStatsMath {

    private const val MILLIS_PER_DAY = 86_400_000L

    /** 陪伴天数：从创建当天算起，创建当天算第 1 天（1:1 iOS `max(1, days + 1)`）。 */
    fun companionDays(creationMillis: Long, nowMillis: Long): Int {
        val days = ((nowMillis - creationMillis) / MILLIS_PER_DAY).toInt()
        return max(1, days + 1)
    }

    /** 记忆条目数：memorySummary 按换行拆分、去空白行后计数（1:1 iOS split-trim-filter）。 */
    fun memoryEntryCount(memorySummary: String): Int =
        memorySummary.split('\n', '\r').count { it.trim().isNotEmpty() }
}
