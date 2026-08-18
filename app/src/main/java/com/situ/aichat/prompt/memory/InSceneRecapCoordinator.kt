package com.situ.aichat.prompt.memory

import android.util.Log
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 场内滚动压缩·前情提要协调（记忆改造二期·部件⑤·图纸 §3.2）：进行中的长见面 / 长通话，窗口只保留本场最近
 * `shortTermMemoryLength×4` 条（基准 80），更早部分被静默丢弃、只剩一句「更早部分已省略」note。本协调把被丢弃
 * 的部分**滚动压缩**成一段前情提要挂在 conversations 三列（图纸 §3.2-A），由 PromptBuilder 注入在截断提示之后。
 * 见面与语音通话共用同一套机制。
 *
 * **惰性失效**（图纸 §3.2-A）：写回带场景 key（见面=sessionId·通话=`call:{首条ts}`），注入 / 续写前校验 key 匹配，
 * 场结束不清列 → 下一场生成时整组覆写、旧 key 永不再匹配 → 自然失效（省清理钩子、杜绝漏清）。
 *
 * 护栏（图纸 §3.2-B·guard 顺序 单飞 → 冷却 → 守卫 → 判定）：per-conversation 单飞占坑 + 尝试后 120s 冷却（成败均记）。
 * 生成频率 ≈ 掉块开始后每 [COVER_AHEAD] 条一次（预覆盖缓冲）。日志纪律：绝不打提要 / 素材内容，只打计数 / uuid / 场景词（§5）。
 */
