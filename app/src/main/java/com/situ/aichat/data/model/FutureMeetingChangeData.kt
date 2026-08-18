package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 未来约定见面「变更确认卡」消息快照（安卓原创·iOS 直接 mutate 无此卡）。识别到 AI 想**改期 / 取消一个已确认约定**时插，
 * 用户点头才动真理源（决策①·2026-06-25·杜绝 AI 误判静默改动已确认约定）。
 *
 * 与确认卡 [FutureMeetingProposalData] 的关键差异：目标约定**全程保持 confirmed**，直到用户接受变更。
 * 故卡态由本快照的 [responded] 驱动（**不**观察约定 status——status 不变无法区分 pending/kept）。
 * 属结构化卡（[MessageKind.isStructuredCard]）：**绝不把原文 JSON 喂 LLM / 复制给用户**，LLM 侧走 [llmRepresentation]。
 */
@Serializable
data class FutureMeetingChangeData(
    val type: String = TYPE,
    /** 目标（已确认）约定 uuid。 */
    val appointmentUuid: String,
    /** [KIND_RESCHEDULE] 改期 / [KIND_CANCEL] 取消。 */
    val changeKind: String,
    /** 原约定时间人话（两种变更都展示，给用户对照）。 */
    val oldWhenDisplay: String? = null,
    /** 拟改到的新时间人话（仅改期）。 */
    val newWhenDisplay: String? = null,
    /** 接受改期时应用的新绝对时刻（仅改期）。 */
    val newScheduledAtMillis: Long? = null,
    /** 接受改期时应用的新精度 raw（仅改期·[MeetingTimeGranularity] raw）。 */
    val newGranularity: String? = null,
    val location: String? = null,
    val activity: String? = null,
    /** AI 提出变更的原话（楷体引号展示·给「为什么」）。 */
    val reason: String? = null,
    /** null = 待确认（带按钮）；[RESPONDED_APPLIED] = 已应用变更回执；[RESPONDED_KEPT] = 保留原约定回执。 */
    val responded: String? = null,
) {
    val isReschedule: Boolean get() = changeKind == KIND_RESCHEDULE
    val isCancel: Boolean get() = changeKind == KIND_CANCEL

    /** 给 LLM 的脱敏表示（结构化卡绝不喂原文 JSON；收口走此处）。只露「请求了什么变更」，不露内部 uuid。 */
    fun llmRepresentation(userName: String = "用户"): String = when (changeKind) {
        KIND_RESCHEDULE -> {
            val from = oldWhenDisplay?.takeIf { it.isNotBlank() }
            val to = newWhenDisplay?.takeIf { it.isNotBlank() }
            when {
                from != null && to != null -> "[系统记录：和${userName}确认是否把约定从 $from 改到 $to]"
                to != null -> "[系统记录：和${userName}确认是否把约定改到 $to]"
                else -> "[系统记录：和${userName}确认是否改期约定]"
            }
        }
        KIND_CANCEL -> {
            val w = oldWhenDisplay?.takeIf { it.isNotBlank() }
            if (w != null) "[系统记录：和${userName}确认是否取消 $w 的约定]" else "[系统记录：和${userName}确认是否取消约定]"
        }
        else -> "[系统记录：和${userName}确认约定变更]"
    }

    companion object {
        const val TYPE = "future_meeting_change"
        const val KIND_RESCHEDULE = "reschedule"
        const val KIND_CANCEL = "cancel"
        const val RESPONDED_APPLIED = "applied"
        const val RESPONDED_KEPT = "kept"
    }
}

/** [FutureMeetingChangeData] 的 JSON 编解码（仿 [FutureMeetingProposalJson]）。 */
object FutureMeetingChangeJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(data: FutureMeetingChangeData): String =
        json.encodeToString(FutureMeetingChangeData.serializer(), data)

    /** 解析；非本类型 / 损坏 → null。 */
    fun parse(content: String): FutureMeetingChangeData? {
        val data = runCatching { json.decodeFromString(FutureMeetingChangeData.serializer(), content) }.getOrNull()
            ?: return null
        return if (data.type == FutureMeetingChangeData.TYPE) data else null
    }
}
