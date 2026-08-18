package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.worldbook.ScanMessage
import com.situ.aichat.worldbook.WorldBookVectorService
import com.situ.aichat.worldbook.WorldInfoActivationInput
import com.situ.aichat.worldbook.WorldInfoActivator
import com.situ.aichat.worldbook.toWorldInfoSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 世界书 → 故事章节生成的薄编排（ST5·契约 `FABLE5_STORY_REDESIGN_PROPOSAL.md` §4，销世界书契约 §8 挂起项）。
 * 照 [com.situ.aichat.worldbook.WorldBookPromptService] 的姿势，把故事语境适配成 [WorldInfoActivator] 激活输入，
 * **不复制引擎逻辑**；输出 = 四锚点归并为一段纯文本（before→after→suffix→atDepth 顺序，段内引擎已按 order 排好），
 * 交 [StoryGenerationPromptBuilder] 的 `worldInfoSection` 锚点注入（续章「前情回顾」前 / 首章「故事设定」后）。
 *
 * 与聊天侧的有意分叉（v1 拍板口径·契约 §4 / D3）：
 * - **书源** = 故事绑定角色中 characterId 非空者，逐角色取「启用的全局书 ∪ 绑定书」并按 uuid 去重；
 *   无关联 AI 角色 / 无书 / per-story 开关关 → null（零注入零开销）。
 * - **扫描缓冲**（代替聊天消息）：续章 = 上一章正文尾段 ~[TAIL_SEGMENT_CHARS] 字（[StoryTextSanitizer] 剥沉浸标签后）
 *   + 用户选择文本 + pendingChapterBeats；首章 = worldSetting + plotDirection。整体作 1 条 [ScanMessage]，
 *   scanDepth 固定 1（条目级 scanDepth=0「只认递归」语义不受影响）。
 * - **时效三件套不参与**（sticky/cooldown/delay 对「章」语义不清，v1 视为恒可触发）：传空时效状态 +
 *   超大消息计数令 delay 永不拦，且激活产出的时效状态**不落库**（升级路径见契约 §13 挂起）。
 * - **预算独立 [BUDGET_CHARS] 字**：章节 prompt 本已很长，不吃聊天侧全局预算设置（经 settings 覆盖参数传入）。
 * - **向量条目照旧参与**（[WorldBookVectorService] 相似度路 + 嵌入懒补）：续章查询 = 用户选择 + 上一章尾段；
 *   首章无上一章，用首章扫描缓冲（worldSetting + plotDirection）作查询，保持"全参与"。
 *
 * 失败兜底：激活链路任何异常（协程取消除外）→ Log.w 后返回 null——世界书故障绝不许弄死章节生成。
 * 诊断日志照世界书惯例：只打激活条目数 + 标题 + 字数，设定内容全文绝不进日志。
 */
