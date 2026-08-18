package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.repository.StoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 剧情弧线大纲编排（自 [StoryGenerationService] **只搬不改**抽出·卷二 C4 文件瘦身，行为字节级不变）。
 *
 * 抽出的动机：卷二给大纲面加了「自报章数换挡 + 弧线简史 + 终章弧」三件后，Service 越过 600 行硬顶
 * （CLAUDE.md §2），而大纲这一族（判定 → 调 LLM → 定向写）本就是自成一体的一件事。
 * [StoryGenerationService.ensureOutline] 保留同签名薄门面，调用方一字不改。
 *
 * 创作 API 配置经 [creationConfig] **惰性**取（suspend 供给函数）：不需要生成大纲的绝大多数章一次也不解析，
 * 与外搬前的行为一致。
 */
@Singleton
class StoryOutlineOrchestrator @Inject constructor(
    private val storyRepository: StoryRepository,
    private val contextLog: ContextLogService,
    private val storyCharacterDataCollector: StoryCharacterDataCollector,
) {

    /**
     * 确保故事有当前剧情弧线的大纲。首次生成；此后本弧写满**自报章数**即续接新弧线（触发决策见
     * [StoryGenerationPolicy.decideOutlineAction] + [StoryArcPlanning]）。大纲生成失败不阻塞章节生成。
     *
     * 换弧时（卷二 B2）先给刚结束的那条弧算一行简史，与新大纲**同一条 UPDATE** 落库
     * （[StoryRepository.updateOutlineAndArcHistory]）——避免「史已追加、新弧却生成失败」的半截态；
     * 新简史同时喂进本次的弧线 prompt（[StoryArcPlanning.appendArcHistoryLine] 的结果先进内存副本再进 prompt）。
     *
     * 有收尾计划（[StoryEntity.finaleEndingType] 非空）时两条换弧支各自换成终章弧（卷二 J1）。
     *
     * @return 更新后的故事（storyOutline/currentArcStartChapter/arcHistory 可能变化）；无变化时原样返回，
     *   供调用方继续构 prompt。
     */
    suspend fun ensureOutline(
        story: StoryEntity,
        chapterNumber: Int,
        nowMillis: Long,
        creationConfig: suspend () -> ApiConfigValues?,
    ): StoryEntity {
        val finalePlanned = story.finaleEndingType != null
        return when (
            StoryGenerationPolicy.decideOutlineAction(
                storyOutline = story.storyOutline,
                currentArcStartChapter = story.currentArcStartChapter,
                chapterNumber = chapterNumber,
                plannedLength = StoryArcPlanning.parseArcPlannedLength(story.storyOutline),
                finalePlanned = finalePlanned,
            )
        ) {
            StoryGenerationPolicy.OutlineAction.GenerateInitialArc -> {
                val text = generateOutline(story, nowMillis, creationConfig) ?: return story
                // J6：弧起点取**当前章**而非写死 1——首章时二者等价，中途大纲被清后重建不再错记弧起点。
                storyRepository.updateOutline(story.id, text, chapterNumber)
                Log.d(TAG, "大纲生成完成（初始弧线）：${text.length} 字")
                story.copy(storyOutline = text, currentArcStartChapter = chapterNumber)
            }

            StoryGenerationPolicy.OutlineAction.GenerateNewArc ->
                generateArcWithHistory(story, chapterNumber, nowMillis, isFinale = false, creationConfig)

            StoryGenerationPolicy.OutlineAction.GenerateFinaleArc ->
                generateArcWithHistory(story, chapterNumber, nowMillis, isFinale = true, creationConfig)

            StoryGenerationPolicy.OutlineAction.None -> story
        }
    }

    /**
     * **手动重排**（图纸 2026-08-05 §3.4·书页大纲卡「按最新剧情重排」）：无视弧进度立即换一条弧
     * ——简史照记、弧起点重置为 [chapterNumber]，走的就是 [generateArcWithHistory] 这条既有换弧路，
     * 不另开写库口（**绝不走 `updateStoryOutlineUserEdit`**：那口有意不动弧起点，是「手改大纲」的语义）。
     *
     * 有收尾计划（[StoryEntity.finaleEndingType] 非空）时自动分派终章弧，收尾方向不丢。
     *
     * @param chapterNumber 新弧起始章号 = 最新已写章 + 1（与 [ensureOutline] 的换弧语义一致）
     * @return 生成成功 = 已落库的新故事；**生成失败 / 被截断 / 与旧大纲逐字相同 → 原样返回入参**，
     *   调用方据「storyOutline 是否变化」判成败并提示（重排后一模一样对用户就是没变，提示重试无害）。
     */
    internal suspend fun regenerateArc(
        story: StoryEntity,
        chapterNumber: Int,
        nowMillis: Long,
        creationConfig: suspend () -> ApiConfigValues?,
    ): StoryEntity = generateArcWithHistory(
        story = story,
        chapterNumber = chapterNumber,
        nowMillis = nowMillis,
        isFinale = story.finaleEndingType != null,
        creationConfig = creationConfig,
    )

    /**
     * 换弧（普通续接 / 转入终章弧）共用编排：先给刚结束的那条弧算一行简史并喂进本次 prompt（B2·防新弧重复旧梗），
     * 大纲生成成功后与简史**同一条 UPDATE** 落库（[StoryRepository.updateOutlineAndArcHistory]）。
     *
     * 生成失败 / 与上一版逐字相同 → 原样返回，简史一并不落（下次换弧再算，幂等）。
     */
    private suspend fun generateArcWithHistory(
        story: StoryEntity,
        chapterNumber: Int,
        nowMillis: Long,
        isFinale: Boolean,
        creationConfig: suspend () -> ApiConfigValues?,
    ): StoryEntity {
        val previousOutline = story.storyOutline
        val newHistory = StoryArcPlanning.appendArcHistoryLine(
            existingHistory = story.arcHistory,
            previousOutline = previousOutline,
            previousArcSummary = story.currentArc,
            arcStart = story.currentArcStartChapter,
            arcEnd = chapterNumber - 1,
        )
        val text = generateOutline(story.copy(arcHistory = newHistory), nowMillis, creationConfig, isFinale)
            ?: return story
        if (text == previousOutline) return story
        storyRepository.updateOutlineAndArcHistory(story.id, text, chapterNumber, newHistory)
        Log.d(
            TAG,
            "${if (isFinale) "终章弧" else "弧线续接"}：第 $chapterNumber 章生成新弧线，" +
                "${text.length} 字，简史 ${newHistory.lines().size} 行",
        )
        return story.copy(storyOutline = text, currentArcStartChapter = chapterNumber, arcHistory = newHistory)
    }

    /**
     * 调用 LLM 生成弧线大纲文本。失败/空 → null（吞异常，不抛）；
     * 不在此持久化——由 [ensureOutline] 把 storyOutline + currentArcStartChapter 一次原子写。
     *
     * @param isFinale true = 生成终章弧大纲（收尾使命块·卷二 J1）
     */
    private suspend fun generateOutline(
        story: StoryEntity,
        nowMillis: Long,
        creationConfig: suspend () -> ApiConfigValues?,
        isFinale: Boolean = false,
    ): String? {
        val config = creationConfig() ?: run {
            Log.w(TAG, "大纲生成跳过：未配置故事创作 API")
            return null
        }
        val roles = storyRepository.getRoles(story.id)
        val characterData = storyCharacterDataCollector.collectCharacterData(roles, nowMillis)
        val prompt = StoryGenerationPromptBuilder.buildOutlinePrompt(story, roles, characterData, isFinale)
        val messages = listOf(
            ChatMessageDto(role = "system", content = prompt),
            ChatMessageDto(role = "user", content = "请设计下一个剧情弧线。"),
        )
        return try {
            var finishReason: String? = null
            // 批 D 上下文日志：主生成（大纲）经 contextLog 落库（source=STORY_GENERATION·用户级 characterName=""·重任务截断）。
            val response = contextLog.completion(
                source = LogSource.STORY_GENERATION,
                characterName = "",
                config = config,
                messages = messages,
                temperature = 0.5,
                // D4：思考模型 ×3（复用压缩同款倍率）。提示词无输出总长约束 ⇒ 模型自停点不因上限放宽而后移，
                // 额度只防截断、不是「写更长」的邀请函（图纸 §0.3-9 成本论证）。
                maxTokens = StoryGenerationPromptBuilder
                    .preferredCompressionMaxTokens(OUTLINE_BASE_MAX_TOKENS, config.isThinkingModel),
                onFinishReason = { finishReason = it },
            )
            val cleaned = StoryTextCleaning.cleanContentThinkingTags(response).trim()
            if (cleaned.isEmpty()) {
                Log.w(TAG, "大纲生成返回为空，跳过")
                return null
            }
            if (LlmClient.isLengthTruncated(finishReason)) {
                // 升额重试后仍撞顶（LlmClient 已自动 ×3 重试一次）——半截大纲绝不落库，走既有「失败不阻塞」路下次再试。
                Log.w(TAG, "大纲被截断不采纳（finish=$finishReason，maxTokens 升额后仍撞顶）")
                return null
            }
            cleaned
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 取消如实传播（照 [StoryGenerationService.requestCreation] 范式）：吞掉它会让上层协程
            // 在已取消的状态下继续跑，还把取消误记成一次「大纲生成失败」。
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "大纲生成失败（不影响章节生成）", e)
            null
        }
    }

    private companion object {
        /** 与 [StoryGenerationService] 同一个 Logcat tag（搬出来不改日志归属，真机排查按同一关键字过滤）。 */
        const val TAG = "StoryGeneration"

        /**
         * 弧线大纲生成的基础 token 上限（图纸一 D4）。原值 1_500 已实证不足——用户 2026-07-31 的真实日志里大纲
         * **存库时即被截断**（「转折点2」末句停在「…而是」），注入侧原样发送、断点来自生成侧撞顶。
         */
        const val OUTLINE_BASE_MAX_TOKENS = 2_000
    }
}
