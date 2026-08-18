package com.situ.aichat.offline

/**
 * 线下见面「重进分档」纯决策（D3·2026-07-07 拍板，T1）。
 *
 * 背景缺陷：见面期间线下叙事不回写会话预览，`conversation.lastMessageRole` 恒为入场 hint 的 "user"——
 * 未答恢复（autoRecoverUnansweredMessage）据此把**每次进屏**都当「有消息没回」，无条件自动推进一拍，
 * 观感=角色自说自话。本策略改按【线下 session 实际最后一条】+ 离开时长分档：
 *
 * - 最后一条非 assistant（用户消息 / 隐藏 hint 未获回答）→ [Action.RECOVER_UNANSWERED]：真中断，照旧恢复（任意时长）；
 * - 已获回答 + 离开 < 3min → [Action.NONE]：临时看了眼别的，剧情原地等人；
 * - 已获回答 + 离开 3–10min → [Action.NUDGE]：插「归来」隐藏提示让角色自然接一拍（只推进一步）；
 * - 已获回答 + 离开 > 10min → [Action.NONE]：交既有恢复弹窗（[OfflineStateGuard.shouldShowRecoveryPrompt]
 *   同一 10min 口径），用户选「继续见面」再由弹窗路径带时长衔接。
 */
object OfflineReturnPolicy {

    /** 离开不足此值 → 无感继续（分钟级离开不打扰剧情）。 */
    const val NUDGE_MIN_AWAY_MS: Long = 3 * 60_000L

    /** 离开超过此值 → 恢复弹窗接管（与 OfflineStateGuard 的 >10min 口径一致）。 */
    const val RECOVERY_PROMPT_AWAY_MS: Long = 10 * 60_000L

    /** 离开超过此值 → 恢复弹窗文案引导「结束见面」（避免凌晨还坐在便利店的失真）。 */
    const val LONG_ABSENCE_MS: Long = 3 * 60 * 60_000L

    enum class Action {
        /** 什么都不做。 */
        NONE,

        /** 最后一条未获回答 → 走既有未答恢复（重新请求回合）。 */
        RECOVER_UNANSWERED,

        /** 插「归来」隐藏提示 + 触发一拍轻推进。 */
        NUDGE,
    }

    /**
     * 重进见面时的动作判定。
     * @param lastOfflineRole 线下 session 最后一条消息的 role（null=尚无消息，交开场/恢复链，不掺和）。
     * @param awayMs 距最后一条线下消息的毫秒数。
     */
    fun decide(lastOfflineRole: String?, awayMs: Long): Action = when {
        lastOfflineRole == null -> Action.NONE
        lastOfflineRole != "assistant" -> Action.RECOVER_UNANSWERED
        awayMs < NUDGE_MIN_AWAY_MS -> Action.NONE
        awayMs > RECOVERY_PROMPT_AWAY_MS -> Action.NONE
        else -> Action.NUDGE
    }

    /** 是否超长离开（恢复弹窗文案引导结束）。 */
    fun isLongAbsence(awayMs: Long): Boolean = awayMs > LONG_ABSENCE_MS

    /** 归来 hint 用的「约 X 分钟」——向下取整、至少报 3 分钟（NUDGE 档下限）。 */
    fun awayMinutes(awayMs: Long): Long = (awayMs / 60_000L).coerceAtLeast(3L)
}
