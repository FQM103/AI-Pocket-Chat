package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「我们的约定」承诺账本行（记忆改造一期·部件①·图纸 §3.1）：两人之间正式定下的约定，作为永不模糊的
 * 一等公民——进行中的全量注入、最近了结的带状态回顾。由对账（[com.situ.aichat.promise.PromiseReconciliation]）
 * 与见面便车 / 历史回填共同写入，落库业务在 [com.situ.aichat.promise.PromiseLedgerService]。
 *
 * 无外键（照 [OpenLoopEntity] / `offline_meeting_memories` 先例）——级联清理**手动**做：删角色见
 * [com.situ.aichat.data.repository.CharacterRepository.delete]。**会话删除不级联删约定**——约定是角色级资产，
 * [conversationUuid] 仅溯源（图纸 §3.1·E17）。[statusRaw] ∈ [PromiseStatus]；[sourceRaw] ∈ [PromiseSource]。
 */
@Entity(
    tableName = "promises",
    indices = [Index("characterUuid")],
)
data class PromiseEntity(
    @PrimaryKey val uuid: String,
    /** 归属角色（索引·注入 / 角色级联删按此取）。 */
    val characterUuid: String,
    /** 溯源会话（会话删除**不**级联删约定——约定是角色级资产）。 */
    val conversationUuid: String = "",
    /** 约定内容（第三人称一句话·≤40 字提取约束）。 */
    val content: String,
    /** [PromiseStatus]：open | fulfilled | cancelled（默认 open）。 */
    val statusRaw: String = PromiseStatus.OPEN,
    /** 约定日期（epoch millis·可空=无明确日期；纯日期按 09:00 落点·惦记桥仅对未来日期建 loop）。 */
    val dueAtMillis: Long? = null,
    /** [PromiseSource]：chat（对账提取）| meeting（见面便车）| meeting_backfill（历史回填）。 */
    val sourceRaw: String = PromiseSource.CHAT,
    /** 见面来源 session（三期 UI 溯源用）。 */
    val sourceSessionId: String = "",
    /** 惦记桥：关联的 open_loops 行（仅带未来日期时桥接·可空）。 */
    val openLoopUuid: String? = null,
    /** 了结时间（epoch millis·可空）。 */
    val resolvedAtMillis: Long? = null,
    /**
     * 判变更时引用的素材原话（审计 + 三期 UI 展示）。
     * 非空=LLM 对账判定（闸二保证 ≥6 字）；空=用户手动标记（resolveManually 恒不写）——三期 UI 判定方式推断依赖此闭环不变量。
     */
    val resolutionEvidence: String = "",
    /** 审计：首次写入。 */
    val createdAtMillis: Long,
    /** 审计：最后更新。 */
    val updatedAtMillis: Long,
)

/** [PromiseEntity.statusRaw] 取值。 */
object PromiseStatus {
    const val OPEN = "open"
    const val FULFILLED = "fulfilled"
    const val CANCELLED = "cancelled"
}

/** [PromiseEntity.sourceRaw] 取值。 */
object PromiseSource {
    const val CHAT = "chat"                     // 对账提取
    const val MEETING = "meeting"               // 见面便车
    const val MEETING_BACKFILL = "meeting_backfill" // 历史回填
}
