package com.situ.aichat.ui.contacts

import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity

/** 14 天纪事窗（锁定·图纸一 §3.2）：联系人第二行「最近纪事」只认此窗内事件。 */
internal const val RECENT_EVENT_WINDOW_MILLIS: Long = 14L * 24 * 60 * 60 * 1000 // 14 天

/**
 * 联系人第二行「最近纪事」的选取结果（图纸一 #5·§3.2）。
 *
 * 分层约束：本类型 + [pickRecentEvent] 只做「选取」不做「格式化」——文案格式化留 UI 层 stringResource
 * （见 ContactsScreen 第二行渲染）；不碰 DB、不碰 Context。
 *
 * 可见性偏差（图纸 §11 D-1）：§3.2 标「全部 internal」，但 §3.1 令公开的 `ContactsViewModel.Row`
 * 持 `recentEvent: RecentEvent?` 字段、且 `rows` 是 public StateFlow → public 成员不得暴露 internal 类型
 * （编译 `'public' function exposes its 'internal' parameter type`）。取最小改动：**仅本结果类型 public**，
 * 选取函数 [pickRecentEvent] 与常量 [RECENT_EVENT_WINDOW_MILLIS] 保持 internal（不出现在任何 public 签名）。
 */
sealed interface RecentEvent {
    val atMillis: Long

    /** 关系里程碑：UI 渲染「{相对天} · 成为{name}」。 */
    data class Milestone(val name: String, override val atMillis: Long) : RecentEvent

    /** 见面：UI 按 activity/location 空链三级降级（activity → location → plain）。 */
    data class Meeting(val activity: String, val location: String, override val atMillis: Long) : RecentEvent
}

/**
 * 从「该角色最新里程碑 + 最新见面行」选出 14 天窗内的最近纪事（图纸 §3.2 算法·锁定）：
 * 1. 里程碑候选：`reason != "初始设定"`（MilestoneEntity.kt:29 播种字面量·新建角色的初始行不算纪事）
 *    且 `now - establishedDate ∈ [0, 窗]`（未来时间戳=脏数据，不算）。
 * 2. 见面候选：`kindRaw == "meeting"` 且 `now - startedAtMillis ∈ [0, 窗]`（legacy 行 startedAt=0 被窗
 *    天然排除·双保险）。
 * 3. 两候选都在 → 取 `atMillis` 更大者（相等取见面——信息更具体）；只一个 → 取它；都无 → null。
 *
 * name / activity / location 各自 `.trim()`。
 */
internal fun pickRecentEvent(
    latestMilestone: MilestoneEntity?,
    latestMeeting: OfflineMeetingMemoryEntity?,
    nowMillis: Long,
): RecentEvent? {
    val milestone = latestMilestone
        ?.takeIf { it.reason != "初始设定" && nowMillis - it.establishedDate in 0..RECENT_EVENT_WINDOW_MILLIS }
        ?.let { RecentEvent.Milestone(it.relationshipName.trim(), it.establishedDate) }
    val meeting = latestMeeting
        ?.takeIf { it.kindRaw == "meeting" && nowMillis - it.startedAtMillis in 0..RECENT_EVENT_WINDOW_MILLIS }
        ?.let { RecentEvent.Meeting(it.activity.trim(), it.location.trim(), it.startedAtMillis) }
    return when {
        milestone != null && meeting != null ->
            if (meeting.atMillis >= milestone.atMillis) meeting else milestone
        else -> milestone ?: meeting
    }
}
