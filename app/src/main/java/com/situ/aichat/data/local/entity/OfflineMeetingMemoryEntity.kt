package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 线下见面「回忆」结构化行（梦剧场 B 部·契约 §B2 决议 B-1 / 图纸 §3.2）：每次见面一行，取代旧
 * `CharacterEntity.offlineMeetingMemorySummary` 大字段（该字段冻结为只读兜底 + 懒播种来源）。
 *
 * 注入端从行**渲染出与今天逐字节一致的**【见面 · 】格式（[com.situ.aichat.offline.OfflineMeetingMemoryRenderer]），
 * 对 LLM 与 [com.situ.aichat.prompt.DirtyMessageDetector] 零变化。[summary] **不含**【见面 · 】标题行（标题由渲染器加）。
 */
@Entity(
    tableName = "offline_meeting_memories",
    indices = [Index("characterUuid"), Index("sessionId")],
)
data class OfflineMeetingMemoryEntity(
    @PrimaryKey val uuid: String,
    /** 归属角色（索引）。 */
    val characterUuid: String,
    /** 归属会话。 */
    val conversationUuid: String = "",
    /** 见面 session（非唯一索引）；legacy 行为 ""。 */
    val sessionId: String = "",
    /** "meeting"（结构化见面）/ "legacy"（旧 blob 合并行原样保存）。 */
    val kindRaw: String = "meeting",
    /** 精确起（legacy 行 = 0·排最前）。 */
    val startedAtMillis: Long,
    /** 精确止（legacy 行 = 0）。 */
    val endedAtMillis: Long = 0,
    /** 地点（硬事实·来自 marker payload）。 */
    val location: String = "",
    /** 活动（硬事实）。 */
    val activity: String = "",
    /** warm/sweet/melancholic/awkward/neutral/""。 */
    val moodRaw: String = "",
    /** 谁发起（未知 null）。 */
    val initiatedByUser: Boolean? = null,
    /** 对话轮数。 */
    val messageCount: Int = 0,
    /** 摘要正文（**不含**【见面 · 】标题行）。 */
    val summary: String = "",
    /** 亮点 JSON 字符串数组（≤3 条·[com.situ.aichat.util.StringListJson]）。 */
    val highlightsJson: String = "[]",
    /** 约定 JSON 字符串数组（≤3 条）。 */
    val promisesJson: String = "[]",
    /** llm / fallback / legacy / manual。 */
    val sourceRaw: String = "llm",
    /** 审计：首次写入。 */
    val createdAtMillis: Long,
    /** 审计：最后更新。 */
    val updatedAtMillis: Long,
    /**
     * 降级前消化标记（记忆改造一期·部件③·图纸 §3.5-A）：非 null = 该见面档案已由消化作业熔入长期记忆摘要，
     * 不再重复消化；null = 未消化。历史存量旧行恒 NULL，被同一收集谓词逐班次自然回补。
     */
    val digestedAtMillis: Long? = null,
    /**
     * 语义向量（记忆改造四期·部件⑥·图纸 §3.2）：见面档案的向量索引（float32 小端字节·与消息向量同款编码
     * [com.situ.aichat.prompt.memory.VectorMemoryService.serializeEmbedding]）；null = 待后台限流回填
     * （[com.situ.aichat.prompt.memory.MeetingArchiveVectorService.backfillMissing]）。**照 [WorldMemoryEntity] 先例
     * 不手写 equals/hashCode**：ByteArray 走引用相等语义（本实体不做整实体值比较断言）。正文编辑（updateEdited）
     * 置 null 令旧向量失效，下次 worker 重嵌。legacy 行/summary 空行永不建向量。
     */
    val embedding: ByteArray? = null,
)
