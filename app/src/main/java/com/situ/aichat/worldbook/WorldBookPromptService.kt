package com.situ.aichat.worldbook

import android.util.Log
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AppSettings.worldInfo* → 引擎设置（WB7c·契约 §12.7 收口 WB4 预留的参数单缝）。
 * 每回合由 AssistantTurnEngine 现读现建——触发设置改完即用（热更新 §12.11-3）。
 * 未上 UI 的项（includeNames / minActivations / useGroupScoring 全局）沿用 [WorldInfoSettings] 的 ST 默认。
 */
fun AppSettings.toWorldInfoSettings(): WorldInfoSettings = WorldInfoSettings(
    scanDepth = worldInfoScanDepth,
    budgetChars = worldInfoBudgetChars,
    recursiveScan = worldInfoRecursiveScan,
    maxRecursionSteps = worldInfoMaxRecursionSteps,
    caseSensitive = worldInfoCaseSensitive,
    matchWholeWords = worldInfoMatchWholeWords,
    insertionStrategy = runCatching { WorldInfoInsertionStrategy.valueOf(worldInfoInsertionStrategy) }
        .getOrDefault(WorldInfoInsertionStrategy.CHARACTER_FIRST),
)

/**
 * 世界书 → 聊天提示词的回合级编排（WB4·契约 §4.3/§4.4）：
 * 取「启用的全局书 ∪ 角色绑定书」→ 跑 [WorldInfoActivator] → 时效状态就地增删 → 交回激活结果，
 * 由 AssistantTurnEngine 传入 PromptBuilder 四锚点注入。**每回合只激活一次**（降级/重试重装配复用同一结果，
 * 避免概率重掷与时效状态重复写）。
 *
 * 扫描输入过滤照向量记忆同口径：非 system、非结构化卡（礼物/红包/约定卡等无语义 JSON）、非空文本；
 * 计数锚（delay/sticky/cooldown 的「会话消息数」）= DAO 真实 COUNT（同口径谓词，批1修复）：旧实现用
 * 「本轮取到的消息条数」，受发送路径 500 条取数上限封顶 → 会话超 500 条后计数停在平台值，sticky 永不过期、
 * cooldown 永不生效、delay > 平台值的条目永不解锁。真实 COUNT 恒单调递增。
 * 设置经 [toWorldInfoSettings] 由调用方每回合从 AppSettings 现读传入（WB7c 收口）。
 */
@Singleton
class WorldBookPromptService @Inject constructor(
    private val worldBookDao: WorldBookDao,
    private val worldBookVectorService: WorldBookVectorService,
    private val messageDao: MessageDao,
) {
    private val activator = WorldInfoActivator()

    suspend fun activateForTurn(
        characterUuid: String,
        conversationUuid: String,
        sortedMessages: List<MessageEntity>,
        characterName: String,
        userName: String,
        /** 链接条目的相似度阈值 %（对齐 AppSettings.vectorSearchThreshold；0 = 向量触发关）。 */
        vectorThresholdPercent: Int = 0,
        settings: WorldInfoSettings = WorldInfoSettings(),
    ): WorldInfoActivationResult? {
        val books = worldBookDao.activeBooksForCharacter(characterUuid)
        if (books.isEmpty()) return null
        val entries = worldBookDao.entriesForBooks(books.map { it.uuid })
        if (entries.isEmpty()) return null
        val timedStates = worldBookDao.timedStatesForConversation(conversationUuid)

        val scannable = sortedMessages.filter {
            it.roleRaw != "system" &&
                !MessageKind.fromRaw(it.messageKindRaw).isStructuredCard &&
                it.content.isNotBlank()
        }
        val scanMessages = scannable.takeLast(SCAN_MESSAGE_WINDOW).map { m ->
            ScanMessage(
                text = m.content,
                senderName = if (m.roleRaw == "user") userName else characterName,
            )
        }

        // WB5 链接条目：语义相似触发（查询 = 最近一条用户消息，与向量记忆同口径）。
        val vectorCandidates = entries.filter { it.vectorized && it.enabled }
        val vectorMatched = if (vectorCandidates.isEmpty()) {
            emptySet()
        } else {
            worldBookVectorService.matchedEntryUuids(
                candidates = vectorCandidates,
                // 批3 3-6：跳过系统耳语（roleRaw='user' 的后台旁白，结构化卡已被 scannable 过滤）——
                // 否则爽约旁白触发的回合会拿旁白当语义检索词。
                queryText = scannable.lastOrNull {
                    it.roleRaw == "user" && MessageKind.fromRaw(it.messageKindRaw) != MessageKind.SYSTEM_HINT
                }?.content.orEmpty(),
                thresholdPercent = vectorThresholdPercent,
            )
        }

        val result = activator.activate(
            WorldInfoActivationInput(
                books = books,
                entries = entries,
                messages = scanMessages,
                conversationMessageCount = messageDao.countScannableForWorldBook(conversationUuid),
                conversationUuid = conversationUuid,
                timedStates = timedStates,
                vectorMatchedEntryUuids = vectorMatched,
                settings = settings,
            ),
        )

        result.expiredTimedStates.forEach {
            worldBookDao.clearTimedState(it.conversationUuid, it.entryUuid, it.effectType)
        }
        result.newTimedStates.forEach { worldBookDao.upsertTimedState(it) }

        val d = result.diagnostics
        if (d.activated.isNotEmpty() || d.droppedByBudget.isNotEmpty() || d.badRegexKeys.isNotEmpty()) {
            // 日志约定：只打标题/条数/字数，设定内容全文绝不进日志。
            Log.i(
                TAG,
                "激活 ${d.activated.size} 条[${d.activated.joinToString { "${it.title}(${it.contentLength}字)" }}]" +
                    " 预算裁掉 ${d.droppedByBudget.size} 条" +
                    (if (d.droppedByBudget.isNotEmpty()) "[${d.droppedByBudget.joinToString { it.title }}]" else "") +
                    " 扫描轮=${d.sweepCount}" +
                    (if (d.badRegexKeys.isNotEmpty()) " 坏正则降级=${d.badRegexKeys}" else ""),
            )
        }
        return result.takeIf { !it.isEmpty }
    }

    private companion object {
        const val TAG = "WorldBookPrompt"

        /** 交给引擎的扫描消息窗上限（覆盖最大合理扫描深度与 minActivations 扩窗需求）。 */
        const val SCAN_MESSAGE_WINDOW = 50
    }
}