@Singleton
class InSceneRecapCoordinator @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val messageRepo: MessageRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val contextLog: ContextLogService,
    private val userProfileDao: UserProfileDao,
) {
    /** per-conversation 单飞占坑（图纸 §3.2-B）。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** per-conversation 上次尝试时刻（冷却·成败均记）。 */
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()

    /**
     * 见面侧回合后钩子（AssistantTurnEngine 触发段·图纸 §3.2-B）：守卫本会话在见面中且 sessionId 非空，
     * 素材 = 本场全部消息（升序），sceneKey = sessionId，场景词 = 线下见面。
     */
    suspend fun checkMeetingRecap(conversationUuid: String) {
        if (!inFlight.add(conversationUuid)) return // 单飞
        try {
            if (inCooldown(conversationUuid)) return // 冷却
            val convo = conversationRepo.get(conversationUuid) ?: return
            val sessionId = convo.currentOfflineSessionId
            if (!convo.isInOfflineMode || sessionId.isNullOrEmpty()) return // 守卫
            val material = messageRepo.offlineSessionMessages(conversationUuid, sessionId)
            generate(convo, material, sceneKey = sessionId, sceneWord = SCENE_WORD_MEETING)
        } finally {
            inFlight.remove(conversationUuid)
        }
    }

    /**
     * 通话侧回合后钩子（VoiceCallPostReplyRounds 触发段·图纸 §3.2-B）：素材 = 最近 [CALL_FETCH_LIMIT] 条里的
     * 尾部连续通话段（[trailingCallBlock]·空则守卫返回），sceneKey = [currentCallBlockKey]，场景词 = 语音通话。
     */
    suspend fun checkCallRecap(conversationUuid: String) {
        if (!inFlight.add(conversationUuid)) return // 单飞
        try {
            if (inCooldown(conversationUuid)) return // 冷却
            val convo = conversationRepo.get(conversationUuid) ?: return
            val recent = messageRepo.recentChronological(conversationUuid, CALL_FETCH_LIMIT)
            val block = trailingCallBlock(recent)
            if (block.isEmpty()) return // 守卫
            val callKey = currentCallBlockKey(recent) ?: return
            generate(convo, block, sceneKey = callKey, sceneWord = SCENE_WORD_CALL)
        } finally {
            inFlight.remove(conversationUuid)
        }
    }

    private fun inCooldown(uuid: String): Boolean =
        System.currentTimeMillis() - (lastAttemptAt[uuid] ?: 0L) < COOLDOWN_MS

    /**
     * 判定 → 取素材切片 → LLM 压缩 → 列级写回（图纸 §3.2-B）。素材空 / 判定不生成 / chunk 空白 → 提前返回不调 LLM；
     * 空返回 / 超长垃圾 → 放弃（旧值与水位不动，冷却后下轮重试）。
     */
    private suspend fun generate(
        convo: ConversationEntity,
        material: List<MessageEntity>,
        sceneKey: String,
        sceneWord: String,
    ) {
        val count = material.size
        if (count == 0) return
        val settings = settingsRepo.getAppSettings()
        val capBase = maxOf(settings.shortTermMemoryLength * 4, 1)
        // 惰性失效：库中 key == 本场 key 才承认旧提要 / 旧水位；否则视同无提要（覆盖数=0·整组覆写）。
        val keyMatches = convo.inSceneRecapSessionKey.isNotEmpty() && convo.inSceneRecapSessionKey == sceneKey
        val coveredCount = if (keyMatches) material.count { it.timestamp <= convo.inSceneRecapUntilMillis } else 0
        val cut = recapDecision(count, capBase, coveredCount) ?: return // 判定

        val cutTs = material[cut.cutIndex - 1].timestamp // cutTs = 素材第 cutIndex 条（1-indexed）的 timestamp
        val waterline = if (keyMatches) convo.inSceneRecapUntilMillis else Long.MIN_VALUE
        val chunk = material.filter { it.timestamp > waterline && it.timestamp <= cutTs }
        // 第三人称指名（图纸一·B2）：formatMessages 之前取名（角色名/用户名·兜底「角色」/「用户」），
        // 供喂对话记录 + buildRecapPrompt 命名要求；characterName 同时供下方 contextLog（原重复取值已上移）。
        val characterName = (characterRepo.get(convo.characterUuid)?.name ?: "").ifBlank { "角色" }
        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" }
        val chunkText = MemoryService.formatMessages(chunk, userLabel = userName, charLabel = characterName)
        if (chunkText.isBlank()) return // E14：素材脱敏后为空 → 不调 LLM、不写库、不推水位

        val config = apiConfigRepo.resolveConfigValues(ApiFunction.MEMORY_SUMMARY)
            ?: apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: return
        val oldRecap = if (keyMatches) convo.inSceneRecapText else ""
        val prompt = buildRecapPrompt(sceneWord, oldRecap, chunkText, characterName, userName)

        lastAttemptAt[convo.uuid] = System.currentTimeMillis() // 尝试即记冷却（成败均记）
        val raw = try {
            contextLog.completion(
                source = LogSource.IN_SCENE_RECAP,
                characterName = characterName,
                config = config,
                messages = listOf(ChatMessageDto(role = "user", content = prompt)),
                temperature = TEMPERATURE,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "场内前情提要生成失败($sceneWord·冷却后重试): ${e.javaClass.simpleName}")
            return
        }

        val text = MemoryService.strippingThinkingTags(raw).trim()
        if (text.isEmpty()) return // E6：空返回 → 放弃（旧值与水位不动）
        if (MemoryService.cjkLength(text) > MAX_RECAP_CJK) return // E6：超长垃圾防线 → 放弃
        conversationRepo.updateInSceneRecap(convo.uuid, text, sceneKey, cutTs)
        Log.d(TAG, "场内前情提要已更新: uuid=${convo.uuid} 场景=$sceneWord 覆盖=${chunk.size}条")
    }

    companion object {
        private const val TAG = "InSceneRecapCoordinator"

        /** 前情提要注入标题（单源·PromptBuilder 2.15 引用；DirtyMessageDetector 照惯例硬编码字面 + KDoc 互指·图纸 §3.2-D）。 */
        const val RECAP_HEADER = "【前情提要】"

        /** 通话块 key 前缀（图纸 §3.2-A·锁定）。 */
        const val CALL_KEY_PREFIX = "call:"

        /** 预覆盖缓冲：生成频率 ≈ 每 40 条一次（图纸 §3.2-B·锁定）。 */
        const val COVER_AHEAD = 40

        /** 尝试后冷却（图纸 §3.2-B·锁定）。 */
        const val COOLDOWN_MS = 120_000L

        /** 垃圾防线：CJK 字数上限（图纸 §3.2-B·锁定）。 */
        const val MAX_RECAP_CJK = 600

        /** 压缩温度（图纸 §3.2-B·锁定）。 */
        const val TEMPERATURE = 0.3

        /** 通话素材取回上限（图纸 §3.2-B·E8）。 */
        const val CALL_FETCH_LIMIT = 500

        private const val SCENE_WORD_MEETING = "线下见面"
        private const val SCENE_WORD_CALL = "语音通话"

        /** 切点（1-indexed 覆盖条数）。 */
        data class RecapCut(val cutIndex: Int)

        /**
         * 共同判定（图纸 §3.2-B·锁定·纯函数）：[count]=素材条数、[capBase]=基准窗口（`shortTermMemoryLength×4`）、
         * [coveredCount]=已覆盖条数。dropped≤0（未超基准）或 dropped≤covered（覆盖足够）→ null（不生成）；
         * 否则切点 `cutIndex = min(count, dropped + COVER_AHEAD)`。
         */
        fun recapDecision(count: Int, capBase: Int, coveredCount: Int): RecapCut? {
            val dropped = count - capBase
            if (dropped <= 0) return null
            if (dropped <= coveredCount) return null
            return RecapCut(cutIndex = minOf(count, dropped + COVER_AHEAD))
        }

        /** 尾部连续 `isPartOfVoiceCall` 段（图纸 §3.2-B·纯函数·输入升序则输出升序·空历史/无通话 → 空）。 */
        fun trailingCallBlock(messages: List<MessageEntity>): List<MessageEntity> =
            messages.takeLastWhile { it.isPartOfVoiceCall }

        /** 本场通话块 key = `call:{尾块首条 timestamp}`（图纸 §3.2-A/B·纯函数·无尾部通话段 → null）。 */
        fun currentCallBlockKey(messages: List<MessageEntity>): String? {
            val block = trailingCallBlock(messages)
            return if (block.isEmpty()) null else "$CALL_KEY_PREFIX${block.first().timestamp}"
        }

        /**
         * 生成提示词（图纸 §3.2-C·纯函数）：oldRecap 空白时整段省略「已有前情提要」块与首行的
         * 「，以及此前已写好的前情提要」。第三人称指名（图纸一·B2·§9）：命名要求用真实 [charName]/[userName]
         * （空由调用方兜底「角色」/「用户」）——取代原 §3.2-C「双方」逐字锁定（J-4 有意规格演进·同步更新单测）。
         */
        internal fun buildRecapPrompt(sceneWord: String, oldRecap: String, chunkText: String, charName: String, userName: String): String {
            val hasOld = oldRecap.isNotBlank()
            val sb = StringBuilder()
            sb.append("你是剧情记录员。下面是一场正在进行的").append(sceneWord).append("里较早部分的对话记录")
            if (hasOld) sb.append("，以及此前已写好的前情提要")
            sb.append("。请把这些内容浓缩成一段新的前情提要：第三人称、按时间顺序，提到两人时用「").append(charName).append("」「").append(userName).append("」的名字（不要写「用户」「角色」），只保留对后续有用的信息（发生了什么、聊到的要点、情绪的变化、双方说定的事），不超过300字。只输出前情提要正文，不要任何标题、解释或前后缀。")
            sb.append("\n\n")
            if (hasOld) sb.append("已有前情提要：\n").append(oldRecap).append("\n\n")
            sb.append("较早部分的记录：\n").append(chunkText)
            return sb.toString()
        }
    }
}
