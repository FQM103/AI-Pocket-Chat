package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.repository.StoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每章落库后的「压缩域」协作者（自 [StoryGenerationService] **只搬不改**抽出·2026-08-03 生成时序卷一 chunk 1，
 * 行为字节级不变）。
 *
 * 抽出的动机：Service 已到 592 行逼近 600 硬顶（CLAUDE.md §2），而压缩这一族（摘要压缩本体 + 熔断表 +
 * 触发判定 + 后台 job 管理）本就是自成一体的一件事。摘要压缩的 prompt / 采纳闸 / 熔断语义一字不改。
 *
 * **时序职责（卷一 chunk 2）**：两次压缩 LLM 调用不再串在「生成完成」关键路径上——章节 materialize 落库后
 * [scheduleAfterChapter] 登记一个 per-书后台 job（判定在 job 内，不触发即空过），generateChapter 立刻返回 ⇒
 * 用户看到「第 N 章写好了」不再白等压缩几十秒，压缩顶爆调用方 withTimeout 也不会再把已落库的章标成生成失败。
 * 顺序则由 **join 不变式**保住：同一本书的下一次生成/重写在入口先 [joinCompression] 再重读故事快照
 * （见 [StoryGenerationService.generateNextChapter] / [StoryGenerationService.prepareRewrite]），
 * 因此「第 N 章压缩写回 → 第 N+1 章读快照/落库」的先后顺序与串行时代完全一致。
 *
 * 创作 API 配置经 [creationConfig] **惰性**取（suspend 供给函数·形状照 [StoryOutlineOrchestrator.ensureOutline]）：
 * 与外搬前「压缩体内部自行解析创作配置」的时机一致。
 */
