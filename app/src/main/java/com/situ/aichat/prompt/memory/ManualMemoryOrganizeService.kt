package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「立即整理」手动记忆消化（记忆护栏第二层 MG-U3·契约 FABLE5_MEMORY_GUARD_UI_PROPOSAL §5）。
 *
 * 与自动路径（[com.situ.aichat.ui.chat.MemoryAnalysisTrigger]）的差别只有一个：**不做触发判定**——
 * 不看 5 分钟失败冷却、不看用户触发下限、不看双轨节奏，用户点了就跑。消化班车
 * [MemoryDigestCoordinator] 及其内的互斥锁 / 校验链 / 自愈 / 泄压阀零碰。
 *
 * 结果回写：成功 → 该角色全部「遇阻会话」（failureDate 非空）+ 本次窗口锚点会话记 success
 * （success 恒清失败旗标 → 资料页状态条自然消失）；失败 → 遇阻会话记 failure（刷新失败短冷却，
 * 与自动路径失败语义一致）。[organizing] 驱动 UI 忙态（照 OfflineSummaryRetryCoordinator 单源样式）。
 */
@Singleton
class ManualMemoryOrganizeService @Inject constructor(
    private val characterRepo: CharacterRepository,
    private val conversationRepo: ConversationRepository,
    private val memoryService: MemoryService,
    private val digestCoordinator: MemoryDigestCoordinator,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val userProfileDao: UserProfileDao,
) {
    private val _organizing = MutableStateFlow<Set<String>>(emptySet())

    /** 手动整理进行中的角色 uuid 集合（驱动状态条忙态·状态在本服务单源）。 */
    val organizing: StateFlow<Set<String>> = _organizing.asStateFlow()

    /**
     * 对单个角色手动跑一趟消化班车。@return true = 成功（含「无待总结内容」的空跑清旗标）。
     * busy 守卫：该角色已在整理中 → 直接返 false 不重入（UI 按钮置灰是第一道，此为第二道）。
     */
    suspend fun organizeNow(characterUuid: String): Boolean {
        // busy 守卫是 UI 级防重入；真正的并发保护在消化班车的每角色互斥锁（本处竞态无害）。
        if (characterUuid in _organizing.value) return false
        _organizing.update { it + characterUuid }
        try {
            val character = characterRepo.get(characterUuid) ?: return false
            val config = apiConfigRepo.resolveConfigValues(ApiFunction.MEMORY_SUMMARY)
                ?: apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: return false
            val settings = settingsRepo.getAppSettings()
            val userName = userProfileDao.get()?.nickname ?: ""

            val blocked = conversationRepo.memorySummaryBlockedByCharacter(characterUuid)
            // 窗口排除锚点 = 最新活跃会话（其短期窗口内消息已随身携带，不参与总结）；兜底取遇阻会话。
            val current = conversationRepo.latestActiveForCharacter(characterUuid)
                ?: blocked.firstOrNull() ?: return false

            val messages = memoryService.collectMessagesOutsideWindow(
                characterUuid = characterUuid,
                currentConversationUuid = current.uuid,
                shortTermLength = settings.shortTermMemoryLength,
            )
            if (messages.isEmpty()) {
                // 窗口外无待总结内容：无账可记 = 已理顺，清旗标即可（不烧 LLM 调用）。
                val now = System.currentTimeMillis()
                blocked.forEach { conversationRepo.recordMemorySummaryResult(it.uuid, success = true, now = now) }
                return true
            }

            return try {
                digestCoordinator.digestAndReconcile(
                    character = character,
                    conversationUuid = current.uuid,
                    messages = messages,
                    config = config,
                    settings = settings,
                    userName = userName,
                )
                val doneAt = System.currentTimeMillis()
                blocked.forEach { conversationRepo.recordMemorySummaryResult(it.uuid, success = true, now = doneAt) }
                if (blocked.none { it.uuid == current.uuid }) {
                    conversationRepo.recordMemorySummaryResult(current.uuid, success = true, now = doneAt)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 校验链拒收（MemorySummaryError）或网络/API 错：记失败刷新短冷却，语义与自动路径一致。
                val failAt = System.currentTimeMillis()
                blocked.forEach { conversationRepo.recordMemorySummaryResult(it.uuid, success = false, now = failAt) }
                false
            }
        } finally {
            _organizing.update { it - characterUuid }
        }
    }
}
