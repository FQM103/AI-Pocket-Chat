package com.situ.aichat.worldbook

import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.prompt.memory.VectorMemoryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界书「链接条目」（vectorized·语义触发）的向量匹配（WB5·契约 §4.6）。
 * 全量复用向量记忆基建（零新轮子）：嵌入经 [VectorMemoryService.generateEmbedding]（bge-small-zh · ONNX），
 * 序列化 / 余弦相似度 / 模型签名同源单一。
 * - 嵌入按需：条目缺嵌入或**签名漂移**（换模型）→ 现场重嵌并 targeted UPDATE 落库（14.5a 自愈同款语义，
 *   但按条目粒度懒重嵌、不整表清空——条目数少，懒法更稳）；
 * - 嵌入器不可用 → 该条目本轮不触发（优雅降级，绝不清库、绝不拦聊天）；
 * - 阈值对齐记忆检索体系（AppSettings.vectorSearchThreshold·0 = 关，契约 §4.6）。
 */
@Singleton
class WorldBookVectorService @Inject constructor(
    private val vectorMemory: VectorMemoryService,
    private val worldBookDao: WorldBookDao,
) {

    /**
     * @param candidates 已筛出的启用链接条目
     * @param queryText 查询文本（最近一条用户消息，与向量记忆同口径）
     * @return 相似度 ≥ 阈值的条目 uuid（喂给引擎的 vectorMatchedEntryUuids）
     */
    suspend fun matchedEntryUuids(
        candidates: List<WorldBookEntryEntity>,
        queryText: String,
        thresholdPercent: Int,
    ): Set<String> {
        if (candidates.isEmpty() || thresholdPercent <= 0 || queryText.isBlank()) return emptySet()
        val queryVec = vectorMemory.generateEmbedding(queryText) ?: return emptySet()
        val threshold = thresholdPercent / 100.0
        val matched = mutableSetOf<String>()
        for (entry in candidates) {
            val entryVec = ensureEmbedding(entry) ?: continue
            if (vectorMemory.cosineSimilarity(queryVec, entryVec) >= threshold) matched.add(entry.uuid)
        }
        return matched
    }

    /** 取有效嵌入：签名一致直接反序列化；缺失/漂移 → 现场嵌入（标题+内容）并落库；嵌入器不可用 → null。 */
    private suspend fun ensureEmbedding(entry: WorldBookEntryEntity): FloatArray? {
        val currentSignature = VectorMemoryService.MODEL_SIGNATURE
        if (entry.embedding != null && entry.embeddingSignature == currentSignature) {
            vectorMemory.deserializeEmbedding(entry.embedding)?.let { return it }
        }
        val text = listOf(entry.comment, entry.content).filter { it.isNotBlank() }.joinToString("\n")
        if (text.isBlank()) return null
        val vec = vectorMemory.generateEmbedding(text) ?: return null
        worldBookDao.updateEntryEmbedding(entry.uuid, vectorMemory.serializeEmbedding(vec), currentSignature)
        return vec
    }
}
