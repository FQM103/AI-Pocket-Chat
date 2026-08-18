package com.situ.aichat.world.live

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.WorldSeeds
import com.situ.aichat.world.bulletin.WorldLlmBudget
import com.situ.aichat.world.cast.WorldNativeRoster
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 偷听候选实体（§3 池·由 VM 从 cast 装配传入·角色=uuid / 原住民=nativeId+slug）。 */
data class EavesdropEntity(
    /** 排序 + 关系解析 + 事件 involved 键：角色 = uuid；原住民 = nativeId（`native:<slug>`）。 */
    val id: String,
    val name: String,
    /** 已招募角色（含 guest）→ uuid；原住民 → null。 */
    val characterUuid: String?,
    /** 原住民 → slug；角色 → null。 */
    val nativeSlug: String?,
)

/** 偷听一次的结果（图纸 §4.6 三态 whisper·UI 据此选气泡与落账文案）。 */
sealed interface EavesdropOutcome {
    /** 现场生成（LLM 成功·[summary] 已记世界事件·whisper 带摘要）。 */
    data class Live(val lines: List<EavesLine>, val summary: String) : EavesdropOutcome

    /** 模板台词（省档/预算尽/断网/解析失败·不记事件·whisper 无摘要）。 */
    data class Template(val lines: List<EavesLine>) : EavesdropOutcome

    /** 冷却窗内同一对再触发（不重放气泡·只出冷却 whisper·零扣零事件）。 */
    data object Cooldown : EavesdropOutcome

    /** 池 <2 / 世界未就绪（不触发·防御）。 */
    data object Unavailable : EavesdropOutcome
}

/**
 * 偷听服务（W12 图纸 §3/§4.6/§9·契约 §7.A 第二时刻·决策 43①）：一次偷听 =
 * 选对（种子确定性·同日同室恒同一对）→ 冷却窗（30min·进程内 Mutex）→ 预算（先扣后调）→ LLM 现场生成 or 模板台词。
 *
 * **旁观边界（契约 §10·§6 禁区）**：内容**绝不进任何会话/消息表**——只把一句摘要落 `world_event`（kind=`eavesdrop`·
 * uuid `world:eaves:<pairKey>:<epochDay>`）。新 kind 不进 [com.situ.aichat.world.link.WorldMemoryScribe] 的
 * `MEMORY_KINDS`（零记忆派生）、不进 W8 通知（零推送）。断网/失败/省档一律退模板，气泡永不空白。
 *
 * **§6 耦合对**：prompt 输出格式 ↔ [WorldEavesdropLines] 解析器（两侧本包内·改一侧必同步·§9 锁）。
 */