@Singleton
class StoryCompressionCoordinator @Inject constructor(
    private val llmClient: LlmClient,
    private val storyRepository: StoryRepository,
    private val storyBibleCompressor: StoryBibleCompressor,
) {

    /**
     * 应用级作用域（形状照 [StoryGenerationTaskManager] 的 scope）：压缩 survive 生成协程取消/屏幕切换——
     * 用户取消生成时那一章其实已落库，白烧的创作至少把压缩做完；SupervisorJob 让多书并发互不牵连。
     * 进程死亡打断 job = 水位线两列没写 ⇒ 下一章重新判定即自愈（与既有「失败保旧值」同语义）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** storyId → 进行中的压缩 job（同一本书同时至多一个，由 join 不变式保证）。 */
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * 等待该书进行中的压缩完成；无 job 立即返回（**join 不变式**的等待端）。
     *
     * 有意不设超时：压缩自身有 LlmClient 层超时兜底，这里再叠一层只会在超时那一刻放行下一章，
     * 恰好制造这套机制要防的那种覆盖竞态（[StoryDao.updateNarrativeState] 整列写 storySummary/storyBible，
     * 而水位线两列已被压缩推进 → 摘要停在旧值、系统却以为已压缩）。
     * 等待期间调用方被取消 → CancellationException 照常传播走生成取消路，压缩 job 不受影响继续跑完。
     */
    internal suspend fun joinCompression(storyId: String) {
        jobs[storyId]?.join()
    }

    /**
     * 章节落库后调度两次压缩（摘要链 + 圣经）：**非挂起、立即返回**，判定全在 job 体内——
     * 恒登记、不触发即空过（免掉「登记与否」的前置分支与漏登记风险；空 job 成本 = 一次投影查询 + 一次单行读）。
     *
     * @param story materialize 时调用点手头的故事快照；这里只取 `id` 与 `lastCompressedAtChapter`
     *   （水位线在 join 不变式下自上次压缩起未变——本 job 是本章唯一的压缩者）。
     * @param creationConfig 创作配置供给函数：圣经压缩的配置在 job 内解析（较原先由 generateChapter 传入
     *   已解析值多一次 DB+密钥库读，语义相同）；摘要压缩本体内部的解析时机保持不变。
     */
    internal fun scheduleAfterChapter(
        story: StoryEntity,
        chapterNumber: Int,
        creationConfig: suspend () -> ApiConfigValues?,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (StoryGenerationParsing.shouldCompressSummary(
                    story.lastCompressedAtChapter,
                    chapterNumber,
                    storyRepository.getChapterSummaries(story.id),
                )
            ) {
                compressSummaryChainIfNeeded(story.id, chapterNumber, creationConfig)
            }
            // 圣经结构化压缩（长篇稳定性 L1·触发判定/切分/熔断在 compressor 内；未触发零副作用）。
            // 用**创作模型**而非结构化槽：与摘要压缩同理——质量敏感的总结活配强模型。
            storyBibleCompressor.compressIfNeeded(story.id, chapterNumber, creationConfig())
        }
        // LAZY 建 job → 先挂完成回调、再登记、最后 start（照 [StoryGenerationTaskManager.startGeneration] 形状）：
        // remove 用双参版本，只摘掉自己那一个，绝不误删后来者。
        job.invokeOnCompletion { jobs.remove(story.id, job) }
        jobs[story.id] = job
        job.start()
    }

    /**
     * R5#3 摘要压缩重试守卫：storyId → 本进程内**连续**失败次数（LLM 调用抛异常或返回空响应都计一次——
     * 同样白烧一次 LLM 调用而无进展；协程取消不计，避免用户取消生成误锁）。连续失败 ≥ [MAX_COMPRESSION_FAILURES]
     * 次后本进程内不再尝试（Log.i 记账），成功一次即清零；不落库，进程重启自然重置。
     *
     * 选 ConcurrentHashMap 的理由：压缩自卷一 chunk 2 起跑在本协作者的 [scope]（per-书后台 job），
     * 登记点仍有手动生成（[StoryGenerationTaskManager]）与自动连载（[StoryAutoSerializeService]）两路，
     * 跨故事并发是常态；本协作者内没有现成互斥，merge/remove 原子操作已足够，加全局 Mutex 反而会串行化无关故事。
     */
    private val compressionFailureCounts = ConcurrentHashMap<String, Int>()

    /**
     * 压缩摘要链（1:1 iOS `compressSummaryChainIfNeeded` :87-136）：把「旧压缩结果 + 自上次压缩以来各章摘要」
     * 合并为新的全局摘要。失败/空不阻断流程（保留旧值，下次再试）；触发判定由 [StoryGenerationParsing.shouldCompressSummary]
     * 在 [scheduleAfterChapter] 的 job 体内先行决定。R5#3：同一故事本进程内连续失败 [MAX_COMPRESSION_FAILURES] 次后跳过
     * （见 [compressionFailureCounts]，防每次落库都白烧 LLM 重试）。
     *
     * 重新拉取故事取最新状态（= iOS `context.fetch`）；既有压缩仅在已压缩过（lastCompressed>0）时取 storySummary，
     * 否则视为首次压缩（空）。写回经 [StoryRepository.updateCompressedSummary] 定向写 storySummary + lastCompressedAtChapter（D1 安全）。
     *
     * 模型路由（有意偏离 iOS 结构化槽）：用**创作模型**（[StoryGenerationService.creationConfig]）跑压缩——质量敏感的总结活配强模型，
     * 与 [StoryBibleCompressor.compressIfNeeded] 圣经压缩同口径；纯格式活（[StoryPayloadResolver] 的 L2/L3/修复）仍走
     * [StoryGenerationService.structuringConfig]。
     */
    internal suspend fun compressSummaryChainIfNeeded(
        storyId: String,
        currentChapter: Int,
        creationConfig: suspend () -> ApiConfigValues?,
    ) {
        val failedCount = compressionFailureCounts[storyId] ?: 0
        if (failedCount >= MAX_COMPRESSION_FAILURES) {
            Log.i(TAG, "摘要压缩跳过：连续失败 $failedCount 次，本进程内不再尝试 story=${storyId.take(8)}")
            return
        }
        val story = storyRepository.getStory(storyId) ?: return
        // 压缩类任务（摘要/圣经）有意路由到**创作模型**：这是质量敏感的总结活（保住人物/伏笔/感情线，
        // 输出回喂后续章节 prompt），配强模型；纯格式活（L2/L3/修复）仍走 structuringConfig。
        val compressionConfig = creationConfig() ?: return

        val lastCompressed = story.lastCompressedAtChapter ?: 0
        val existingCompressed = if (lastCompressed > 0) story.storySummary ?: "" else ""

        val newSummaries = StoryGenerationParsing.buildNewSummariesBlock(
            chapterSummaries = storyRepository.getChapterSummaries(storyId),
            lastCompressedChapter = lastCompressed,
            currentChapter = currentChapter,
        )
        if (newSummaries.isEmpty()) return

        val prompt = StoryGenerationPromptBuilder.buildCompressionPrompt(
            existingCompressed = existingCompressed,
            newSummaries = newSummaries,
            lastCompressedChapter = lastCompressed,
            currentChapter = currentChapter,
            genre = story.genre,
        )
        val messages = listOf(
            ChatMessageDto(role = "system", content = prompt),
            ChatMessageDto(role = "user", content = "请执行摘要压缩。"),
        )
        try {
            var finishReason: String? = null
            val compressed = llmClient.completion(
                messages = messages, config = compressionConfig, temperature = 0.1,
                // 思考模型专属额度：×3 给足推理余量（压缩现走创作槽），第一次就装得下 → 少截断少重试。
                maxTokens = StoryGenerationPromptBuilder.preferredCompressionMaxTokens(2_400, compressionConfig.isThinkingModel),
                onFinishReason = { finishReason = it },
            )
            // 非流式 completion 不剥内联 <think>——storySummary 落库后回注 prompt 并回喂下轮压缩，必须剥净（含 trim）。
            val trimmed = StoryTextCleaning.cleanContentThinkingTags(compressed)
            // 截断防线（记忆护栏 G2 同款单源判据 [LlmClient.isLengthTruncated]）：finish_reason=length（升额后仍被
            // 掐断，压缩现走创作槽·思考模型尤易触顶）→ 半截摘要绝不落库回喂（否则丢的正是要保住的伏笔/感情线，
            // 且回喂下轮 existingCompressed 自我强化），计一次失败、保留旧值、下轮再试（与空响应同路）。
            if (trimmed.isNotEmpty() && !LlmClient.isLengthTruncated(finishReason)) {
                storyRepository.updateCompressedSummary(storyId, trimmed, currentChapter)
                compressionFailureCounts.remove(storyId)
                Log.d(TAG, "摘要压缩完成：第1-${currentChapter}章 → ${trimmed.length}字")
            } else {
                compressionFailureCounts.merge(storyId, 1, Int::plus)
                Log.w(TAG, "摘要压缩未采纳（空或截断 finish=$finishReason，计一次失败）story=${storyId.take(8)}")
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 协程取消不计失败（取消 ≠ 压缩本身坏），且**如实重抛**（卷一 chunk 3·照
            // [StoryGenerationService.requestCreation] 范式）：吞掉取消会让 job 以「正常完成」收场、
            // 日志把取消记成一次失败，join 方也读不到真实结局。
            throw e
        } catch (e: Exception) {
            compressionFailureCounts.merge(storyId, 1, Int::plus)
            Log.w(TAG, "摘要压缩失败（不影响正常使用）", e)
        }
    }

    private companion object {
        /** 与 [StoryGenerationService] 同一个 Logcat tag（搬出来不改日志归属，真机排查按同一关键字过滤）。 */
        const val TAG = "StoryGeneration"

        /** R5#3：同一故事本进程内摘要压缩连续失败达此次数后不再尝试（成功即复位，见 [compressionFailureCounts]）。 */
        const val MAX_COMPRESSION_FAILURES = 2
    }
}
