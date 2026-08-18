package com.situ.aichat.world.link

import com.situ.aichat.data.local.dao.WorldMemoryDao
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.prompt.memory.VectorMemoryService
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界记忆的**嵌入限流回填**（W5 图纸 §3.3 / 契约 §9「嵌入限流」）：懒结算一次冒出的几十条新记忆走后台队列
 * 慢慢建向量索引，绝不卡开机、不发热。分批 [BATCH_SIZE] + 批间 [BACKFILL_YIELD_MS] 让片（照抄
 * [VectorMemoryService] 的 `BACKFILL_YIELD_MS` 值）。
 *
 * 编码 = [VectorMemoryService.serializeEmbedding]（float32 小端·与消息向量同款），嵌入后端 = [TextEmbedder]
 * （ONNX bge-small-zh）。嵌入器不可用/推理失败 → 直接 return（本轮不回填，下次 worker 再排·KEEP）。由
 * [com.situ.aichat.work.EmbeddingBackfillWorker] 末尾调用（复用现有 worker·requireNetwork=false）。
 */
@Singleton
class WorldMemoryEmbedder @Inject constructor(
    private val memoryDao: WorldMemoryDao,
    private val embedder: TextEmbedder,
    private val vectorService: VectorMemoryService,
) {

    /** 回填所有缺嵌入（embedding IS NULL）的世界记忆；空批退出，嵌入器不可用即返回。 */
    suspend fun backfillMissing() {
        if (!embedder.isAvailable) return
        while (true) {
            val batch = memoryDao.missingEmbedding(BATCH_SIZE)
            if (batch.isEmpty()) return // 空批退出
            for (memory in batch) {
                val vector = embedder.embed(memory.content) ?: return // 不可用/失败 → 停（避免空跑死循环）
                memoryDao.updateEmbedding(memory.uuid, vectorService.serializeEmbedding(vector))
            }
            delay(BACKFILL_YIELD_MS) // 批间让片给前台发消息当轮嵌入
        }
    }

    companion object {
        /** 每批条数（图纸 §9·批 16）。 */
        private const val BATCH_SIZE = 16

        /** 批间让片毫秒（照抄 [VectorMemoryService] 现有 BACKFILL_YIELD_MS 值·图纸 §3.3）。 */
        private const val BACKFILL_YIELD_MS = 50L
    }
}
