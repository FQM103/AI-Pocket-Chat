package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 红包记录（1:1 iOS `Models/RedPacketRecord` @Model）—— 承载整个生命周期的真实数据（金额/状态/时间）。
 *
 * ## 为什么需要这张表（而不只是消息卡 JSON）
 * 红包走「托管账户」模型：发送瞬间从发送方扣走、钱锁在本记录的 `status=pending`，直到接收方收/拒，或 24h 过期才
 * 二次结算（钱到接收方 / 退回发送方）。需要 4 态有限状态机 + 可扫描的 pending 集合（24h 过期 + 22h 预警）。
 *
 * ## 与 [com.situ.aichat.data.model.RedPacketData] 消息卡快照的分工
 * - `RedPacketData`（Message.content JSON）：发送瞬间的**自包含快照**，UI 渲染起点。
 * - 本表：**状态机 + 真实金额 + 时间戳**，通过 [messageUuid]/[uuid] 双向关联。
 * - [amount] 是**真相源**，永远只在这里 + 快照里持久化，**永不进 LLM**（拆开后的感知走 PromptBuilder 独立段）。
 *
 * 不可变 Room 行 + `copy`（与 gift/pet/diary 一致）；终态通过 RedPacketService 纯函数 copy 后 @Update 写回。
 * [senderType]/[receiverType] 存 [com.situ.aichat.data.model.WalletOwnerType] raw（"user"/"character"）。
 */
@Entity(
    tableName = "red_packet_records",
    indices = [
        Index("messageUuid"),
        Index("conversationUuid"),
        Index("status"),
        Index("receiverCharacterUUID"),
    ],
)
data class RedPacketRecordEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    /** 关联的 [com.situ.aichat.data.local.entity.MessageEntity.messageUUID]（那条 RED_PACKET 消息）。 */
    val messageUuid: String = "",
    /** 关联的会话 uuid（按会话查询 + 系统事件消息插回此会话）。 */
    val conversationUuid: String = "",

    // ── 发送方 ──
    /** 发送方类型 raw（"user"/"character"，默认 user，对齐 iOS `senderTypeRaw`）。 */
    val senderType: String = "user",
    /** 发送方为角色时存角色 uuid；用户发时空串。 */
    val senderCharacterUUID: String = "",

    // ── 接收方 ──
    /** 接收方类型 raw（"user"/"character"，默认 character，对齐 iOS `receiverTypeRaw`）。 */
    val receiverType: String = "character",
    /** 接收方为角色时存角色 uuid；用户收时空串。 */
    val receiverCharacterUUID: String = "",

    // ── 红包本体（快照） ──
    /** 金额（金币）。**真相源**，永不进 LLM。 */
    val amount: Int = 0,
    /** 祝福文字（≤80 字，RedPacketService.cappedBlessing 截断）。 */
    val blessingText: String = "",
    /** 节日 id（[com.situ.aichat.gift.FestivalCalendar] id）；null = 日常红包（非节日/用户自主发）。 */
    val festivalId: String? = null,

    // ── 状态机 ──
    /** 状态 raw（默认 pending，对齐 iOS `statusRaw`）。 */
    val status: String = "pending",
    /** 创建时间（发送瞬间）。 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 过期时间（createdAt + 24h）。24h 过期扫描以此为准。 */
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_EXPIRATION_MS,
    /** 终态落地时间（accepted/rejected/expired 时写入）；pending 时为 null。 */
    val resolvedAt: Long? = null,

    // ── Sub D 字段 ──
    /** 角色 LLM 拒收时的理由（≤30 字；空串=无/未拒收）。 */
    val rejectionReason: String = "",
    /** 是否已推过「即将过期」预警通知（防 22h 提醒重复推，9.3b 扫描用）。 */
    val notifiedExpiringSoon: Boolean = false,
) {
    companion object {
        /** 默认过期窗口：24h（1:1 iOS `expiresAt = createdAt + 86400`）。 */
        const val DEFAULT_EXPIRATION_MS = 24L * 60 * 60 * 1000
    }
}
