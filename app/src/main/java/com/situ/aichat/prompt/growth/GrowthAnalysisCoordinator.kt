package com.situ.aichat.prompt.growth

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.GrowthAnalysisMetadata
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.dynamicInterests
import com.situ.aichat.data.model.growthLog
import com.situ.aichat.data.model.growthMetadata
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor

/** 兴趣名兜底截断长度：提示词已要求 AI 返回简短名（≤8字），此为防长句意外落库的保险丝（放宽自旧值 8）。 */
private const val INTEREST_NAME_MAX_LEN = 16

/**
 * 1:1 port of iOS `Services/GrowthAnalysisCoordinator.swift`：包装 [GrowthAnalysisService]，负责
 * 关系淡化 → LLM 分析 → 软上限缩放写回 → 维度跷跷板 → 兴趣冷却 → 生命阶段检测 → 成长日志 → 元数据更新。
 *
 * **持久化偏差（可逆）**：iOS @Model 逐函数原地写、SwiftData 自动存盘（种子化/淡化在 LLM 调用前就落库）；
 * 这里把工作态解码到局部变量、全部完成后**一次性写回**（与 [com.situ.aichat.prompt.memory.StructuredMemoryCoordinator]
 * 一致，缩小丢更新窗口）。代价：LLM 失败时本轮种子化/淡化不落库 → 下次分析重算（幂等：decay 用 max(floor,
 * 原值+delta) 从原值重算，种子按名去重）→ 收敛到同一终态，仅失败连发期间 prompt 显示未淡化值。
 */
