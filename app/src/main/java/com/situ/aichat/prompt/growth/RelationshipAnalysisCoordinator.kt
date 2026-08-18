package com.situ.aichat.prompt.growth

import android.util.Log
import com.situ.aichat.relationship.MilestoneCelebrationNotifier
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.data.model.growthLog
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.economy.MilestoneRewardService
import com.situ.aichat.data.remote.llm.ApiConfigValues
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1:1 port of iOS `RelationshipAnalysisCoordinator`（RelationshipAnalysisService.swift 内）：调用
 * [RelationshipAnalysisService] → 关系变化时写一条 [MilestoneEntity]（triggerType + phase）→ 追加成长日志、
 * 计数处理、记录评估时间戳。AI 自动评估和用户手动推进共用（[triggerTypeRaw] 区分）。
 *
 * **延后 P9**：里程碑 200 金币奖励（`MilestoneRewardService`）依赖钱包/货币系统，本步留 TODO。
 * **并发**：写回从「起始读」基底 copy（与 [GrowthAnalysisCoordinator] / StructuredMemoryCoordinator 一致）。
 * 高频每消息计数递增已由 ChatViewModel 的 `characterMetaMutex` 串行化；分析写回（秒级 LLM 后）与并发递增/
 * 另一分析写回的罕见竞态 = 已知限制 → P12 健壮性（每角色写锁/actor 覆盖「重读-合并-写」）。
 */
