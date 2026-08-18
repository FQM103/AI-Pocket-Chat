package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.util.DateFormatters
import java.time.ZoneId
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * 宠物养护引擎（1:1 iOS `PetCareService`）：喂食/清洁/玩耍/聊天收益、惰性时间衰减、忽略阶段推进、
 * 治疗/寻找、信任恢复、性格加成、技能学习。
 *
 * Android 适配：iOS 的 `CharacterPet` 是可变 @Model 原地改 + SwiftData 自动存；Android 的
 * [CharacterPetEntity] 是不可变 Room 行，故所有操作是**纯函数**（entity 进 → 新 entity 出，调用方负责
 * upsert），内部用可变工作对象 [PetMut] 逐行复刻 iOS。`now` 注入便于确定性单测（替 iOS `Date()`）。
 * 金币奖励（进化/里程碑）由调用方在 P9 货币系统接入。
 */
object PetCareService {

    data class TrickResult(val pet: CharacterPetEntity, val learnedName: String?)

    /** 寻找结果（1:1 iOS `PetSearchResult`）。 */
    sealed interface SearchResult {
        val pet: CharacterPetEntity
        data class Found(override val pet: CharacterPetEntity) : SearchResult
        data class Searching(override val pet: CharacterPetEntity, val attempts: Int, val needed: Int) : SearchResult
    }

    // MARK: - 照顾操作

    fun feed(pet: CharacterPetEntity, settings: AppSettings, now: Long = System.currentTimeMillis()): CharacterPetEntity {
        val m = PetMut(pet)
        val baseReduction = applyTrustMultiplier(m, 30)
        m.hunger = max(0, m.hunger - baseReduction)
        m.lastFedDate = now
        m.growthPoints += settings.petGrowthPointsPerFeed
        m.totalInteractions += 1
        m.lastInteractionDate = now
        val bonus = personalityBonus(PetGrowthEventType.FED, m.personality)
        if (bonus.stat != 0) m.hunger = max(0, m.hunger - bonus.stat)
        m.growthPoints += bonus.growth
        advanceTrustRecovery(m, now)
        recoverNeglect(m, now)
        m.appendLog(PetGrowthEventType.FED, "喂食了${m.species.displayName}", now)
        return m.toEntity()
    }

    fun clean(pet: CharacterPetEntity, settings: AppSettings, now: Long = System.currentTimeMillis()): CharacterPetEntity {
        val m = PetMut(pet)
        val baseIncrease = applyTrustMultiplier(m, 30)
        m.cleanliness = min(100, m.cleanliness + baseIncrease)
        m.lastCleanedDate = now
        m.growthPoints += settings.petGrowthPointsPerClean
        m.totalInteractions += 1
        m.lastInteractionDate = now
        val bonus = personalityBonus(PetGrowthEventType.CLEANED, m.personality)
        if (bonus.stat != 0) m.cleanliness = min(100, m.cleanliness + bonus.stat)
        if (bonus.happiness != 0) m.happiness = min(100, m.happiness + bonus.happiness)
        m.growthPoints += bonus.growth
        advanceTrustRecovery(m, now)
        recoverNeglect(m, now)
        m.appendLog(PetGrowthEventType.CLEANED, "给${m.species.displayName}洗了澡", now)
        return m.toEntity()
    }

    fun play(pet: CharacterPetEntity, settings: AppSettings, now: Long = System.currentTimeMillis()): CharacterPetEntity {
        val m = PetMut(pet)
        val baseIncrease = applyTrustMultiplier(m, 25)
        m.happiness = min(100, m.happiness + baseIncrease)
        m.lastPlayedDate = now
        m.growthPoints += settings.petGrowthPointsPerPlay
        m.totalInteractions += 1
        m.lastInteractionDate = now
        val bonus = personalityBonus(PetGrowthEventType.PLAYED, m.personality)
        if (bonus.stat != 0) m.happiness = min(100, m.happiness + bonus.stat)
        m.growthPoints += bonus.growth
        m.metadata = m.metadata.copy(playCount = m.metadata.playCount + 1)
        advanceTrustRecovery(m, now)
        recoverNeglect(m, now)
        m.appendLog(PetGrowthEventType.PLAYED, "和${m.species.displayName}玩耍了", now)
        return m.toEntity()
    }

