package com.situ.aichat.data.model

/**
 * 候选约定（1:1 iOS `Models/MeetingCandidate.swift`）——识别层（抽取扫描 / 工具快路 / 弱模型兜底 / 用户手动）
 * 统一产出的中间产物。**不是真理源、不持久化**：只有过了确认闸门才写成
 * [com.situ.aichat.data.local.entity.MeetingAppointmentEntity]。
 *
 * 识别与真理源解耦（见 FUTURE_MEETUP_PORT_PLAN.md §2）：候选先经时间解析 + 查重 + 确认闸门，再决定是否落库。
 * 不可变值类型，便于在后台识别流程里安全传递。
 */
data class MeetingCandidate(
    /** 这条候选要做什么（新建 / 改期 / 取消 / 确认已有 / 判定为无）。 */
    val intent: MeetingCandidateIntent = MeetingCandidateIntent.NEW,
    /** reschedule / cancel / confirm 时指向的已有约定 uuid；new / none 为 null。 */
    val targetAppointmentUuid: String? = null,
    /** LLM 给的具体时间字符串（ISO8601 或常见格式），时间解析主路径；可空。 */
    val isoDateTime: String? = null,
    /** 模型原话的时间说法（如「周六下午」），兜底解析依据 + 留痕。 */
    val rawWhen: String = "",
    /** 谁发起。 */
    val proposedBy: MeetingProposedBy = MeetingProposedBy.CHARACTER,
    /** 识别来源（溯源 / 调参用）。 */
    val source: MeetingSource = MeetingSource.EXTRACTION,
    val location: String = "",
    val activity: String = "",
    val invitationText: String = "",
    val tensionHint: String = "",
    val hiddenTensionSeed: String = "",
    /** 把握度（确认闸门据此决定谨慎程度）。 */
    val confidence: MeetingConfidence = MeetingConfidence.MEDIUM,
)

/** 候选意图：这段对话是要建 / 改 / 取消 / 确认约定，还是根本没有约定。 */
enum class MeetingCandidateIntent(val raw: String) {
    NEW("new"),
    RESCHEDULE("reschedule"),
    CANCEL("cancel"),
    CONFIRM("confirm"),
    NONE("none");

    companion object {
        private val byRaw = entries.associateBy { it.raw }

        /** 从 raw 还原（容忍大小写 / 首尾空白）；未知值保守回退 [NONE]（识别侧未知 = 当作没有约定，宁漏勿错）。 */
        fun fromRaw(raw: String): MeetingCandidateIntent = byRaw[raw.trim().lowercase()] ?: NONE
    }
}

/** 候选把握度。低把握度在确认闸门处更谨慎对待。 */
enum class MeetingConfidence(val raw: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    companion object {
        private val byRaw = entries.associateBy { it.raw }

        /** 从 raw 还原（容忍大小写 / 首尾空白）；未知值回退 [MEDIUM]。 */
        fun fromRaw(raw: String): MeetingConfidence = byRaw[raw.trim().lowercase()] ?: MEDIUM
    }
}
