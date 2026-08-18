package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 未来约定见面（1:1 iOS `Models/MeetingAppointment.swift` @Model）——一条「用户 ↔ 角色」之间、
 * 未来某天线下见面的约定，是整个功能的**真理源**（识别层只产候选，过确认闸门才落成这里的一条真约定）。
 *
 * ## 为什么不用外键级联（与 [ScheduleEventEntity] 等不同，刻意照 [RedPacketRecordEntity] 范式）
 * [characterUuid] / [conversationUuid] **只按 ID 关联，不声明 ForeignKey CASCADE**：约定会派生「到点本地通知」，
 * 删角色 / 删会话时必须**先枚举它的 uuid 去撤通知、再删记录**（否则角色都删了还会到点弹「孤儿通知」）。
 * 若用级联，记录会被数据库静默删掉、来不及撤通知。清理接线见 MeetingAppointmentDao 的 uuidsForXxx / deleteForXxx。
 *
 * ## 与「确认卡消息快照」的分工（确认卡数据类在 Phase 5/6 建）
 * - 确认卡（MessageEntity.content 的 JSON）：插进聊天里那张卡的**自包含快照**，UI 渲染起点。
 * - 本表：**状态机 + 绝对见面时刻 + 生命周期时间戳**，是驱动等待期 / 到点 / 赴约 / 爽约的唯一依据。
 * - 二者通过 [uuid] 关联；本表的 [status] 是真相源，确认卡气泡实时观察它刷新按钮态。
 *
 * 不可变 Room 行 + `copy`（与 redpacket/gift/pet 一致）；状态流转走 MeetingAppointmentStore 纯函数 copy 后 @Update。
 * 各枚举列存 raw 字符串，见 [com.situ.aichat.data.model.MeetingStatus] 等（字面量由测试锁定一致性）。
 */
@Entity(
    tableName = "meeting_appointments",
    indices = [
        Index("characterUuid"),
        Index("conversationUuid"),
        Index("status"),
        Index("scheduledAt"),
    ],
)
data class MeetingAppointmentEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),

    /** 关联角色 uuid（[CharacterEntity.uuid]）。仅 ID 关联、无 FK，删角色手动清理。 */
    val characterUuid: String = "",
    /** 在哪个会话里约的（同角色可有多个会话）。赴约 / 倒数小条 / 爽约反应都要落回这个会话。 */
    val conversationUuid: String = "",

    // ── 状态机 ──
    /** 状态 raw（默认 proposed），见 [com.situ.aichat.data.model.MeetingStatus]。 */
    val status: String = "proposed",
    /** 谁发起 raw（默认 character），见 [com.situ.aichat.data.model.MeetingProposedBy]。 */
    val proposedBy: String = "character",
    /** 来源 raw（默认 extraction，溯源调参用），见 [com.situ.aichat.data.model.MeetingSource]。 */
    val source: String = "extraction",

    // ── 时间 ──
    /** 代码解析出的**绝对见面时刻**（epoch millis）。到点提醒 / 宽限期均以此为准。 */
    val scheduledAt: Long = 0L,
    /** 时间精度 raw（默认 exact），见 [com.situ.aichat.data.model.MeetingTimeGranularity]。 */
    val timeGranularity: String = "exact",
    /** 模型原话的时间说法（如「周末下午」），留痕 + 兜底解析依据。 */
    val rawWhenText: String = "",

    // ── 内容 ──
    /** 见面地点（可空串）。 */
    val location: String = "",
    /** 一起做什么（可空串）。 */
    val activity: String = "",
    /** 邀约台词（展示用，可空串）。 */
    val invitationText: String = "",
    /** ≤12 字、给用户看的隐晦暗示（卡片上，不剧透；可空串）。 */
    val tensionHint: String = "",
    /** 带进见面的小心事种子（**用户不可见**，赴约时喂给线下见面沉浸模式；可空串）。 */
    val hiddenTensionSeed: String = "",

    // ── 生命周期时间戳 ──
    /** 创建时刻（识别出候选 / 手动发起的瞬间）。 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 确认落定时刻（用户点「答应」 / 手动直接确认）；proposed 期为 null。 */
    val confirmedAt: Long? = null,
    /** 结局时刻（honored / missed / cancelled 落定时写入）；进行中为 null。 */
    val outcomeAt: Long? = null,

    // ── 赴约链接 + 防重排 ──
    /** 赴约后链到的线下见面 sessionId；未赴约为 null。 */
    val honoredSessionId: String? = null,
    /** 上次为它排到点通知的时刻，防重复排程；未排为 null。 */
    val lastReminderScheduledAt: Long? = null,
)