    /** 聊天互动加成（聊天服务里调，轻微辅助成长）。 */
    fun chatInteraction(pet: CharacterPetEntity, settings: AppSettings, now: Long = System.currentTimeMillis()): CharacterPetEntity {
        val m = PetMut(pet)
        m.growthPoints += settings.petGrowthPointsPerChat
        m.lastInteractionDate = now
        return m.toEntity()
    }

    /** 玩耍后检查解锁新技能；学会返回中文名（每个只学一次）。1:1 iOS `checkAndLearnTrick`。 */
    fun learnTrickIfUnlocked(pet: CharacterPetEntity, now: Long = System.currentTimeMillis()): TrickResult {
        val m = PetMut(pet)
        for (milestone in PetTrickMilestones.milestones) {
            if (m.metadata.playCount >= milestone.plays && !m.metadata.learnedTricks.contains(milestone.trickId)) {
                m.metadata = m.metadata.copy(learnedTricks = m.metadata.learnedTricks + milestone.trickId)
                m.appendLog(PetGrowthEventType.TRICK_LEARNED, "${m.name}学会了${milestone.name}！", now)
                return TrickResult(m.toEntity(), milestone.name)
            }
        }
        return TrickResult(pet, null)
    }

    // MARK: - 治疗（仅 .sick）

    fun treat(pet: CharacterPetEntity, settings: AppSettings, now: Long = System.currentTimeMillis()): CharacterPetEntity {
        if (PetNeglectPhase.fromRaw(pet.neglectPhaseRaw) != PetNeglectPhase.SICK) return pet
        val m = PetMut(pet)
        m.health = min(100, m.health + 25)
        m.growthPoints += settings.petGrowthPointsPerFeed
        m.totalInteractions += 1
        m.lastInteractionDate = now
        val treatmentCount = m.metadata.treatmentCount + 1
        if (treatmentCount >= PetRecoveryThresholds.TREATMENTS_TO_HEAL) {
            m.neglectPhaseRaw = PetNeglectPhase.NONE.raw
            m.health = max(m.health, 60)
            m.metadata = m.metadata.copy(treatmentCount = 0)
            m.appendLog(PetGrowthEventType.TREATED, "${m.name}治愈了！", now)
        } else {
            m.metadata = m.metadata.copy(treatmentCount = treatmentCount)
            m.appendLog(PetGrowthEventType.TREATED, "给${m.name}治疗了（$treatmentCount/${PetRecoveryThresholds.TREATMENTS_TO_HEAL}）", now)
        }
        return m.toEntity()
    }

    // MARK: - 寻找（仅 .ranAway）

    fun searchForPet(pet: CharacterPetEntity, now: Long = System.currentTimeMillis()): SearchResult {
        if (PetNeglectPhase.fromRaw(pet.neglectPhaseRaw) != PetNeglectPhase.RAN_AWAY) {
            return SearchResult.Searching(pet, 0, PetRecoveryThresholds.ATTEMPTS_TO_FIND)
        }
        val m = PetMut(pet)
        val attempts = m.metadata.searchAttempts + 1
        var md = m.metadata.copy(searchAttempts = attempts)
        if (md.searchStartDate == null) md = md.copy(searchStartDate = now)
        m.metadata = md
        return if (attempts >= PetRecoveryThresholds.ATTEMPTS_TO_FIND) {
            m.neglectPhaseRaw = PetNeglectPhase.SICK.raw
            m.health = 30
            m.happiness = 20
            m.metadata = m.metadata.copy(searchAttempts = 0, searchStartDate = null, trustRecovery = 0.01, treatmentCount = 0)
            m.lastInteractionDate = now
            m.appendLog(PetGrowthEventType.FOUND, "找到了${m.name}！但它状态很差，需要治疗", now)
            SearchResult.Found(m.toEntity())
        } else {
            m.appendLog(PetGrowthEventType.SEARCH_ATTEMPT, "寻找${m.name}（$attempts/${PetRecoveryThresholds.ATTEMPTS_TO_FIND}）", now)
            SearchResult.Searching(m.toEntity(), attempts, PetRecoveryThresholds.ATTEMPTS_TO_FIND)
        }
    }

    // MARK: - 时间衰减（启动/回前台批量调；惰性增量补算）

