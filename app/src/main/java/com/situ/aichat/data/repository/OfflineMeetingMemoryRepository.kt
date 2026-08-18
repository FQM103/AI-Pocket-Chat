package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.offline.OfflineMeetingLegacyParser
import com.situ.aichat.offline.OfflineMeetingMemoryRenderer
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 线下见面回忆行的读写/懒播种/渲染出口（梦剧场 B 部·图纸 §3.3）——UI 与 prompt 取数的**唯一入口**（UI 绝不直碰 DAO）。
 *
 * 懒播种：旧 `CharacterEntity.offlineMeetingMemorySummary`（blob·冻结只读）首次访问时 [OfflineMeetingLegacyParser] 解析成行；
 * 注入端 [renderedForInjection] 从行渲染出与今天逐字节一致的【见面 · 】格式（[OfflineMeetingMemoryRenderer]）。
 */
@Singleton
class OfflineMeetingMemoryRepository @Inject constructor(
    private val dao: OfflineMeetingMemoryDao,
    private val characterDao: CharacterDao,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 懒播种（**幂等**）：已有行或 blob 空 → return；否则解析 blob 逐行 upsert。blob 保留不清。
     * 幂等靠「先 count 再插」+ 播种 uuid 确定性（[OfflineMeetingLegacyParser]）+ upsert REPLACE 容忍并发双调用。
     */
    suspend fun ensureSeeded(characterUuid: String) {
        if (characterUuid.isEmpty()) return
        if (dao.countByCharacter(characterUuid) > 0) return
        val blob = characterDao.getByUuid(characterUuid)?.offlineMeetingMemorySummary.orEmpty()
        if (blob.isBlank()) return
        val rows = OfflineMeetingLegacyParser.parse(characterUuid, blob)
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    /**
     * 普通聊天注入文本（§3.1）：懒播种 → 行空且 blob 非空则原样返回 blob（旧备份优雅降级·注入不断档·E2）→
     * 否则按 `meetingMemoryInjectCount`/`meetingMemoryMaxLength` 渲染。
     */
    suspend fun renderedForInjection(characterUuid: String): String {
        if (characterUuid.isEmpty()) return ""
        ensureSeeded(characterUuid)
        val rows = dao.byCharacter(characterUuid)
        if (rows.isEmpty()) {
            return characterDao.getByUuid(characterUuid)?.offlineMeetingMemorySummary.orEmpty()
        }
        val settings = settingsRepository.getAppSettings()
        return OfflineMeetingMemoryRenderer.render(
            rows = rows,
            injectCount = settings.meetingMemoryInjectCount,
            budget = settings.meetingMemoryMaxLength,
        )
    }

    /** 某角色全部行（startedAt 升序·回忆屏展示由调用方倒序）。 */
    suspend fun byCharacter(characterUuid: String): List<OfflineMeetingMemoryEntity> = dao.byCharacter(characterUuid)

    /** 按 session 查见面行（余温消息守卫④：取该 sessionId 的行·取不到→跳过·§3.10）。 */
    suspend fun bySessionId(sessionId: String): OfflineMeetingMemoryEntity? =
        if (sessionId.isEmpty()) null else dao.findBySessionId(sessionId)

    /**
     * 按 sessionId upsert：旧行存在则**保 uuid/createdAt 更新其余**，否则新建（E6 幂等·摘要重跑/自愈不产生第二行）。
     * 返回落库后的行（含最终 uuid）。
     */
    suspend fun upsertMeeting(row: OfflineMeetingMemoryEntity): OfflineMeetingMemoryEntity {
        // 先播种旧 blob（幂等）：否则新行落库后 countByCharacter>0，renderedForInjection 的 ensureSeeded 早退 →
        // 旧见面永不播种成行 → 注入丢失旧见面摘要。播种在此收口，保证行是完整真相源。
        ensureSeeded(row.characterUuid)
        val existing = if (row.sessionId.isNotEmpty()) dao.findBySessionId(row.sessionId) else null
        val merged = if (existing != null) {
            row.copy(uuid = existing.uuid, createdAtMillis = existing.createdAtMillis)
        } else {
            row
        }
        dao.upsert(merged)
        return merged
    }

    /**
     * 手动编辑写回（回忆屏·图纸 §3.11）：按 [rowUuid] 更新地点/活动/摘要 + `sourceRaw="manual"`。
     * 行不存在（已被删/未播种）→ 静默返回。注入宏直读行（B3 收尾），无需再写 blob。
     *
     * 记忆改造四期·部件⑥（图纸 §3.2·E3）：正文（地点/活动/摘要）变了 → 置 `embedding = null` 令旧向量失效，
     * 出候选池，下次 [com.situ.aichat.work.EmbeddingBackfillWorker] 重嵌（stale 向量绝不留用）。
     */
    suspend fun updateEdited(rowUuid: String, newLocation: String, newActivity: String, newSummary: String, now: Long) {
        val existing = dao.findByUuid(rowUuid) ?: return
        dao.upsert(
            existing.copy(
                location = newLocation,
                activity = newActivity,
                summary = newSummary,
                sourceRaw = "manual",
                updatedAtMillis = now,
                embedding = null,
            ),
        )
    }

    /** 当日全部角色的见面行（日记提及·§3.9·跨角色·kind="meeting"）。 */
    suspend fun meetingsOnDay(startMillis: Long, endMillis: Long): List<OfflineMeetingMemoryEntity> =
        dao.meetingsInRange(startMillis, endMillis)

    /** 近期见面行 Flow（联系人「最近纪事」·图纸一 #5·§3.3·只读透传）。 */
    fun observeMeetingsSince(since: Long): Flow<List<OfflineMeetingMemoryEntity>> = dao.observeMeetingsSince(since)

    /** 24h 自愈候选：某角色最老 fallback 行（B2 用）。 */
    suspend fun oldestFallback(characterUuid: String): OfflineMeetingMemoryEntity? = dao.oldestFallback(characterUuid)

    /** 删角连坐清理（与 conversations/messages 同处调）。 */
    suspend fun deleteByCharacter(characterUuid: String) = dao.deleteByCharacter(characterUuid)
}