@Singleton
class StoryWorldInfoService @Inject constructor(
    private val worldBookDao: WorldBookDao,
    private val worldBookVectorService: WorldBookVectorService,
    private val settingsRepository: SettingsRepository,
) {
    private val activator = WorldInfoActivator()

    /**
     * @param latestChapter 上一章实体（含正文，供尾段/用户选择扫描）；null = 首章（扫描 worldSetting + plotDirection）
     * @return 归并后的世界观设定段纯文本；开关关 / 无书 / 无命中 / 激活异常 → null（prompt 零变化）
     */
    suspend fun buildWorldInfoSection(
        story: StoryEntity,
        roles: List<StoryCharacterRoleEntity>,
        latestChapter: StoryChapterEntity?,
    ): String? {
        if (!story.worldInfoEnabled) return null
        val characterIds = roles.mapNotNull { role -> role.characterId?.takeIf { it.isNotBlank() } }.distinct()
        if (characterIds.isEmpty()) return null
        return try {
            activate(story, characterIds, latestChapter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "世界书激活失败，本章按无世界书继续生成", e)
            null
        }
    }

    /**
     * 该故事是否有任一世界书源（绑定角色 characterId 各自「启用全局书 ∪ 绑定书」有非空者）——
     * 设置页「世界观设定参与生成」开关行的显隐判定（契约 §4：绑定角色都没挂书时整行隐藏）。轻量：只查书不查条目。
     */
    suspend fun hasWorldBooks(roles: List<StoryCharacterRoleEntity>): Boolean {
        val characterIds = roles.mapNotNull { role -> role.characterId?.takeIf { it.isNotBlank() } }.distinct()
        return characterIds.any { worldBookDao.activeBooksForCharacter(it).isNotEmpty() }
    }

    private suspend fun activate(
        story: StoryEntity,
        characterIds: List<String>,
        latestChapter: StoryChapterEntity?,
    ): String? {
        val books = characterIds
            .flatMap { worldBookDao.activeBooksForCharacter(it) }
            .distinctBy { it.uuid }
        if (books.isEmpty()) return null
        val entries = worldBookDao.entriesForBooks(books.map { it.uuid })
        if (entries.isEmpty()) return null

        // 扫描缓冲：续章 = 剥标签后的上一章尾段 + 用户选择 + 方向提示；首章 = 世界观 + 剧情方向。
        val tailSegment = latestChapter?.let { StoryTextSanitizer.sanitize(it.content).takeLast(TAIL_SEGMENT_CHARS) }
        val scanText = if (latestChapter == null) {
            listOfNotNull(story.worldSetting, story.plotDirection)
        } else {
            listOfNotNull(tailSegment, latestChapter.userChoice, story.pendingChapterBeats)
        }.filter { it.isNotBlank() }.joinToString("\n")

        val appSettings = settingsRepository.getAppSettings()
        val settings = appSettings.toWorldInfoSettings().copy(
            scanDepth = 1, // 缓冲整体 1 条 ScanMessage，与全局聊天扫描深度解耦
            budgetChars = BUDGET_CHARS, // 契约 §4：故事侧独立预算，不吃聊天侧全局设置
        )

        // 向量条目（链接触发）：续章查询 = 用户选择 + 上一章尾段；首章用首章缓冲。阈值对齐聊天侧（0 = 关）。
        val vectorCandidates = entries.filter { it.vectorized && it.enabled }
        val vectorMatched = if (vectorCandidates.isEmpty()) {
            emptySet()
        } else {
            val queryText = if (latestChapter == null) {
                scanText
            } else {
                listOfNotNull(latestChapter.userChoice, tailSegment).filter { it.isNotBlank() }.joinToString("\n")
            }
            worldBookVectorService.matchedEntryUuids(
                candidates = vectorCandidates,
                queryText = queryText,
                thresholdPercent = appSettings.vectorSearchThreshold,
            )
        }

        val result = activator.activate(
            WorldInfoActivationInput(
                books = books,
                entries = entries,
                messages = listOf(ScanMessage(text = scanText)),
                conversationMessageCount = CHAPTER_MESSAGE_COUNT,
                conversationUuid = story.id,
                timedStates = emptyList(),
                vectorMatchedEntryUuids = vectorMatched,
                settings = settings,
            ),
        )
        // v1 拍板：时效三件套不参与——result.newTimedStates / expiredTimedStates 有意不持久化。

        val d = result.diagnostics
        if (d.activated.isNotEmpty() || d.droppedByBudget.isNotEmpty() || d.badRegexKeys.isNotEmpty()) {
            // 日志约定：只打标题/条数/字数，设定内容全文绝不进日志。
            Log.i(
                TAG,
                "故事「${story.title}」激活 ${d.activated.size} 条" +
                    "[${d.activated.joinToString { "${it.title}(${it.contentLength}字)" }}]" +
                    " 预算裁掉 ${d.droppedByBudget.size} 条" +
                    (if (d.droppedByBudget.isNotEmpty()) "[${d.droppedByBudget.joinToString { it.title }}]" else "") +
                    (if (d.badRegexKeys.isNotEmpty()) " 坏正则降级=${d.badRegexKeys}" else ""),
            )
        }
        if (result.isEmpty) return null

        // 四锚点归并为一段（契约 §4 已知口径①：故事是单 prompt 无对话历史，@depth 归并段尾）。
        val merged = buildList {
            add(result.before)
            add(result.after)
            add(result.suffix)
            result.atDepth.forEach { add(it.content) }
        }.filter { it.isNotBlank() }.joinToString("\n")
        return merged.ifBlank { null }
    }

    private companion object {
        const val TAG = "StoryWorldInfo"

        /** 续章扫描的上一章正文尾段长度（剥标签后取末尾，契约 §4「~1500 字」）。 */
        const val TAIL_SEGMENT_CHARS = 1500

        /** 故事侧世界书独立字符预算（契约 §4 / D3 拍板：2000 字，不吃聊天侧全局预算）。 */
        const val BUDGET_CHARS = 2000

        /** 时效不参与：作 delay 判定的消息计数取超大值 ⇒ 恒可触发（不用 Int.MAX_VALUE 防 sticky 锚点加法溢出）。 */
        const val CHAPTER_MESSAGE_COUNT = Int.MAX_VALUE / 2
    }
}