    /**
     * 惰性增量衰减（1:1 iOS `applyDecay`）。增量从 lastDecayDate 算起，忽略阶段从 lastInteractionDate
     * 绝对时长算。整数截断 + lastDecayDate=now 的写法严格照搬（不保留余数，否则与 iOS 漂移）。
     */
    fun applyDecay(
        pet: CharacterPetEntity,
        settings: AppSettings,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): CharacterPetEntity {
        val lastInteraction = pet.lastInteractionDate ?: return pet
        val m = PetMut(pet)

        val decayBaseDate = m.metadata.lastDecayDate ?: lastInteraction
        val hoursSinceDecay = (now - decayBaseDate) / 3_600_000.0
        if (hoursSinceDecay < 1) return pet

        val hours = min(hoursSinceDecay.toInt(), 720)
        val hungerRate = settings.petHungerDecayPerHour
        val cleanRate = settings.petCleanlinessDecayPerHour
        val happyRate = settings.petHappinessDecayPerHour

        m.hunger = min(100, m.hunger + hours * hungerRate)
        m.cleanliness = max(0, m.cleanliness - hours * cleanRate)
        val happinessLoss = hours * happyRate
        val effectiveHappinessLoss = if (m.personality == PetPersonalityType.INDEPENDENT) max(happinessLoss / 2, 1) else happinessLoss
        m.happiness = max(0, m.happiness - effectiveHappinessLoss)

        if (m.hunger >= 80 || m.cleanliness <= 20) {
            m.health = max(0, m.health - max(hours / 2, 1))
        }

        // 1) 未散步惩罚（calendar 整日数；24h 周期 floor，对齐 iOS Calendar.dateComponents([.day])，DST 边缘忽略）
        val lastWalk = m.metadata.lastWalkDate ?: m.adoptedDate
        val daysSinceWalk = max(0, ((now - lastWalk) / 86_400_000L).toInt())
        if (daysSinceWalk >= 2) {
            val penaltyDays = daysSinceWalk - 1
            m.happiness = max(0, m.happiness - penaltyDays * 3)
            m.health = max(0, m.health - penaltyDays * 2)
        }

        // 2) 过度散步惩罚（当天散步 >3 次）
        val todayWalks = m.metadata.lastWalkCountDate?.let { countDate ->
            if (DateFormatters.startOfDayMillis(countDate, zone) == DateFormatters.startOfDayMillis(now, zone)) m.metadata.dailyWalkCount else 0
        } ?: 0
        if (todayWalks > 3) {
            val extraWalks = todayWalks - 3
            m.hunger = min(100, m.hunger + extraWalks * 3)
            m.cleanliness = max(0, m.cleanliness - extraWalks * 2)
        }

        m.metadata = m.metadata.copy(lastDecayDate = now)

        val hoursSinceInteraction = (now - lastInteraction) / 3_600_000.0
        updateNeglectPhase(m, hoursSinceInteraction, now)
        return m.toEntity()
    }

    // MARK: - 忽略阶段

    /** 按忽略时长推进阶段（只恶化，不靠时间回退减轻）。1:1 iOS `updateNeglectPhase`。 */
    private fun updateNeglectPhase(m: PetMut, hoursSinceLastInteraction: Double, now: Long) {
        val days = hoursSinceLastInteraction / 24.0
        val oldPhase = m.neglectPhase
        val newPhase = when {
            days >= 7 -> PetNeglectPhase.RAN_AWAY
            days >= 5 -> PetNeglectPhase.SICK
            days >= 3 -> PetNeglectPhase.UPSET
            days >= 1 -> PetNeglectPhase.UNHAPPY
            else -> PetNeglectPhase.NONE
        }
        if (newPhase.severity <= oldPhase.severity) return // 只在恶化时更新
        m.neglectPhaseRaw = newPhase.raw
        m.appendLog(PetGrowthEventType.NEGLECT_ADVANCE, "${m.name}${newPhase.displayName}了", now)
    }

    /** 照顾后恢复忽略状态（仅 unhappy/upset 可被普通照顾治愈；sick/ranAway 须专门流程）。 */
    private fun recoverNeglect(m: PetMut, now: Long) {
        val phase = m.neglectPhase
        if (phase != PetNeglectPhase.NONE && phase != PetNeglectPhase.RAN_AWAY && phase != PetNeglectPhase.SICK) {
            m.neglectPhaseRaw = PetNeglectPhase.NONE.raw
            m.appendLog(PetGrowthEventType.NEGLECT_RECOVER, "${m.name}从${phase.displayName}恢复了", now)
        }
    }

    // MARK: - 性格加成

    private data class Bonus(val stat: Int, val happiness: Int, val growth: Int)

