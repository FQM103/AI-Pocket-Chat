package com.situ.aichat.world.live

import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.WorldCityLoreEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.atlas.CityLoreSkeleton
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldLoreSkeleton
import com.situ.aichat.world.bulletin.WorldLlmBudget
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首访点亮·风物志（W12 图纸 §3/§9·契约 §7.A 第三时刻·决策 43②）：程序（生成）城首访时 LLM 就骨架定稿一段风物志，
 * **一次定稿 = 永久 canon**（`world_city_lore` 表 IGNORE·永不覆盖）。断网/失败/超预算/省档/条件不满足一律**不调 LLM
 * 或弃稿静默**——城市卡确定性拼句照旧兜底，下次进小镇再试。
 *
 * **E13 条件矩阵**（六种任一不满足即零 LLM）：精修城（骨架即 canon）/ 用户不在场 / 在途 / 已有 lore / 预算尽 / 省档。
 * **E14**：LLM 产文 <40 或 >400 字 → 弃稿静默（额度已扣不退·先扣后调·同小报「宁少不多花」）。
 */
@Singleton
class WorldLoreService @Inject constructor(
    private val worldDao: WorldDao,
    private val travelService: com.situ.aichat.world.travel.WorldTravelService,
    private val budget: WorldLlmBudget,
    private val apiConfigRepo: ApiConfigRepository,
    private val contextLog: ContextLogService,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 首访点亮 [cityId]（§3）：条件矩阵全过 → 预算门（先扣后调）→ LLM → 长度阈校验 → 写 canon（IGNORE）。
     * 返回是否**新写入**（供 VM 失效大陆城市卡缓存·让卡 body 改显 canon lore）。任何一步不满足 = 静默返 false。
     */
    suspend fun tryLightUp(cityId: String, nowMs: Long): Boolean {
        val state = worldDao.getState() ?: return false
        val atlas = WorldAtlas.of(state.seed)
        val city = atlas.cityById(cityId) ?: return false
        if (city.curated) return false // 精修城骨架即 canon·不点亮
        if (worldDao.getLore(cityId) != null) return false // 已有 canon·永不覆盖
        val presence = travelService.userPresence(nowMs)
        if (presence.cityId != cityId || presence.inTransitToCityId != null) return false // 不在场 / 在途
        val region = atlas.regionById(city.regionId) ?: return false

        val tier = settingsRepository.appSettings.first().worldVividnessTier
        val cap = WorldVividnessPools.loreCap(tier)
        val epochDay = WorldClock.localDateOf(nowMs, WorldClock.resolveZone(state.userTimezoneId)).toEpochDay()
        if (!budget.tryConsume(WorldVividnessPools.LORE, epochDay, cap)) return false // 省档(cap0)/超顶 → 静默
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) ?: return false // 无配置 → 静默（额度已扣·先扣后调）

        val skeleton = WorldLoreSkeleton.skeletonOf(state.seed, city, region)
        val text = try {
            // 非流式 completion 不剥内联 <think>（只有流式经 ThinkTagParser），永久 canon 落库前在此剥净（含 trim）。
            MemoryService.strippingThinkingTags(
                contextLog.completion(
                    source = LogSource.WORLD_LORE,
                    characterName = "",
                    config = config,
                    messages = listOf(ChatMessageDto(role = "user", content = buildPrompt(city.name, skeleton))),
                    temperature = LORE_TEMPERATURE,
                ),
            )
        } catch (e: Exception) {
            return false // 断网/超时 → 静默（额度不退）
        }
        if (text.length < LORE_MIN_LEN || text.length > LORE_MAX_LEN) return false // E14 弃稿静默
        worldDao.insertLore(WorldCityLoreEntity(cityId = cityId, loreJson = encodeLore(text), generatedAt = nowMs)) // IGNORE 永不覆盖
        return true
    }

    /** 风物志 prompt（§9 全文锁死·骨架字段逐项拼入·§11 记具体标签）。 */
    private fun buildPrompt(cityName: String, s: CityLoreSkeleton): String {
        val bones = "所在大区：${s.regionName}；特产：${s.specialty}；老街：${s.oldStreet}；" +
            "招牌吃食：${s.signatureDish}；地标：${s.landmarkHint}；传说：${s.legendHint}"
        return "为虚构星球上的小城「$cityName」写一段 120 到 200 字的风物志，中文，温暖克制、有画面感。" +
            "已知骨架：$bones。写成一段连续短文，不要列条目，不要出现「AI」「玩家」等词。"
    }

    companion object {
        private const val LORE_MIN_LEN = 40 // 弃稿下阈·§9 锁死
        private const val LORE_MAX_LEN = 400 // 弃稿上阈·§9 锁死
        private const val LORE_TEMPERATURE = 0.8 // 图纸未锁·§11 记

        /** 风物志 JSON 编码（键名 `text` 锁死·§3·预留扩展）。 */
        fun encodeLore(text: String): String = buildJsonObject { put("text", text) }.toString()

        /** 反解 canon lore 正文（供大陆城市卡 body 显示·坏数据/空 → null）。 */
        fun loreTextOf(loreJson: String): String? =
            runCatching { Json.parseToJsonElement(loreJson).jsonObject["text"]?.jsonPrimitive?.content }
                .getOrNull()?.takeIf { it.isNotBlank() }
    }
}