@Singleton
class RelationshipAnalysisCoordinator @Inject constructor(
    private val service: RelationshipAnalysisService,
    private val growthService: GrowthAnalysisService,
    private val characterDao: CharacterDao,
    private val milestoneDao: MilestoneDao,
    private val milestoneRewardService: MilestoneRewardService,
    private val characterWriteLock: CharacterWriteLock,
    private val celebrationNotifier: MilestoneCelebrationNotifier,
    // 成长原型校准（图纸 §3.3 入口②）：AI 升档后抬分到原型地板（已持锁 → 用 HoldingLock 变体）。
    private val archetypeCalibrator: RelationshipArchetypeCalibrator,
) {

    /**
     * 执行关系评估；关系变化时创建新里程碑并写回。[triggerTypeRaw] = "aiAutomatic" / "userAdvance"。
     * 失败抛出（调用方静默处理）。
     * **P12.6 D1**：整个「读-LLM-写」在 [CharacterWriteLock] 内串行；回写改用列级 UPDATE（changed 写
     * 成长日志/关系计数/评估时间 + 清心意文案戳；unchanged 写关系计数/评估时间），消除与成长分析/计数器的并发覆盖。
     * 里程碑插入/金币奖励为独立表，不在角色锁竞态内。
     */
    suspend fun analyzeAndPersist(
        characterUuid: String,
        config: ApiConfigValues,
        userName: String,
        triggerTypeRaw: String,
    ): RelationshipAnalysisResult = characterWriteLock.withCharacterLock(characterUuid) {
        val character = characterDao.getByUuid(characterUuid) ?: throw RelationshipAnalysisError.NoMessages
        val messages = growthService.collectMessagesForAnalysis(characterUuid)
        if (messages.isEmpty()) throw RelationshipAnalysisError.NoMessages

        val milestones = milestoneDao.getForCharacter(characterUuid) // 升序
        val currentRelationshipName = milestones.lastOrNull()?.relationshipName
        val currentRelationship = currentRelationshipName ?: "未设定" // 提示词兜底「未设定」
        val currentPhase = milestones.lastOrNull()?.phase

        val result = service.analyzeRelationship(
            messages = messages,
            characterName = character.name,
            currentRelationship = currentRelationship,
            currentPhase = currentPhase,
            quality = character.relationshipQuality,
            milestones = milestones,
            config = config,
            userName = userName,
        )

        val now = System.currentTimeMillis()
        if (result.changed) {
            // 先存旧展示串（插入新里程碑后 current 立即指向新值）。日志旧关系兜底用「未知」（对齐 iOS，与提示词的「未设定」区分）
            val oldDisplay = composeRelationshipDisplay(currentRelationshipName ?: "未知", currentPhase)
            val newDisplay = composeRelationshipDisplay(result.newRelationship, result.newPhase)

            // 关系变化必插新里程碑（含 phase）——不能复用 recordRelationship 的「同名跳过」守卫：仅时期变也算变。
            milestoneDao.upsert(
                MilestoneEntity(
                    uuid = UUID.randomUUID().toString(),
                    characterUuid = characterUuid,
                    relationshipName = result.newRelationship,
                    establishedDate = now,
                    reason = result.reason,
                    triggerTypeRaw = triggerTypeRaw,
                    phase = result.newPhase,
                ),
            )
            // 成长原型校准（图纸 §3.3 入口②）：AI 升档后抬分到新原型地板（已持锁 → HoldingLock 变体；
            // AI 路恒 applyCeilings=false，AI 降档绝不回拉；时期-only 同名变化 = 幂等重写同 id，无害）。
            archetypeCalibrator.calibrateHoldingLock(
                characterUuid,
                result.newRelationship,
                applyFloors = true,
                applyCeilings = false,
            )
            // P9.1c 关系里程碑 200 金币奖励（去重入账；establishedDate=now，对齐 iOS RelationshipAnalysisService:348）。
            milestoneRewardService.rewardRelationshipMilestone(characterUuid, character.name, result.newRelationship, now)

            // 追加成长日志（iOS 此处不裁剪，下次成长分析的 appendGrowthLog 会裁到上限）
            val log = character.growthLog.toMutableList()
            log.add(
                GrowthLogEntry(
                    timestamp = now,
                    type = GrowthEventType.RELATIONSHIP_CHANGE,
                    summary = "关系变化：$oldDisplay → $newDisplay（${result.reason}）",
                ),
            )
            characterDao.updateRelationshipChanged(
                uuid = characterUuid,
                growthLog = GrowthJson.encodeGrowthLog(log),
                messageCount = computeNextMessageCount(character.relationshipMessageCount, true),
                analysisDate = now,
            )
            Log.i("RelationshipAnalysis", "✓ ${character.name}: $oldDisplay → $newDisplay (${result.reason})")

            // P1-33 里程碑庆祝（纯旁路·失败不影响写回）：用插入新条**之前**的旧历史快照（milestones）判定
            // 「仅 aiAutomatic + 首次达到 + 高于历史最高」，前台 toast / 后台系统通知。放 coordinator 一处
            // 天然覆盖聊天与语音两路触发；recordRelationship（初始设定）不经此处=不通知。
            runCatching {
                celebrationNotifier.onMilestoneAchieved(
                    characterUuid = characterUuid,
                    characterName = character.name,
                    historyNames = milestones.map { it.relationshipName },
                    newName = result.newRelationship,
                    triggerTypeRaw = triggerTypeRaw,
                )
            }.onFailure { Log.w("RelationshipAnalysis", "里程碑庆祝通知失败（不影响写回）", it) }
        } else {
            characterDao.updateRelationshipUnchanged(
                uuid = characterUuid,
                messageCount = computeNextMessageCount(character.relationshipMessageCount, false),
                analysisDate = now,
            )
            Log.i("RelationshipAnalysis", "✓ ${character.name}: 无变化")
        }
        result
    }
}

/**
 * 关系评估完成后的消息计数处理（纯函数，internal 可测）：changed=true → 归零；changed=false → -15 而非归零
 * （保留累积，否则评估「无变化」会让链式触发反复要求积满 30 轮）。对齐 iOS computeNextMessageCount。
 */
internal fun computeNextMessageCount(current: Int, changed: Boolean): Int = if (changed) 0 else maxOf(0, current - 15)
