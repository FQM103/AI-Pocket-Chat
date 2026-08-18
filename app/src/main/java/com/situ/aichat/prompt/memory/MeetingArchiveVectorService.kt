package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.util.StringListJson
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 见面档案向量索引（记忆改造四期·部件⑥·图纸 §3.2）：见面档案跌出「最新 N 次完整注入」后，聊到相关话题时靠语义
 * 检索回忆起那次见面的细节——档案行建向量，与消息向量在 [VectorMemoryService] 的**同一个 TOP_K 候选池**竞争。
 *
 * 职责边界（图纸 §2.3）：本服务不碰消息表、不做合并排序（单池合并在 [VectorMemoryService]）、不调 LLM。嵌入编码 =
 * [VectorMemoryService.serializeEmbedding]（float32 小端·与消息/世界记忆同款），嵌入后端 = [TextEmbedder]（ONNX
 * bge-small-zh）；回填由 [com.situ.aichat.work.EmbeddingBackfillWorker] 末尾调用（复用现有 worker·requireNetwork=false）。
 *
 * 「一事一形态」防重复：top-N（最新 [com.situ.aichat.data.model.AppSettings.meetingMemoryInjectCount] 场）的完整档案卡
 * 已整卡注入在场 → 这些场既不出向量候选、其原文消息也在 [VectorMemoryService] 侧被 [Retrieval.excludedSessionIds] 排除。
 */
@Singleton
class MeetingArchiveVectorService @Inject constructor(
    private val dao: OfflineMeetingMemoryDao,
    private val settingsRepository: SettingsRepository,
    private val embedder: TextEmbedder,
) {

    /** 嵌入源文本（锁定·纯函数·internal·T1）：地点·活动头行 + 摘要 + 难忘行。不含【见面 · 】标题（注入卡格式零碰）。 */
    internal fun embedSource(m: OfflineMeetingMemoryEntity): String {
        val head = listOf(m.location, m.activity).filter { it.isNotBlank() }.joinToString(" · ")
        val highlights = StringListJson.decode(m.highlightsJson)
        return buildString {
            if (head.isNotEmpty()) { append(head); append('\n') }
            append(m.summary)
            if (highlights.isNotEmpty()) { append('\n'); append("难忘：${highlights.joinToString("；")}") }
        }.trim()
    }

    /**
     * 回填缺嵌入档案（逐字照 [com.situ.aichat.world.link.WorldMemoryEmbedder].backfillMissing 范式：可用性先探 /
     * 空批退 / 失败即停 / 批 [BATCH_SIZE] / 批间让片 [BACKFILL_YIELD_MS]）。
     */
    suspend fun backfillMissing() {
        if (!embedder.isAvailable) return
        while (true) {
            val batch = dao.missingEmbedding(BATCH_SIZE)
            if (batch.isEmpty()) return // 空批退出
            for (memory in batch) {
                val vector = embedder.embed(embedSource(memory)) ?: return // 不可用/失败 → 停（避免空跑死循环）
                dao.updateEmbedding(memory.uuid, VectorMemoryService.serializeEmbedding(vector))
            }
            delay(BACKFILL_YIELD_MS) // 批间让片给前台发消息当轮嵌入
        }
    }

    /** 模型签名变更清空（由 [VectorMemoryService.detectModelChangeAndClearIfNeeded] 的 CLEAR_AND_REEMBED 分支调）。 */
    suspend fun clearAll(): Int = dao.clearAllEmbeddings()

    /** 单条第二路候选（内容 = [embedSource] 产出·[startedAtMillis] 作时间戳·[similarity] 余弦分）。 */
    data class ArchiveCandidate(val content: String, val startedAtMillis: Long, val similarity: Double)

    /** 第二路候选 + top-N 场原文消息排除集（供 [VectorMemoryService] 合并 + 排除·图纸 §3.2）。 */
    data class Retrieval(val candidates: List<ArchiveCandidate>, val excludedSessionIds: Set<String>)

    /** 第二路候选 + top-N 排除集（锁定算法·图纸 §3.2）。 */
    suspend fun retrieval(queryEmbedding: FloatArray, characterUuid: String, threshold: Double): Retrieval {
        val meetings = dao.byCharacter(characterUuid).filter { it.kindRaw == "meeting" }.sortedBy { it.startedAtMillis }
        val n = settingsRepository.getAppSettings().meetingMemoryInjectCount
        val topN = if (n <= 0) emptyList() else meetings.takeLast(n) // 稳定规则：最新 N 条（不随渲染预算降级漂移·一期 §3.5-A 同款）
        val excluded = topN.mapNotNullTo(HashSet()) { it.sessionId.takeIf { s -> s.isNotBlank() } }
        val topNUuids = topN.mapTo(HashSet()) { it.uuid }
        val candidates = meetings.mapNotNull { m ->
            if (m.uuid in topNUuids) return@mapNotNull null // 完整卡已在场 → 不出候选
            val emb = m.embedding?.let(VectorMemoryService::deserializeEmbedding) ?: return@mapNotNull null
            if (emb.size != queryEmbedding.size) return@mapNotNull null
            val sim = VectorMemoryService.cosineSimilarity(queryEmbedding, emb)
            if (sim < threshold) return@mapNotNull null
            ArchiveCandidate(embedSource(m), m.startedAtMillis, sim)
        }
        return Retrieval(candidates, excluded)
    }

    companion object {
        /** 每批条数（图纸 §9·批 16·照 WorldMemoryEmbedder）。 */
        private const val BATCH_SIZE = 16

        /** 批间让片毫秒（照抄 [VectorMemoryService] 现有 BACKFILL_YIELD_MS 值·图纸 §3.2）。 */
        private const val BACKFILL_YIELD_MS = 50L
    }
}