    /** 性格对照顾操作的额外加成（1:1 iOS `personalityBonus`）。 */
    private fun personalityBonus(action: PetGrowthEventType, personality: PetPersonalityType): Bonus = when {
        action == PetGrowthEventType.PLAYED && personality == PetPersonalityType.CLINGY -> Bonus(10, 0, 0)
        action == PetGrowthEventType.PLAYED && personality == PetPersonalityType.LIVELY -> Bonus(5, 0, 2)
        action == PetGrowthEventType.CLEANED && personality == PetPersonalityType.LAZY -> Bonus(10, 0, 0)
        action == PetGrowthEventType.CLEANED && personality == PetPersonalityType.TIMID -> Bonus(0, 5, 0)
        else -> Bonus(0, 0, 0)
    }

    // MARK: - 信任恢复

    /** 找回后照顾效果打折（trust∈(0,1) → 0.5+0.5t；0 或 ≥1 不打折）。1:1 iOS `applyTrustMultiplier`。 */
    private fun applyTrustMultiplier(m: PetMut, baseAmount: Int): Int {
        val trust = m.metadata.trustRecovery
        if (!(trust > 0 && trust < 1.0)) return baseAmount
        val multiplier = 0.5 + 0.5 * trust
        return max(1, (baseAmount * multiplier).toInt())
    }

    /** 照顾后递增信任恢复（找回后适用，每次 +0.2，达 1.0 写日志）。1:1 iOS `advanceTrustRecovery`。 */
    private fun advanceTrustRecovery(m: PetMut, now: Long) {
        val trust = m.metadata.trustRecovery
        if (!(trust > 0 && trust < 1.0)) return
        val newTrust = min(1.0, trust + PetRecoveryThresholds.TRUST_RECOVERY_PER_CARE)
        m.metadata = m.metadata.copy(trustRecovery = newTrust)
        if (newTrust >= 1.0) {
            m.appendLog(PetGrowthEventType.NEGLECT_RECOVER, "${m.name}完全信任你了！", now)
        }
    }

    /**
     * 可变工作对象：从不可变 [CharacterPetEntity] 解出可变字段 + 解码 metadata/growthLog，逐行复刻 iOS
     * 原地改，最后 [toEntity] 重新冻结回不可变行（含 growthLog 裁剪至 50）。
     */
    private class PetMut(private val base: CharacterPetEntity) {
        var speciesRaw = base.speciesRaw
        var isHiddenSpecies = base.isHiddenSpecies
        var hunger = base.hunger
        var cleanliness = base.cleanliness
        var happiness = base.happiness
        var health = base.health
        var growthStageRaw = base.growthStageRaw
        var growthPoints = base.growthPoints
        var totalInteractions = base.totalInteractions
        var lastFedDate = base.lastFedDate
        var lastCleanedDate = base.lastCleanedDate
        var lastPlayedDate = base.lastPlayedDate
        var lastInteractionDate = base.lastInteractionDate
        var neglectPhaseRaw = base.neglectPhaseRaw
        var metadata: PetMetadata = base.metadata
        val adoptedDate = base.adoptedDate
        val name = base.name
        private val log = base.growthLog.toMutableList()

        val species: PetSpecies get() = PetSpecies.fromRaw(speciesRaw)
        val personality: PetPersonalityType get() = PetPersonalityType.fromRaw(base.personalityTypeRaw)
        val neglectPhase: PetNeglectPhase get() = PetNeglectPhase.fromRaw(neglectPhaseRaw)

        fun appendLog(type: PetGrowthEventType, summary: String, now: Long) {
            log.add(PetGrowthLogEntry(id = UUID.randomUUID().toString(), timestamp = now, type = type.raw, summary = summary))
        }

        fun toEntity(): CharacterPetEntity {
            val trimmed = if (log.size > 50) log.takeLast(50) else log
            return base.copy(
                speciesRaw = speciesRaw,
                isHiddenSpecies = isHiddenSpecies,
                hunger = hunger,
                cleanliness = cleanliness,
                happiness = happiness,
                health = health,
                growthStageRaw = growthStageRaw,
                growthPoints = growthPoints,
                totalInteractions = totalInteractions,
                lastFedDate = lastFedDate,
                lastCleanedDate = lastCleanedDate,
                lastPlayedDate = lastPlayedDate,
                lastInteractionDate = lastInteractionDate,
                neglectPhaseRaw = neglectPhaseRaw,
                petMetadataJson = PetJson.encodeMetadata(metadata),
                petGrowthLogJson = PetJson.encodeGrowthLog(trimmed),
            )
        }
    }
}