@Singleton
class GrowthAnalysisCoordinator @Inject constructor(
    private val service: GrowthAnalysisService,
    private val characterDao: CharacterDao,
    private val milestoneDao: MilestoneDao,
    private val characterWriteLock: CharacterWriteLock,
    private val settingsRepo: SettingsRepository,
    private val throttleStore: MaintenanceThrottleStore,
) {

    /**
     * 闲置角色关系淡化扫（14.7b，1:1 iOS `AppBootstrapService.checkAndApplyRelationshipDecay`）。
     * iOS 每 24h 启动维护遍历**所有**角色应用关系淡化，与「聊天后分析前淡化」是两条独立调用点；安卓此前只有
     * 聊天驱动的 [analyzeAndPersist] 路（无消息即抛 NoMessages 返回）→ **从不/极少聊天的角色永不淡化**。本扫补齐：
     * 不调 LLM，纯按 lastChatDate 应用既有 [applyRelationshipDecay] 规则、逐角色列级写回。
     *
     * 节流 24h（[MaintenanceThrottleStore]）+ growthSystemEnabled 门控（关则**不**记 markRun，下次启动再判，对齐 iOS
     * 守卫早返回不写 lastCheck）。每角色在 [CharacterWriteLock] 内 fresh 读后改写，与聊天分析 / 计数器递增不打架。
     */
    suspend fun runIdleRelationshipDecay(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (!throttleStore.isDue(MaintenanceThrottleStore.KEY_RELATIONSHIP_DECAY, MaintenanceThrottleStore.DAY_MS, now)) {
            return
        }
        if (!settingsRepo.getAppSettings().growthSystemEnabled) return // 不 markRun（对齐 iOS guard 早返回）
        val characters = characterDao.getAll()
        var decayedCount = 0
        for (character in characters) {
            if (decayOneCharacter(character.uuid, now, zone)) decayedCount++
        }
        throttleStore.markRun(MaintenanceThrottleStore.KEY_RELATIONSHIP_DECAY, now)
        if (decayedCount > 0) Log.i("GrowthAnalysis", "闲置淡化扫：$decayedCount 个角色关系维度自然衰减")
    }

    /**
     * 对单角色应用关系淡化并列级写回；有变更返回 true。锁内 fresh 读 → 复用 [applyRelationshipDecay]（与聊天分析前
     * 用的同一规则/同一幂等）→ 仅写关系/元数据/日志 3 列。无变更（在宽限期/同日已淡化/已触底）则不写、返回 false。
     */
    private suspend fun decayOneCharacter(characterUuid: String, now: Long, zone: ZoneId): Boolean =
        characterWriteLock.withCharacterLock(characterUuid) {
            val character = characterDao.getByUuid(characterUuid) ?: return@withCharacterLock false
            val currentRelationship = milestoneDao.getForCharacter(characterUuid).lastOrNull()?.relationshipName
            val beforeQuality = character.relationshipQuality
            val beforeMetadata = character.growthMetadata
            val growthLog = character.growthLog.toMutableList()
            val (quality, metadata) = applyRelationshipDecay(
                beforeQuality, beforeMetadata, character.lastChatDate, currentRelationship,
                character.relationshipArchetypeId, growthLog, now, zone,
            )
            if (quality == beforeQuality && metadata == beforeMetadata) return@withCharacterLock false
            characterDao.updateRelationshipDecay(
                uuid = characterUuid,
                relationshipQuality = GrowthJson.encode(quality),
                growthMetadata = GrowthJson.encode(metadata),
                growthLog = GrowthJson.encodeGrowthLog(growthLog),
            )
            true
        }

    /**
     * 执行成长分析并将结果一次性写回角色。失败抛出（调用方静默处理）。
     * **P12.6 D1**：整个「读-LLM-写」在 [CharacterWriteLock] 内串行（锁内重读最新角色），写回改用列级 UPDATE
     * （只写性格/关系/兴趣/成长元数据/成长日志 5 列），消除与计数器递增 / 其它分析的并发覆盖。锁内含 LLM 调用，
     * 同角色计数器会等这几秒——单用户本地 App 可接受、且更贴 iOS 全串行语义；链式关系评估在本函数返回（释放锁）后进行，无嵌套。
     */
    suspend fun analyzeAndPersist(
        characterUuid: String,
        config: ApiConfigValues,
        userName: String,
        settings: AppSettings,
    ): GrowthAnalysisResult = characterWriteLock.withCharacterLock(characterUuid) {
        val character = characterDao.getByUuid(characterUuid) ?: throw GrowthAnalysisError.NoMessages
        val messages = service.collectMessagesForAnalysis(characterUuid)
        if (messages.isEmpty()) throw GrowthAnalysisError.NoMessages

        val now = System.currentTimeMillis()
        val milestones = milestoneDao.getForCharacter(characterUuid) // 升序 = iOS sortedMilestones
        val currentRelationship = milestones.lastOrNull()?.relationshipName
        val lastMilestoneEstablished = milestones.lastOrNull()?.establishedDate

        // 工作态（各解码一次）
        var spectrum = character.personalitySpectrum
        var quality = character.relationshipQuality
        val interests = character.dynamicInterests.toMutableList()
        var metadata = character.growthMetadata
        val growthLog = character.growthLog.toMutableList()

        // 首次分析：从 initialInterests 种子化动态兴趣
        if (metadata.totalAnalysisCount == 0) {
            seedInitialInterests(character.initialInterests, interests, now)
        }

        // LLM 分析前先执行关系淡化，让淡化后的维度值进入分析提示词
        val decayed = applyRelationshipDecay(quality, metadata, character.lastChatDate, currentRelationship, character.relationshipArchetypeId, growthLog, now)
        quality = decayed.first
        metadata = decayed.second

        // LLM 分析（失败上抛；本轮种子化/淡化未落库 → 见类注释偏差说明）
        val result = service.analyzeGrowth(
            messages = messages,
            characterName = character.name,
            spectrum = spectrum,
            quality = quality,
            interests = interests,
            config = config,
            userName = userName,
            scheduleSystemEnabled = settings.scheduleSystemEnabled,
            characterUuid = characterUuid,
            nowMillis = now,
        )

        // 应用分析结果
        spectrum = applyPersonalityChanges(result.personalityChanges, spectrum)
        quality = applyRelationshipChanges(result.relationshipChanges, quality)
        applyNewInterests(result.newInterests, interests, now)
        applyInterestHeatChanges(result.interestHeatChanges, interests, now)
        val interplay = applyDimensionInterplay(quality, spectrum)
        quality = interplay.first
        spectrum = interplay.second
        coolDownStaleInterests(interests, settings.interestCooldownDays, now)
        metadata = detectPhaseTransition(quality, metadata, lastMilestoneEstablished, character.lastChatDate, growthLog, now)
        appendGrowthLog(result.events, result.narrative, growthLog, settings.growthLogMaxCount, now)
        metadata = updateMetadata(metadata, now)

        // 一次性列级写回（P12.6 D1：只写自己这 5 列，不整行覆盖）
        characterDao.updateGrowthAnalysis(
            uuid = characterUuid,
            personalitySpectrum = GrowthJson.encode(spectrum),
            relationshipQuality = GrowthJson.encode(quality),
            dynamicInterests = GrowthJson.encodeDynamicInterests(interests),
            growthMetadata = GrowthJson.encode(metadata),
            growthLog = GrowthJson.encodeGrowthLog(growthLog),
        )
        // 观测点（真机验直接看；对齐 M05 向量层的 Logcat 风格）
        Log.i("GrowthAnalysis", "✓ ${character.name}: 性格${result.personalityChanges.size}项 关系${result.relationshipChanges.size}项 新兴趣${result.newInterests.size}个 阶段=${metadata.currentPhase} 总分析#${metadata.totalAnalysisCount} | ${result.narrative}")
        result
    }

    // MARK: - 种子化初始兴趣

    /** 首次分析前，把角色的 initialInterests 转为动态兴趣（按填写顺序分层热度，去重）。名取前 16 字兜底。 */
    internal fun seedInitialInterests(initialInterests: String, interests: MutableList<DynamicInterest>, now: Long) {
        if (initialInterests.isEmpty()) return
        val names = initialInterests.split(Regex("[,，、]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(INTEREST_NAME_MAX_LEN) }
        if (names.isEmpty()) return

        val existingNames = interests.map { it.name.lowercase() }.toSet()
        val newNames = names.filter { it.lowercase() !in existingNames }
        for ((index, name) in newNames.withIndex()) {
            val heat = when (index) {
                0, 1 -> 70   // 最先想到的，核心爱好
                2, 3 -> 60   // 比较重要
                else -> 50   // 补充填写
            }
            interests.add(DynamicInterest(name = name, heat = heat, discoveredDate = now, lastMentionedDate = now, isFromInitial = true))
        }
    }

    // MARK: - 应用变化

    private fun applyPersonalityChanges(changes: Map<String, Int>, spectrum: PersonalitySpectrum): PersonalitySpectrum {
        if (changes.isEmpty()) return spectrum
        var s = spectrum
        val keys = PersonalitySpectrum.DIMENSION_KEYS
        for ((key, delta) in changes) {
            val index = keys.indexOf(key)
            if (index < 0) continue
            val current = s.values[index]
            s = s.setValue(index, current + scaledDelta(current, delta))
        }
        return s
    }

    private fun applyRelationshipChanges(changes: Map<String, Int>, quality: RelationshipQuality): RelationshipQuality {
        if (changes.isEmpty()) return quality
        var q = quality
        val keys = RelationshipQuality.DIMENSION_KEYS
        for ((key, delta) in changes) {
            val index = keys.indexOf(key)
            if (index < 0) continue
            val current = q.values[index]
            q = q.setValue(index, current + scaledDelta(current, delta))
        }
        return q
    }

    /** 添加新发现的兴趣（去重，名取前 16 字兜底——提示词已要求 AI 返回简短名，这里仅防长句意外落库）。 */
    internal fun applyNewInterests(newInterests: List<GrowthAnalysisResult.NewInterest>, interests: MutableList<DynamicInterest>, now: Long) {
        if (newInterests.isEmpty()) return
        val existingNames = interests.map { it.name.lowercase() }.toSet()
        for (item in newInterests) {
            if (item.name.lowercase() in existingNames) continue
            interests.add(DynamicInterest(name = item.name.take(INTEREST_NAME_MAX_LEN), heat = item.initialHeat, discoveredDate = now, lastMentionedDate = now, isFromInitial = false))
        }
    }

    /** 更新已有兴趣的热度（增量 + 非线性缩放，更新 lastMentionedDate）。 */
    private fun applyInterestHeatChanges(changes: Map<String, Int>, interests: MutableList<DynamicInterest>, now: Long) {
        if (changes.isEmpty()) return
        for (i in interests.indices) {
            val delta = changes[interests[i].name] ?: continue
            val adjusted = scaledDelta(interests[i].heat, delta)
            interests[i] = interests[i].copy(
                heat = (interests[i].heat + adjusted).coerceIn(0, 100),
                lastMentionedDate = now,
            )
        }
    }

    /** 冷却长时间未提及的兴趣（分层固定衰减，不经 scaledDelta），热度 ≤0 删除。 */
    private fun coolDownStaleInterests(interests: MutableList<DynamicInterest>, cooldownDays: Int, now: Long) {
        val cooldownMillis = cooldownDays.toLong() * 86_400_000L
        for (i in interests.indices) {
            val elapsed = now - interests[i].lastMentionedDate
            if (elapsed > cooldownMillis) {
                val decay = when {
                    interests[i].heat >= 80 -> 3   // 深度爱好，不容易忘
                    interests[i].heat >= 50 -> 5   // 普通兴趣，正常衰减
                    else -> 8                      // 浅层兴趣，快速冷却
                }
                interests[i] = interests[i].copy(heat = maxOf(0, interests[i].heat - decay))
            }
        }
        interests.removeAll { it.heat <= 0 }
    }

    // MARK: - 维度跷跷板（顺序求值：后规则看见前规则的修改，对齐 iOS）

    private fun applyDimensionInterplay(quality: RelationshipQuality, spectrum: PersonalitySpectrum): Pair<RelationshipQuality, PersonalitySpectrum> {
        var q = quality
        var s = spectrum
        val rKeys = RelationshipQuality.DIMENSION_KEYS
        val pKeys = PersonalitySpectrum.DIMENSION_KEYS

        // 规则1：亲密压力——亲近+依恋均高 → 张力 +1
        if (q.closeness >= 75 && q.attachment >= 75) {
            val idx = rKeys.indexOf("tension")
            if (idx >= 0) q = q.setValue(idx, q.values[idx] + scaledDelta(q.values[idx], 1))
        }
        // 规则2：新鲜感衰退——熟悉度高+趣味性高 → 趣味性 -1
        if (q.familiarity >= 80 && q.funValue >= 70) {
            val idx = rKeys.indexOf("fun")
            if (idx >= 0) q = q.setValue(idx, q.values[idx] + scaledDelta(q.values[idx], -1))
        }
        // 规则3：信任-坦诚联动——信任高 → 坦诚度 +1
        if (q.trust >= 70) {
            val idx = pKeys.indexOf("openness")
            if (idx >= 0) s = s.setValue(idx, s.values[idx] + scaledDelta(s.values[idx], 1))
        }
        // 规则4：高张力催化依恋——张力高 → 依恋 +1（相爱相杀）
        if (q.tension >= 60) {
            val idx = rKeys.indexOf("attachment")
            if (idx >= 0) q = q.setValue(idx, q.values[idx] + scaledDelta(q.values[idx], 1))
        }
        return q to s
    }

    // MARK: - 生命阶段检测

    private fun detectPhaseTransition(
        quality: RelationshipQuality,
        metadata: GrowthAnalysisMetadata,
        lastMilestoneEstablished: Long?,
        lastChatDate: Long?,
        growthLog: MutableList<GrowthLogEntry>,
        now: Long,
    ): GrowthAnalysisMetadata {
        val oldPhase = metadata.currentPhase
        val nowInstant = Instant.ofEpochMilli(now)

        val recentMilestone = lastMilestoneEstablished?.let {
            ChronoUnit.DAYS.between(Instant.ofEpochMilli(it), nowInstant) < 14
        } ?: false
        val daysSinceLastChat = lastChatDate?.let {
            ChronoUnit.DAYS.between(Instant.ofEpochMilli(it), nowInstant).toInt()
        } ?: 999

        // 按优先级检测（越特殊越先）
        val newPhase: String? = when {
            recentMilestone || (quality.funValue >= 70 && quality.tension < 30) -> "honeymoon"
            quality.tension >= 40 && quality.closeness >= 50 -> "adjustment"
            (quality.funValue < 40 && quality.familiarity >= 70) || daysSinceLastChat >= 5 -> "fatigue"
            quality.familiarity >= 65 && quality.trust >= 65 && quality.tension < 30 -> "stability"
            (oldPhase == "adjustment" || oldPhase == "fatigue") && quality.tension < 30 && quality.closeness >= 50 -> "breakthrough"
            else -> {
                // 突破期 14 天时限：到期后按趣味性过渡到蜜月/稳定，否则维持
                if (oldPhase == "breakthrough") {
                    val entered = metadata.phaseEnteredDate
                    if (entered != null && ChronoUnit.DAYS.between(Instant.ofEpochMilli(entered), nowInstant) >= 14) {
                        if (quality.funValue >= 60) "honeymoon" else "stability"
                    } else {
                        oldPhase
                    }
                } else {
                    oldPhase
                }
            }
        }

        if (newPhase == oldPhase) return metadata

        if (newPhase != null) {
            val phaseName = when (newPhase) {
                "honeymoon" -> "蜜月期"
                "adjustment" -> "磨合期"
                "stability" -> "稳定期"
                "fatigue" -> "倦怠期"
                "breakthrough" -> "突破期"
                else -> newPhase
            }
            growthLog.add(GrowthLogEntry(timestamp = now, type = GrowthEventType.RELATIONSHIP_CHANGE, summary = "关系进入$phaseName"))
            trimLog(growthLog, 100)
        }
        return metadata.copy(currentPhase = newPhase, phaseEnteredDate = now)
    }

    /** 追加成长日志并裁剪到上限。无 event 但有 narrative 也记一条 majorEvent。 */
    private fun appendGrowthLog(
        events: List<GrowthAnalysisResult.GrowthEvent>,
        narrative: String,
        growthLog: MutableList<GrowthLogEntry>,
        maxCount: Int,
        now: Long,
    ) {
        for (event in events) {
            growthLog.add(GrowthLogEntry(timestamp = now, type = event.type, summary = event.summary))
        }
        if (events.isEmpty() && narrative.isNotEmpty()) {
            growthLog.add(GrowthLogEntry(timestamp = now, type = GrowthEventType.MAJOR_EVENT, summary = narrative))
        }
        trimLog(growthLog, maxCount)
    }

    // MARK: - 关系淡化（不聊天时维度自然衰减）

    private fun applyRelationshipDecay(
        quality: RelationshipQuality,
        metadata: GrowthAnalysisMetadata,
        lastChatDate: Long?,
        currentRelationship: String?,
        relationshipArchetypeId: String?,
        growthLog: MutableList<GrowthLogEntry>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<RelationshipQuality, GrowthAnalysisMetadata> {
        val lastChat = lastChatDate ?: return quality to metadata

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val lastChatDay = Instant.ofEpochMilli(lastChat).atZone(zone).toLocalDate()
        val rawInactiveDays = ChronoUnit.DAYS.between(lastChatDay, today).toInt()
        val inactiveDays = rawInactiveDays.coerceIn(0, 30) // 封顶 30 防极端

        if (inactiveDays <= 3) return quality to metadata // 3 天宽限期

        // 同一天不重复淡化
        val lastDecay = metadata.lastDecayAppliedDate
        if (lastDecay != null && Instant.ofEpochMilli(lastDecay).atZone(zone).toLocalDate() == today) {
            return quality to metadata
        }

        // 本次应衰减天数（非总天数，而是距上次淡化的增量；首次补齐宽限后所有天数）
        val newDecayDays: Int = if (lastDecay != null) {
            val lastDecayDay = Instant.ofEpochMilli(lastDecay).atZone(zone).toLocalDate()
            ChronoUnit.DAYS.between(lastDecayDay, today).toInt().coerceAtLeast(0)
        } else {
            inactiveDays - 3
        }
        if (newDecayDays <= 0) return quality to metadata

        val eqPoint = equilibriumPoint(currentRelationship)
        val dynamicFloor = maxOf(10, eqPoint - 10) // 平衡点下浮 10，最低 10（未识别原型走此 legacy 地板）
        val archetype = relationshipArchetypeId?.let { RelationshipArchetype.byId(it) }
        val q = computeDecayedQuality(quality, inactiveDays, newDecayDays, dynamicFloor, archetype)
        if (q == quality) return quality to metadata

        growthLog.add(GrowthLogEntry(timestamp = now, type = GrowthEventType.RELATIONSHIP_CHANGE, summary = "因${inactiveDays}天未互动，关系维度自然衰减"))
        trimLog(growthLog, 100)
        Log.i("GrowthAnalysis", "关系淡化: ${inactiveDays}天未互动 (本次衰减${newDecayDays}天, floor=${archetype?.id ?: dynamicFloor})")

        val todayStartMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        return q to metadata.copy(lastDecayAppliedDate = todayStartMillis)
    }

    /** 更新成长分析元数据。 */
    private fun updateMetadata(metadata: GrowthAnalysisMetadata, now: Long): GrowthAnalysisMetadata =
        metadata.copy(lastAnalysisDate = now, roundsSinceLastAnalysis = 0, totalAnalysisCount = metadata.totalAnalysisCount + 1)

    private fun trimLog(log: MutableList<GrowthLogEntry>, maxCount: Int) {
        if (log.size > maxCount) {
            val kept = log.takeLast(maxCount)
            log.clear()
            log.addAll(kept)
        }
    }
}

// MARK: - 纯数学（internal，便于单测；对齐 iOS GrowthAnalysisCoordinator 的 static 方法）

/**
 * 根据当前维度值缩放 LLM 给出的 delta，实现「高段位增长慢、跌落快」的软上限。缩放后绝对值至少为 1。
 * 性格/关系/兴趣热度共用。
 */
internal fun scaledDelta(current: Int, rawDelta: Int): Int {
    if (rawDelta == 0) return 0
    val scale: Double = if (rawDelta > 0) {
        when {
            current >= 80 -> 0.3
            current >= 60 -> 0.6
            current < 20 -> 1.5  // 低端加速恢复
            else -> 1.0
        }
    } else {
        when {
            current >= 80 -> 2.0
            current >= 60 -> 1.5
            current < 20 -> 0.5  // 低端保护
            else -> 1.0
        }
    }
    val scaled = rawDelta * scale
    return if (scaled > 0) maxOf(1, ceil(scaled).toInt()) else minOf(-1, floor(scaled).toInt())
}

/**
 * 关系淡化的纯计算（图纸 D-6·T2-3 直测）：4 衰减维（熟悉/亲近/默契/趣味）startDay=3 + 张力 startDay=7；
 * **「界内才衰、到界停、界外不动」守卫**（V-b：修旧 `maxOf(floor, current-days)` 把手调界外低值抬回地板的反向涨分缺陷）；
 * 地板 = 识别出原型时 `max(该维原型地板, 5)`，未识别时 legacy [dynamicFloor]（张力恒固定地板 5·独立不吃原型）；
 * 依恋规则不变（3–7 天想念 +1/天、>7 天 −1/天至 5）；trust/respect 恒不衰（不在衰减维内）。
 */
internal fun computeDecayedQuality(
    quality: RelationshipQuality,
    inactiveDays: Int,
    newDecayDays: Int,
    dynamicFloor: Int,
    archetype: RelationshipArchetype?,
): RelationshipQuality {
    var q = quality
    val keys = RelationshipQuality.DIMENSION_KEYS
    fun decayDim(dim: String, startDay: Int, floor: Int) {
        if (inactiveDays <= startDay) return
        val i = keys.indexOf(dim)
        val current = q.values[i]
        if (current <= floor) return // 到界停 / 界外不动（不抬回地板）
        q = q.setValue(i, maxOf(floor, current - newDecayDays))
    }
    for (dim in listOf("familiarity", "closeness", "rapport", "fun")) {
        val floor = if (archetype != null) maxOf(archetype.floors[keys.indexOf(dim)], 5) else dynamicFloor
        decayDim(dim, startDay = 3, floor = floor)
    }
    decayDim("tension", startDay = 7, floor = 5)
    // 依恋特殊（规则不变）：3–7 天想念累加，>7 天淡化至 5。
    val ai = keys.indexOf("attachment")
    val cur = q.values[ai]
    val newAtt = if (inactiveDays <= 7) minOf(cur + newDecayDays, 100) else maxOf(5, cur - newDecayDays)
    if (newAtt != cur) q = q.setValue(ai, newAtt)
    return q
}

/** 根据当前关系名称计算维度的自然平衡点（淡化时维度向此值回落而非跌到底）。 */
internal fun equilibriumPoint(currentRelationship: String?): Int {
    val rel = currentRelationship?.lowercase() ?: return 35
    val intimate = listOf("恋人", "热恋", "老夫老妻", "灵魂伴侣", "伴侣", "爱人", "lover", "partner", "soulmate")
    if (intimate.any { rel.contains(it) }) return 70
    val close = listOf("好朋友", "死党", "闺蜜", "知己", "暧昧", "损友", "best friend", "close friend")
    if (close.any { rel.contains(it) }) return 55
    val friend = listOf("朋友", "普通朋友", "网友", "friend")
    if (friend.any { rel.contains(it) }) return 40
    val distant = listOf("陌生人", "点头之交", "stranger")
    if (distant.any { rel.contains(it) }) return 20
    return 35
}
