package com.situ.aichat.prompt.memory

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.promise.PromiseLedgerService
import com.situ.aichat.promise.PromiseReconciliation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消化班车编排（记忆改造一期·图纸 §3.6）：把「素材收集 → 摘要写回（带素材）→ 标记 → 约定对账 → 落库」串成一趟班车，
 * 取代两 Trigger 直接调 [MemorySummaryCoordinator.summarizeAndPersist]。**绝不做触发判定**（冷却 / 双轨仍在两 Trigger）、
 * **绝不改摘要校验链**（那在 [MemorySummaryCoordinator]）。
 *
 * per-角色 [Mutex] 互斥（照 [MemorySummaryCoordinator] 样式·聊天与通话双路不同批双跑·E9）。摘要抛错直接上抛（素材不标记、
 * 账本不动，调用方冷却语义不变·E7）；对账瞬态错误 2s 重试 1 次，仍失败静默放弃本批（摘要成果与素材标记保留·E6）。
 */
@Singleton
class MemoryDigestCoordinator @Inject constructor(
    private val summaryCoordinator: MemorySummaryCoordinator,
    private val memoryService: MemoryService,
    private val materialService: MemoryDigestMaterialService,
    private val contextLog: ContextLogService,
    private val ledger: PromiseLedgerService,
    private val promiseRepository: PromiseRepository,
) {
    private val perCharacterLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * 跑一趟消化班车，返回摘要写回值（= [MemorySummaryCoordinator.summarizeAndPersist] 返回值）。摘要抛错直接上抛。
     * 触发判定 / 重试 / 冷却记录仍在调用方（两 Trigger），本函数只负责一趟班车的编排。
     */
    suspend fun digestAndReconcile(
        character: CharacterEntity,
        conversationUuid: String,
        messages: List<MessageEntity>,
        config: ApiConfigValues,
        settings: AppSettings,
        userName: String,
    ): String = perCharacterLocks.getOrPut(character.uuid) { Mutex() }.withLock {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        // 1. 收集素材（无副作用·失败可无损重收）。
        val material = materialService.collect(character.uuid, userName, settings, now, zone)

        // 2. 摘要写回（带素材·校验链零碰）；抛错直接上抛（素材不标记、账本不动·E7）。参数照 MemoryAnalysisTrigger 现值。
        val summary = summaryCoordinator.summarizeAndPersist(
            character = character,
            messages = messages,
            config = config,
            maxLength = settings.memorySummaryMaxLength,
            customPrompt = settings.memoryExtractionPrompt,
            progressiveCompressionEnabled = settings.progressiveCompressionEnabled,
            characterName = character.name,
            userName = userName,
            extraMaterial = material.text,
            markSummarized = { memoryService.markSummarized(messages) },
        )

        // 3. 摘要成功 → 标记素材已消化（逐条列级 UPDATE + 水位推进·进程死亡只会漏标→下班次重收·E10）。
        materialService.markDigested(character.uuid, material, now)

        // 4. 约定对账（瞬态错误 2s 重试 1 次·仍失败静默放弃本批·摘要成果保留·E6）。open 为空也照跑（新约定提取仍需要）。
        reconcileWithRetry(character, conversationUuid, messages, config, userName, material.text, now, zone)

        summary
    }

    /** 对账一趟：open 清单 + 素材 → 提示词 → LLM（恒一次·+失败重试一次）→ 宽容解析四道闸 → 落库。 */
    private suspend fun reconcileWithRetry(
        character: CharacterEntity,
        conversationUuid: String,
        messages: List<MessageEntity>,
        config: ApiConfigValues,
        userName: String,
        materialText: String,
        now: Long,
        zone: ZoneId,
    ) {
        val open = promiseRepository.openByCharacter(character.uuid)
        val fullMaterial = MemoryService.formatMessages(
            messages,
            userLabel = userName.ifBlank { "用户" },
            charLabel = character.name.ifBlank { "角色" },
        ) + if (materialText.isNotBlank()) "\n\n" + materialText else ""
        val prompt = PromiseReconciliation.buildPrompt(
            charName = character.name,
            userName = userName,
            nowText = MemoryService.formatTimestamp(now),
            open = open,
            materialText = fullMaterial,
            zone = zone,
        )

        suspend fun runOnce() {
            val raw = contextLog.completion(
                source = LogSource.PROMISE_RECONCILE,
                characterName = character.name,
                config = config,
                messages = listOf(ChatMessageDto(role = "user", content = prompt)),
                temperature = RECONCILE_TEMPERATURE,
                responseFormat = ResponseFormatDto(type = "json_object"),
            )
            val verified = PromiseReconciliation.parseAndVerify(raw, open, fullMaterial, zone)
            ledger.applyReconciliation(character.uuid, conversationUuid, verified, now)
        }

        try {
            runOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            delay(RECONCILE_RETRY_DELAY_MS)
            try {
                runOnce()
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                // 仍失败：静默放弃本批·仅计数日志（绝不打约定内容·§5·E20）。漏掉的状态变更靠后续对话/三期 UI 兜底。
                Log.w(TAG, "约定对账失败(静默放弃本批): ${e2.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "MemoryDigestCoordinator"

        /** 对账温度（低温保稳·图纸 §3.6）。 */
        const val RECONCILE_TEMPERATURE = 0.2

        /** 对账瞬态失败重试延迟（图纸 §3.6·E6）。 */
        const val RECONCILE_RETRY_DELAY_MS = 2_000L
    }
}