@Singleton
class WorldEavesdropService @Inject constructor(
    private val worldDao: WorldDao,
    private val socialDao: WorldSocialDao,
    private val characterDao: CharacterDao,
    private val budget: WorldLlmBudget,
    private val apiConfigRepo: ApiConfigRepository,
    private val contextLog: ContextLogService,
    private val settingsRepository: SettingsRepository,
) {

    /** 冷却台账（进程内·§3）：进程死只损失冷却记录、至多多花一次预算（可接受）。事件 uuid 幂等为第二保险。 */
    private val cooldownMutex = Mutex()
    private val lastPlayedAt = HashMap<String, Long>() // pairKey → lastPlayedAtMs

    /**
     * 偷听一次（图纸 §3）：[pool] = 当前地点可偷听实体池（guests ∪ discovered 原住民·VM 装配·<2 → [EavesdropOutcome.Unavailable]）。
     * 选对（种子）→ 冷却门（进程内·30min）→ 预算门（先扣后调）→ LLM or 模板。冷却门通过即**当刻占位**（模板/现场都占冷却·防缩放刷）。
     */
    suspend fun eavesdrop(
        pool: List<EavesdropEntity>,
        cityId: String,
        placeId: String,
        cityName: String,
        placeName: String,
        nowMs: Long,
    ): EavesdropOutcome {
        if (pool.size < 2) return EavesdropOutcome.Unavailable
        val state = worldDao.getState() ?: return EavesdropOutcome.Unavailable
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val epochDay = WorldClock.localDateOf(nowMs, zone).toEpochDay()
        val (a, b) = pickPair(pool, state.seed, placeId, epochDay)
        val pairKey = WorldIds.pairKey(a.id, b.id)

        // 冷却门 + 当刻占位原子化（进程内 Mutex·§3）：窗内 → 只出冷却 whisper·零扣零事件；否则立即占位（并发再触发即见冷却）。
        val blocked = cooldownMutex.withLock {
            val last = lastPlayedAt[pairKey]
            if (last != null && nowMs - last < COOLDOWN_MS) {
                true
            } else {
                lastPlayedAt[pairKey] = nowMs // 模板/现场都占冷却（demo 过审行为）
                false
            }
        }
        if (blocked) return EavesdropOutcome.Cooldown

        val tier = settingsRepository.appSettings.first().worldVividnessTier
        val cap = WorldVividnessPools.eavesdropCap(tier)
        return tryLive(a, b, pairKey, cityId, cityName, placeName, epochDay, cap, nowMs)
            ?: EavesdropOutcome.Template(WorldEavesdropLines.templateLines(a.name, b.name, pairKey, epochDay, state.seed))
    }

    /** 选对（§3 锁死·`internal` 便于 T2-1 直测确定性）：池按 id 字典序 → 枚举全部两两组合 → `|derive(seed,"eaves:placeId",epochDay)| % 组合数`（同日同室恒同）。 */
    internal fun pickPair(pool: List<EavesdropEntity>, seed: Long, placeId: String, epochDay: Long): Pair<EavesdropEntity, EavesdropEntity> {
        val sorted = pool.sortedBy { it.id }
        val pairs = ArrayList<Pair<EavesdropEntity, EavesdropEntity>>()
        for (i in sorted.indices) for (j in i + 1 until sorted.size) pairs.add(sorted[i] to sorted[j])
        val h = WorldSeeds.derive(seed, "eaves:$placeId", epochDay)
        return pairs[Math.floorMod(h, pairs.size.toLong()).toInt()]
    }

    /**
     * 现场生成（先扣后调·图纸 §3/E9/E11/E14）：预算门未过（省档 cap0/超顶）/无配置/断网异常/解析弃稿 → null（退模板·额度不退）。
     * 成功 → 记一条世界事件（幂等·存在即跳过防清 seenAt/notifiedAt）→ [EavesdropOutcome.Live]。
     */
    private suspend fun tryLive(
        a: EavesdropEntity,
        b: EavesdropEntity,
        pairKey: String,
        cityId: String,
        cityName: String,
        placeName: String,
        epochDay: Long,
        cap: Int,
        nowMs: Long,
    ): EavesdropOutcome.Live? {
        if (!budget.tryConsume(WorldVividnessPools.EAVES, epochDay, cap)) return null // 省档(cap0)/超顶 → 模板
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) ?: return null // 无有效配置 → 模板（额度已扣·先扣后调）
        val raw = try {
            // 非流式 completion 不剥内联 <think>（只有流式经 ThinkTagParser）。逐行解析器虽有说话人白名单，
            // 但思考里的对话草稿行恰好是「名字：台词」格式，可能混进气泡/触发整稿弃稿——解析前统一剥净。
            MemoryService.strippingThinkingTags(
                contextLog.completion(
                    source = LogSource.WORLD_EAVESDROP,
                    characterName = "",
                    config = config,
                    messages = listOf(ChatMessageDto(role = "user", content = buildPrompt(a, b, cityName, placeName, nowMs))),
                    temperature = EAVES_TEMPERATURE,
                ),
            )
        } catch (e: Exception) {
            return null // 断网/超时 → 模板（额度不退）
        }
        val parsed = WorldEavesdropLines.parse(raw, a.name, b.name) ?: return null // 解析弃稿 → 模板
        recordEvent(a, b, pairKey, cityId, epochDay, parsed.summary, nowMs)
        return EavesdropOutcome.Live(parsed.lines, parsed.summary)
    }

    /** 落一句摘要入世界事件（§9 uuid `world:eaves:<pairKey>:<epochDay>`·kind `eavesdrop`）。存在即跳过（幂等第二保险）。 */
    private suspend fun recordEvent(a: EavesdropEntity, b: EavesdropEntity, pairKey: String, cityId: String, epochDay: Long, summary: String, nowMs: Long) {
        val uuid = UUID.nameUUIDFromBytes("world:eaves:$pairKey:$epochDay".toByteArray()).toString()
        if (worldDao.getEvent(uuid) != null) return // 存在即跳过（永不重写·防清 seenAt/notifiedAt）
        worldDao.upsertEvent(
            WorldEventEntity(
                uuid = uuid,
                kindRaw = EAVESDROP_KIND,
                involvedIdsJson = StringListJson.encode(listOf(a.id, b.id)),
                cityId = cityId,
                summary = summary,
                happenedAt = nowMs,
            ),
        )
    }

    /** 组 prompt（§9 全文锁死·三个「一句」按 §3 装配·可空句直接拼接）。 */
    private suspend fun buildPrompt(a: EavesdropEntity, b: EavesdropEntity, cityName: String, placeName: String, nowMs: Long): String {
        val extra = (relationLine(a, b).orEmpty()) + (recentEventLine(a, b, nowMs).orEmpty())
        return "你在写一部温暖日常剧的一小段背景对话。${a.name}（${personaOf(a)}）和${b.name}（${personaOf(b)}）" +
            "此刻在${cityName}的${placeName}。$extra\n" +
            "写他们此刻的简短对话，共 3 到 4 句，每句一行，格式「名字：台词」，口语、贴人设、每句不超过 40 字。\n" +
            "最后另起一行输出「【动静】」加一句不超过 30 字的第三人称摘要。"
    }

    /** 一句人设（§3）：角色取 personalityDescription 前 40 字 / 原住民取 def.personality 前 40 字。 */
    private suspend fun personaOf(e: EavesdropEntity): String {
        val full = when {
            e.characterUuid != null -> characterDao.getByUuid(e.characterUuid)?.personalityDescription.orEmpty()
            e.nativeSlug != null -> WorldNativeRoster.bySlug(e.nativeSlug)?.personality.orEmpty()
            else -> ""
        }
        return full.take(40)
    }

    /**
     * 关系一句（§3）：char↔char 有落地边 → 「他们是{types·连}——{bond}」；原住民对无落地边 → 出厂边 origin 原文；
     * 其余（char↔native / 无任何边）→ null（省略）。
     */
    private suspend fun relationLine(a: EavesdropEntity, b: EavesdropEntity): String? {
        if (a.characterUuid != null && b.characterUuid != null) {
            val edge = socialDao.getEdge(a.characterUuid, b.characterUuid)
                ?: socialDao.getEdge(b.characterUuid, a.characterUuid)
                ?: return null
            val types = StringListJson.decode(edge.typesJson).joinToString("·")
            return "他们是$types——${edge.bond}"
        }
        if (a.nativeSlug != null && b.nativeSlug != null) {
            return WorldNativeRoster.FACTORY_EDGES.firstOrNull {
                (it.slugA == a.nativeSlug && it.slugB == b.nativeSlug) || (it.slugA == b.nativeSlug && it.slugB == a.nativeSlug)
            }?.origin
        }
        return null
    }

    /** 近事一句（§3）：该对 30 天内最近一条关系事件的 summary 原文（仅 char↔char·事件按角色 uuid pairKey 聚合）；无则 null。 */
    private suspend fun recentEventLine(a: EavesdropEntity, b: EavesdropEntity, nowMs: Long): String? {
        if (a.characterUuid == null || b.characterUuid == null) return null
        val newest = socialDao.newestEventForPair(WorldIds.pairKey(a.characterUuid, b.characterUuid)) ?: return null
        return if (nowMs - newest.happenedAt <= RECENT_EVENT_WINDOW_MS) newest.summary else null
    }

    companion object {
        /** 偷听世界事件 kind（`world_event.kindRaw`·§9 锁死）。 */
        const val EAVESDROP_KIND = "eavesdrop"

        private const val COOLDOWN_MS = 30 * 60_000L // 冷却窗 30min·§9 锁死
        private const val RECENT_EVENT_WINDOW_MS = 30L * 24 * 60 * 60_000L // 近事窗 30 天·§3
        private const val EAVES_TEMPERATURE = 0.8 // 对话创意度（图纸未锁具体值·§11 记）
    }
}
