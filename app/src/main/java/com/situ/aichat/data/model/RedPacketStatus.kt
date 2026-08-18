package com.situ.aichat.data.model

/**
 * 红包生命周期状态（1:1 iOS `Models/RedPacketRecord.RedPacketStatus`，rawValue 字面一致）。
 *
 * ```
 * pending(托管中,钱锁在托管账户)
 *   ├── 用户拆 / 角色 LLM 决定收 → accepted(已领取,钱到接收方)
 *   ├── 角色 LLM 决定拒收         → rejected(已退回,钱回发送方)
 *   └── 用户 24h 未拆              → expired(已退回,钱回发送方)
 * ```
 * UI 把 `rejected`/`expired` 统一显示为「已退回」，仅系统消息文案区分二者。
 */
enum class RedPacketStatus(val raw: String) {
    /** 托管中 —— 钱已从发送方扣走，进入托管账户，等接收方决定。 */
    PENDING("pending"),
    /** 已领取 —— 接收方拆开/收下，钱到账。 */
    ACCEPTED("accepted"),
    /** 已退回（角色主动拒收路径）。 */
    REJECTED("rejected"),
    /** 已退回（用户 24h 超时路径）。 */
    EXPIRED("expired");

    /** 是否「已退回」（UI + 系统消息都会合并显示为「已退回」，1:1 iOS `isReturned`）。 */
    val isReturned: Boolean get() = this == REJECTED || this == EXPIRED

    /** 是否终态（不再变动，1:1 iOS `isTerminal`）。 */
    val isTerminal: Boolean get() = this != PENDING

    companion object {
        private val byRaw = entries.associateBy { it.raw }

        /** 从 rawValue 还原；未知值保守回退 [PENDING]（1:1 iOS `RedPacketStatus(rawValue:) ?? .pending`）。 */
        fun fromRaw(raw: String): RedPacketStatus = byRaw[raw] ?: PENDING
    }
}
