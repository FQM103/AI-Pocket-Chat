package com.situ.aichat.world.link

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldMemoryDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldMemoryEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.social.WorldRelationshipBeats as Beats
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天回合的**世界上下文装配器**（W5 图纸 §3.3 / §3.2 聊天回合链 / 契约 §9【核心】）：聊天时把「关系提炼」+
 * 相关世界记忆压成一个注入块，让角色真实记得世界。**只读 + 一次 ONNX 嵌入**，绝不写库（写在回前台通行证里）。
 *
 * 门控四连（任一命中 → null·不注入）：① 角色未加入世界 ② 全局关系开关关（护栏#7：关 = 聊天里不提彼此）
 * ③ 世界未初始化 ④ 无边且无记忆。组装 = §4.1 块头 + 提炼 ≤3 行（[WorldRelationshipDigest]）+ 记忆行 ≤6
 * （近 3 天 ≤3 ∪ 向量检索 ≤3·余弦 ≥ [SIMILARITY_THRESHOLD]·剔近 3 天重复）。canon 优先写进块头（对话冲突以对话为准）。
 */
@Singleton
class WorldChatContextProvider @Inject constructor(
    private val worldDao: WorldDao,
    private val socialDao: WorldSocialDao,
    private val memoryDao: WorldMemoryDao,
    private val characterDao: CharacterDao,
    private val embedder: TextEmbedder,
    private val vectorService: VectorMemoryService,
) {

    /** 为一个聊天回合装配世界上下文块；不注入时返回 null。[query] = 最新真实用户消息文本。 */
    suspend fun forTurn(character: CharacterEntity, query: String, settings: AppSettings): String? {
        if (!character.joinedWorld) return null // ①
        if (!settings.worldRelationshipsEnabled) return null // ②（护栏#7）
        val state = worldDao.getState() ?: return null // ③
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val nowMs = System.currentTimeMillis() // 仅作相对日/近层过滤的当下读，不进任何派生/存储字段

        val inWorldNames = characterDao.getInWorld().associate { it.uuid to it.name }
        val edges = socialDao.edgesFrom(character.uuid).filter { !it.dormant && inWorldNames.containsKey(it.toId) }
        val recentByPair = recentEventByPair(character, edges, nowMs)

        val digestLines = WorldRelationshipDigest.build(character, edges, recentByPair, inWorldNames, query, nowMs, zone)
        val memoryLines = memoryLines(character, query, nowMs, zone)

        if (digestLines.isEmpty() && memoryLines.isEmpty()) return null // ④ 无边且无记忆
        return buildString {
            append(BLOCK_HEADER)
            digestLines.forEach { append('\n').append(it) }
            memoryLines.forEach { append('\n').append(it) }
        }
    }

    /** 各对 7 天内最新一条非 rel_compact 事件（无则不含该键）。 */
    private suspend fun recentEventByPair(
        character: CharacterEntity,
        edges: List<com.situ.aichat.data.local.entity.WorldRelationshipEntity>,
        nowMs: Long,
    ): Map<String, WorldRelationshipEventEntity> {
        val windowStart = nowMs - RECENT_EVENT_WINDOW_MS
        val result = HashMap<String, WorldRelationshipEventEntity>()
        for (edge in edges) {
            val pairKey = WorldIds.pairKey(character.uuid, edge.toId)
            if (result.containsKey(pairKey)) continue
            socialDao.eventsForPair(pairKey)
                .filter { it.kindRaw != Beats.COMPACT && it.happenedAt >= windowStart }
                .maxByOrNull { it.happenedAt }
                ?.let { result[pairKey] = it }
        }
        return result
    }

    /**
     * 记忆行：近 3 天层（≤3）∪ 向量检索层（≤3·剔近 3 天重复）。query 空白/嵌入器不可用 → 只有近 3 天层。
     * `internal` 便于 T2 行为测试（断言检索结果 + 向量运算在后台线程）。
     */
    internal suspend fun memoryLines(character: CharacterEntity, query: String, nowMs: Long, zone: ZoneId): List<String> {
        val near = memoryDao.recentForCharacter(character.uuid, nowMs - NEAR_MEMORY_WINDOW_MS).take(NEAR_MEMORY_LIMIT)
        val nearUuids = near.mapTo(HashSet()) { it.uuid }

        // 向量检索层：一次 ONNX 嵌入 + 反序列化 + 逐条余弦相似度是 CPU 密集运算，整段切到后台线程执行
        // （与 VectorMemoryService.searchRelevantMemories 同款手法）——聊天回合作用域是 Dispatchers.Main.immediate
        // （见 CoroutineScopeModule.ChatTurnScope），绝不能在主线程做此运算或触发首次模型加载。
        // embedder.isAvailable 首读会同步加载模型，故一并置于后台。行为与原实现等价（门控/阈值/排序/上限不变）。
        val vector: List<WorldMemoryEntity> = if (query.isNotBlank()) {
            withContext(Dispatchers.Default) {
                if (!embedder.isAvailable) return@withContext emptyList<WorldMemoryEntity>()
                val q = embedder.embed(query) ?: return@withContext emptyList<WorldMemoryEntity>()
                memoryDao.embeddedForCharacter(character.uuid)
                    .mapNotNull { m ->
                        val emb = m.embedding?.let { vectorService.deserializeEmbedding(it) } ?: return@mapNotNull null
                        val sim = vectorService.cosineSimilarity(q, emb)
                        if (sim >= SIMILARITY_THRESHOLD && m.uuid !in nearUuids) m to sim else null
                    }
                    .sortedByDescending { it.second }
                    .take(VECTOR_MEMORY_LIMIT)
                    .map { it.first }
            }
        } else {
            emptyList()
        }

        return (near + vector).map { renderMemoryLine(it, zone) }
    }

    /** 记忆行格式（§4.1·锁死）：`- [{YYYY-MM-DD}] {content}`（日期 = happenedAt 于用户时区本地日）。 */
    private fun renderMemoryLine(memory: WorldMemoryEntity, zone: ZoneId): String =
        "- [${WorldClock.localDateOf(memory.happenedAt, zone)}] ${memory.content}"

    companion object {
        /** 聊天注入块头（§4.1·单行·锁死·canon 优先：对话冲突以对话为准）。 */
        const val BLOCK_HEADER =
            "以下是你在这座小城生活的人际近况与经历（背景信息：可在聊天里自然提起，绝不逐条播报；若与你们对话中已确认的事实冲突，一律以对话为准）："

        /** 向量检索余弦阈值 0.65（图纸 §9·与 [VectorMemoryService.DEFAULT_SIMILARITY_THRESHOLD] 对齐）。 */
        private const val SIMILARITY_THRESHOLD = 0.65

        private const val RECENT_EVENT_WINDOW_MS = 7L * 86_400_000L // 近事窗 7 天
        private const val NEAR_MEMORY_WINDOW_MS = 3L * 86_400_000L // 记忆近层 3 天
        private const val NEAR_MEMORY_LIMIT = 3 // 近 3 天层 ≤3
        private const val VECTOR_MEMORY_LIMIT = 3 // 向量检索 topK 3
    }
}
