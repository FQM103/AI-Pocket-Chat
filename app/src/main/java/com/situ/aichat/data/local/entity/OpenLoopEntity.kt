package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「心里惦记的事」结构化行（活人感一期 P2·图纸 §3.2）：AI 角色记住的、之后值得像朋友一样主动问起的事
 * （答应对方的事 / 对方提到即将发生或未有结果的事 / 悬而未决的开放话题）。
 *
 * 无外键（照 `offline_meeting_memories` 先例）——级联清理**手动**做：删角色见
 * [com.situ.aichat.data.repository.CharacterRepository.delete]，删会话见
 * [com.situ.aichat.data.repository.ConversationDeletionService]（图纸 §3.2·E11）。
 * [typeRaw] ∈ [OpenLoopType]；[statusRaw] ∈ [OpenLoopStatus]。
 */
@Entity(
    tableName = "open_loops",
    indices = [Index("conversationUuid"), Index("characterUuid")],
)
data class OpenLoopEntity(
    @PrimaryKey val uuid: String,
    /** 归属会话（索引·会话级联删按此清）。 */
    val conversationUuid: String,
    /** 归属角色（索引·注入 / 角色级联删按此取）。 */
    val characterUuid: String,
    /** 一句话概括（第三人称·≤30 字）。 */
    val content: String,
    /** [OpenLoopType]：promise_char | user_event | open_topic。 */
    val typeRaw: String,
    /** 到期时间（epoch millis·可空=无明确日期，仅对话内回连、不排到期 worker）。 */
    val dueAt: Long? = null,
    /** [OpenLoopStatus]：open | resolved | expired | revisited（默认 open）。 */
    val statusRaw: String = OpenLoopStatus.OPEN,
    /** 首次落库时间（epoch millis·14 天过期清理基点）。 */
    val createdAt: Long,
    /** 解决 / 过期时间（epoch millis·可空）。 */
    val resolvedAt: Long? = null,
)

/** [OpenLoopEntity.typeRaw] 取值（扫描解析 ↔ 落库同源；未知值归 [OPEN_TOPIC]）。 */
object OpenLoopType {
    const val PROMISE_CHAR = "promise_char"  // 角色答应过对方的事
    const val USER_EVENT = "user_event"      // 用户提到的即将发生 / 未有结果的事
    const val OPEN_TOPIC = "open_topic"      // 悬而未决的开放话题
    val ALL = setOf(PROMISE_CHAR, USER_EVENT, OPEN_TOPIC)
}

/** [OpenLoopEntity.statusRaw] 取值。 */
object OpenLoopStatus {
    const val OPEN = "open"
    const val RESOLVED = "resolved"
    const val EXPIRED = "expired"

    /**
     * 长线回访终态（活人感二期 M2·图纸 §3.2）：resolved 的「惦记的事」在 7–30 天后被角色回头问过一次进展后，
     * 置此终态——不再参与候选 / 注入 / 回访任何路径（一次为限·E12）。**TEXT 列新值·无 Room 迁移**（图纸 §9 零迁移是设计核心）。
     */
    const val REVISITED = "revisited"
}
