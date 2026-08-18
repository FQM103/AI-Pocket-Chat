package com.situ.aichat.world.bulletin

import com.situ.aichat.data.local.dao.WorldBulletinDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.WorldBulletinEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.world.SettlementWindow
import com.situ.aichat.world.WorldSeeds
import com.situ.aichat.world.atlas.WorldAtlas
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 开机小报服务（W5 图纸 §3.3 / §4.4 / §4.5 / 契约 §7.A）：把「离开期间」的世界事件拼成模板小报（恒有值·零 LLM），
 * 按鲜活度档位 + 每日预算做**一次** LLM 润色，失败/断网/超预算/超长一律优雅退模板——**世界永不死机**。存库供 W11
 * 世界卡消费（本块零 UI）。
 *
 * hash 门（`eventsHash` = 窗内事件 uuid 升序拼串的 fnv1a64）：事件没变不重润色、不重烧 token。**先扣后调**经
 * [WorldLlmBudget]（决策 31：省 0 / 标准 3 / 豪华 12 保险丝）。ApiConfig 走当前激活配置（无有效配置 = 跳过润色）。
 */
@Singleton
class WorldBulletinService @Inject constructor(
    private val worldDao: WorldDao,
    private val bulletinDao: WorldBulletinDao,
    private val budget: WorldLlmBudget,
    private val apiConfigRepo: ApiConfigRepository,
    private val contextLog: ContextLogService,
    private val settingsRepository: SettingsRepository,
) {

    /** 刷新小报；返回是否写了/改了行（供 [com.situ.aichat.world.link.WorldLinkRunner] 记「更新/跳过」）。 */
    suspend fun refresh(state: WorldStateEntity, window: SettlementWindow, zone: ZoneId, nowMs: Long): Boolean {
        val days = window.days
        if (days.isEmpty()) return false
        val windowStartMs = days.first().date.atStartOfDay(zone).toInstant().toEpochMilli()
        // windowEndMs 取窗末日边界（确定性·禁真时钟·图纸 §9 W5-D14）：updatedAt/polishedAt 皆源于它。
        val windowEndMs = days.last().date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val epochDay = days.last().epochDay

        // 闭区间上界 = 窗末前 1ms（W8 §3.6·W7 复核挂账①裁决）：每个事件恰进且只进「它发生那天」那期小报——
        // 根治「今晨到达/今日招募」的事件跨今天+明天两期各出一次。今晨到达的即时性由 W8 通知承担，小报次日回顾。
        val events = worldDao.eventsBetween(windowStartMs, windowEndMs - 1)
        // 窗内事件标记已被小报消费（W8 §3.6·W1 埋列首次启用）：W8 通知 fire 见 seenAt 非空即闭嘴（小报讲过的不再弹）。
        // 时戳用 windowEndMs（恒定可重演·禁真时钟同源）；DAO 自带 NULL 守卫·重刷不改首次时刻。
        events.forEach { worldDao.markEventSeen(it.uuid, windowEndMs) }
        val eventsHash = WorldSeeds.fnv1a64(events.map { it.uuid }.sorted().joinToString(","))

        // 短暂离开（窗内零事件且缺席 <24h）→ 不动现有行，return（不出报）。
        if (events.isEmpty() && window.absenceMs < ABSENCE_QUIET_FLOOR_MS) return false

        val cityName = WorldAtlas.of(state.seed).cityById(state.userHomeCityId)?.name ?: WorldBulletinTemplates.FALLBACK_CITY
        val templateText = if (events.isEmpty()) {
            WorldBulletinTemplates.quiet(cityName)
        } else {
            WorldBulletinTemplates.withEvents(cityName, events)
        }

        val existing = bulletinDao.getByDay(epochDay)
        val hashChanged = existing == null || existing.eventsHash != eventsHash
        // !hashChanged ⇒ existing 非空（Kotlin 经 hashChanged 智能转型）：事件没变且已润色 → 啥都不做。
        if (!hashChanged && existing.polishedText != null) return false

        if (hashChanged) {
            // 新行/事件变了 → 先落模板行（polishedText 清 null）；进程死在润色前，模板恒有值。
            bulletinDao.upsert(bulletinRow(epochDay, windowStartMs, windowEndMs, eventsHash, templateText, polished = null))
        }

        val polished = tryPolish(epochDay, templateText)
        if (polished != null) {
            bulletinDao.upsert(bulletinRow(epochDay, windowStartMs, windowEndMs, eventsHash, templateText, polished = polished))
        }

        bulletinDao.deleteBulletinsOlderThan(epochDay - BULLETIN_RETENTION_DAYS) // 裁旧（保留 7 天）
        return hashChanged || polished != null
    }

    private fun bulletinRow(
        epochDay: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        eventsHash: Long,
        templateText: String,
        polished: String?,
    ) = WorldBulletinEntity(
        epochDay = epochDay,
        windowStartMs = windowStartMs,
        windowEndMs = windowEndMs,
        eventsHash = eventsHash,
        templateText = templateText,
        polishedText = polished,
        polishedAt = polished?.let { windowEndMs },
        updatedAt = windowEndMs,
    )

    /**
     * 预算门控 LLM 润色（省档/无配置/超预算/失败/超长 → null=保模板）。**先扣后调**：budget 通过才调 LLM。
     */
    private suspend fun tryPolish(epochDay: Long, templateText: String): String? {
        val settings = settingsRepository.appSettings.first()
        val cap = when (settings.worldVividnessTier) {
            AppSettings.WORLD_VIVIDNESS_LITE -> return null // 省档零润色
            AppSettings.WORLD_VIVIDNESS_STANDARD -> STANDARD_DAILY_CAP
            AppSettings.WORLD_VIVIDNESS_RICH -> RICH_DAILY_CAP // 保险丝·正常永达不到
            else -> return null
        }
        if (!budget.tryConsume(BUDGET_CATEGORY, epochDay, cap)) return null // 先扣后调·超顶退模板
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) ?: return null // 无有效配置 = 跳过润色
        return try {
            // 非流式 completion 不剥内联 <think>（只有流式经 ThinkTagParser），落库前在此剥净（含 trim）。
            val out = MemoryService.strippingThinkingTags(
                contextLog.completion(
                    source = LogSource.WORLD_BULLETIN,
                    characterName = "",
                    config = config,
                    messages = listOf(
                        ChatMessageDto(role = "system", content = WorldBulletinTemplates.POLISH_SYSTEM_PROMPT),
                        ChatMessageDto(role = "user", content = templateText),
                    ),
                    temperature = 0.7,
                ),
            )
            if (out.isBlank() || out.length > MAX_POLISHED_LENGTH) null else out // 空白/超长 → 弃、保模板
        } catch (e: Exception) {
            null // 异常（断网/超时…）= 保模板
        }
    }

    companion object {
        private const val BUDGET_CATEGORY = "bulletin"
        private const val STANDARD_DAILY_CAP = 3 // 决策 31·§9 禁改
        private const val RICH_DAILY_CAP = 12 // 豪华保险丝·决策 31·§9 禁改
        private const val MAX_POLISHED_LENGTH = 400 // 弃稿线·§9 禁改
        private const val BULLETIN_RETENTION_DAYS = 7 // 小报保留天数·§9 禁改
        private const val ABSENCE_QUIET_FLOOR_MS = 86_400_000L // 缺席静好线 24h·§9 禁改
    }
}
