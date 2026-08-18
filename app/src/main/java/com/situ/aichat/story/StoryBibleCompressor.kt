package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.repository.StoryRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 故事圣经结构化压缩编排（长篇稳定性 L1·契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §3）。
 *
 * 每章落库后由 [StoryCompressionCoordinator.scheduleAfterChapter] 登记的**后台 job** 在摘要压缩旁调用
 * （2026-08-03 卷一起挪出生成关键路径，本文件的判定/闸/熔断一字未改）：触发判定
 * （[StoryBibleCompression.shouldCompress]）→ 切分（水位线 / 近 [StoryBibleCompression.KEEP_RAW_RECENT_CHAPTERS] 章
 * 保留原始行）→ LLM 整理为「主要/次要/已淡出角色 + 伏笔账本」→ **四条件采纳闸**（空 / 截断 / 超长 /
 * 点名对账·见 [compressIfNeeded] 内注释）→ 定向写回 storyBible + lastBibleCompressedAtChapter（D1 安全）。
 *
 * 容错完全对齐摘要压缩链（R5#3 模式）：失败/空返回/闸拒收均保留旧圣经原样不阻断生成；同故事本进程内连续失败
 * [MAX_BIBLE_COMPRESSION_FAILURES] 次后熔断（成功即复位；协程取消不计失败）。压缩用模型配置由调用方传入
 * ——现路由到**创作模型**（[StoryGenerationService.creationConfig]）：质量敏感的总结活配强模型，
 * 与摘要压缩同口径（有意偏离 iOS 原结构化槽）。
 */
@Singleton
class StoryBibleCompressor @Inject constructor(
    private val llmClient: LlmClient,
    private val storyRepository: StoryRepository,
) {

    /** storyId → 本进程内连续失败次数（并发语义与摘要压缩 compressionFailureCounts 一致）。 */
    private val failureCounts = ConcurrentHashMap<String, Int>()

    /**
     * 触发即压缩，未触发/熔断中/无新增行则静默返回。重新拉取故事取最新状态（materialize 刚写入的圣经追加行）。
     *
     * @param latestChapterNumber 刚落库的最新章号
     * @param config 压缩用模型配置（调用方经 creationConfig() 解析 → 创作模型；null = 未配置，跳过）
     */
    internal suspend fun compressIfNeeded(storyId: String, latestChapterNumber: Int, config: ApiConfigValues?) {
        if (config == null) return
        val failedCount = failureCounts[storyId] ?: 0
        if (failedCount >= MAX_BIBLE_COMPRESSION_FAILURES) {
            Log.i(TAG, "圣经压缩跳过：连续失败 $failedCount 次，本进程内不再尝试 story=${storyId.take(8)}")
            return
        }
        val story = storyRepository.getStory(storyId) ?: return
        if (!StoryBibleCompression.shouldCompress(story.storyBible, story.lastBibleCompressedAtChapter, latestChapterNumber)) return
        val bible = story.storyBible ?: return

        val throughChapter = StoryBibleCompression.compressThroughChapter(latestChapterNumber)
        val split = StoryBibleCompression.split(bible, story.lastBibleCompressedAtChapter, throughChapter)
        if (split.compressLines.isEmpty()) return

        // ⑤点名对账闸的点名册（圣经压缩保真优化·图纸 §3.4）：只取**旧基底**里的角色名，压缩产物必须一个不落。
        // 新增逐章行里的名字不入册（自由文本无可靠名字边界）——守卫半径小而可靠 > 大而误杀。
        val rollCall = StoryBibleCompression.extractArchiveNames(split.base)

        val prompt = StoryBibleCompression.buildBibleCompressionPrompt(
            existingBase = split.base,
            newChapterLines = split.compressLines.joinToString("\n"),
            throughChapter = throughChapter,
            genre = story.genre,
        )
        val messages = listOf(
            ChatMessageDto(role = "system", content = prompt),
            ChatMessageDto(role = "user", content = "请执行档案整理。"),
        )
        try {
            var finishReason: String? = null
            val compressed = llmClient.completion(
                messages = messages, config = config, temperature = 0.1,
                // 思考模型专属额度：×3 给足推理余量（压缩现走创作槽），第一次就装得下 → 少截断少重试。
                maxTokens = StoryGenerationPromptBuilder.preferredCompressionMaxTokens(2_800, config.isThinkingModel),
                onFinishReason = { finishReason = it },
            )
            // 非流式 completion 不剥内联 <think>——storyBible 落库后逐章回注 prompt，必须剥净（含 trim）。
            val trimmed = StoryTextCleaning.cleanContentThinkingTags(compressed)
            // 四条件采纳闸（图纸 §3.4·顺序固定便于分因日志），任一不满足 = 这轮产物不可信 → 走既有失败路
            // （计失败 + 保旧圣经 + 下轮再试），四因共用同一计数与熔断，不设独立计数器：
            //  1/2 空 与 截断（记忆护栏 G2 同款单源判据 [LlmClient.isLengthTruncated]）：finish_reason=length
            //      （升额后仍被掐断，压缩现走创作槽·思考模型尤易触顶）→ 半截档案绝不落库回喂（会丢角色/伏笔且自我强化）。
            //  3 超长：诚实整理微超是常态且质量可用（下轮自我回正），硬线只拦「根本没干整理活」的离谱输出。
            //  4 点名对账：旧基底里的角色一个都不许在压缩后消失（复印件式磨损的最后一道闸）。
            // 日志只打计数与长度，绝不打角色名/档案正文（REDLINES §3 日志红线）。
            val rejectReason: String? = when {
                trimmed.isEmpty() || LlmClient.isLengthTruncated(finishReason) -> "空或截断 finish=$finishReason"
                trimmed.length > StoryBibleCompression.ARCHIVE_REJECT_CHAR_LIMIT ->
                    "超长 ${trimmed.length}字 > ${StoryBibleCompression.ARCHIVE_REJECT_CHAR_LIMIT}字"
                else -> rollCall.count { !trimmed.contains(it) }.takeIf { it > 0 }?.let { "点名对账缺${it}人" }
            }
            if (rejectReason == null) {
                val newBible = StoryBibleCompression.assembleCompressedBible(trimmed, split.keepLines)
                storyRepository.updateCompressedBible(storyId, newBible, throughChapter)
                failureCounts.remove(storyId)
                Log.d(TAG, "圣经压缩完成：截至第${throughChapter}章 → ${newBible.length}字（尾段留 ${split.keepLines.size} 行）")
            } else {
                failureCounts.merge(storyId, 1, Int::plus)
                Log.w(TAG, "圣经压缩未采纳（$rejectReason，计一次失败）story=${storyId.take(8)}")
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 协程取消不计失败（取消 ≠ 压缩本身坏），且**如实重抛**（卷一 chunk 3·照
            // [StoryGenerationService.requestCreation] 范式）：吞掉取消会让承载它的 job 以「正常完成」
            // 收场，日志还把取消误记成一次失败。
            throw e
        } catch (e: Exception) {
            failureCounts.merge(storyId, 1, Int::plus)
            Log.w(TAG, "圣经压缩失败（不影响正常使用，旧圣经保留）", e)
        }
    }

    private companion object {
        const val TAG = "StoryGeneration"

        /** 同一故事本进程内圣经压缩连续失败达此次数后不再尝试（对齐摘要压缩 MAX_COMPRESSION_FAILURES）。 */
        const val MAX_BIBLE_COMPRESSION_FAILURES = 2
    }
}
