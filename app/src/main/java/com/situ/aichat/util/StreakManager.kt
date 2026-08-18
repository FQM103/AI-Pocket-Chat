package com.situ.aichat.util

import com.situ.aichat.data.local.entity.CharacterEntity
import java.time.Instant
import java.time.ZoneId

/**
 * Port of iOS `Services/StreakManager.swift`. 火花系统：每天跟一个角色聊天算「续火花」，连续天数 +1；
 * 某天没聊则断、从头计数。`lastChatDate` 另供成长阶段判定使用（GrowthAnalysisCoordinator 的
 * daysSinceLastChat → fatigue 档）；原成长模块「久未联系提示」已删（前后置区审计 🟡-2·2026-07-13）。
 *
 * - 写路径 [recordChat]：用户发消息后调用，更新 streakCount / lastChatDate（同日去重）。
 * - 读路径 [checkStreak] / [getStreakCount]（P6.1a 补）：通知调度据火花状态调语气 / 选分支。
 */
object StreakManager {

    /**
     * 记录一次聊天（iOS 在用户发消息后调用）。返回更新后的角色副本；今天已聊过则原样返回（引用相等，
     * 调用方可据此跳过持久化）。
     * - 最后聊天是昨天 → 连续天数 +1
     * - 更早或从未 → 重置为 1
     */
    fun recordChat(
        character: CharacterEntity,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): CharacterEntity {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val newStreak: Int
        val last = character.lastChatDate
        if (last != null) {
            val lastDay = Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
            if (lastDay == today) return character  // 今天已聊过，不重复计数
            newStreak = if (lastDay == today.minusDays(1)) character.streakCount + 1 else 1
        } else {
            newStreak = 1
        }
        return character.copy(streakCount = newStreak, lastChatDate = now)
    }

    /**
     * 查询角色当前火花状态（对齐 iOS `checkStreak`）：
     * - 今天聊过 → [StreakStatus.Active]（days = 当前连续天数）
     * - 昨天聊过、今天还没 → [StreakStatus.NeedsChat]（days = 截至昨天的连续天数）
     * - 超过一天没聊或从未 → [StreakStatus.Broken]
     */
    fun checkStreak(
        character: CharacterEntity,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): StreakStatus = computeStreak(character.lastChatDate, character.streakCount, now, zone)

    /** 纯函数核心（internal 便于单测）：只依赖 lastChatDate / streakCount，不碰实体其余字段。 */
    internal fun computeStreak(
        lastChatDate: Long?,
        streakCount: Int,
        now: Long,
        zone: ZoneId,
    ): StreakStatus {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val last = lastChatDate ?: return StreakStatus.Broken
        val lastDay = Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
        return when (lastDay) {
            today -> StreakStatus.Active(streakCount)
            today.minusDays(1) -> StreakStatus.NeedsChat(streakCount)
            else -> StreakStatus.Broken
        }
    }

    /**
     * 火花状态的稳定标签（"active" / "needsChat" / "broken"）。逐字迁自 `DynamicNotificationContentService`
     * （主动通知真实感改造 C3；原件 C6a 删除）。通知调度据此选回退分支。
     */
    fun streakStatusLabel(status: StreakStatus): String = when (status) {
        is StreakStatus.Active -> "active"
        is StreakStatus.NeedsChat -> "needsChat"
        StreakStatus.Broken -> "broken"
    }

    /** 当前连续聊天天数（火花已断返回 0）。对齐 iOS `getStreakCount`。 */
    fun getStreakCount(
        character: CharacterEntity,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int = when (val status = checkStreak(character, now, zone)) {
        is StreakStatus.Active -> status.days
        is StreakStatus.NeedsChat -> status.days
        StreakStatus.Broken -> 0
    }
}

/** 火花状态（对齐 iOS `StreakStatus`）。 */
sealed interface StreakStatus {
    /** 今天已聊过，火花燃烧中。 */
    data class Active(val days: Int) : StreakStatus

    /** 今天还没聊，等待续上（days = 截至昨天的连续天数）。 */
    data class NeedsChat(val days: Int) : StreakStatus

    /** 火花已断（超过一天没聊，或从未聊过）。 */
    data object Broken : StreakStatus
}
