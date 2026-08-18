package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 故事章节「三级防御解析 + 截断续写」协作者（自 [StoryGenerationService] 拆出 · 文件瘦身，**行为零改 / 逐字搬**）。
 *
 * 职责：把创作模型的原始输出定稿为可落库的 [StoryChapterPayload]——三级 resolvePayload（L1 纯代码解析 →
 * L2 轻量补全 → L3 元数据 LLM 结构化 → 最终兜底）与截断续写 requestContinuation，含配套 LLM 私有助手
 * （requestMetadataCompletion / requestMetadataStructuring）及续写系统提示词常量。
 * 由 [StoryGenerationService.generateChapter] 编排调用；日志沿用同一 TAG（"StoryGeneration"）。
 *
 * **正文保命铁律（图纸一 C2）**：三级里没有任何一条路把正文交给 LLM——L3 只发元数据文本，
 * 正文恒取代码切分侧（[StoryMetadataParser] 的产物）。「整章送去重整理」那条老路已连同 JSON 修复器一并拆除。
 */
@Singleton
class StoryPayloadResolver @Inject constructor(
    private val llmClient: LlmClient,
    private val contextLog: ContextLogService,
) {

    /**
     * 三级防御解析：L1 纯代码解析（必填+质量字段齐全，0 token）→ L2 轻量补全（缺质量字段时 LLM 补，失败用已有+默认）
     * → L3 元数据 LLM 结构化（**只发元数据，正文恒用代码切分侧**）→ 最终兜底（默认元数据 + 代码切分正文）。
     * 正文为空 → 抛 [StoryGenerationError.EmptyResponse]。
     */
    internal suspend fun resolvePayload(
        rawOutput: String,
        chapterNumber: Int,
        structConfig: ApiConfigValues,
    ): StoryChapterPayload {
        val diagnostics = mutableListOf<String>()
        val parseResult = StoryMetadataParser.parse(rawOutput, diagnostics)
        if (diagnostics.isNotEmpty()) {
            // §7/§11 观测点：解析剥离计数（只打标签名+位置，正文内容与 key 绝不进日志）
            Log.i(TAG, "解析剥离 ${diagnostics.size} 处 $diagnostics")
        }
        if (parseResult.content.isEmpty()) {
            Log.e(TAG, "故事正文为空，无法继续")
            throw StoryGenerationError.EmptyResponse
        }

        if (parseResult.hasRequiredFields && parseResult.hasQualityFields) {
            Log.d(TAG, "解析路径：第一级·纯代码解析（0 token）")
            return StoryGenerationParsing.buildPayload(parseResult, chapterNumber)
        }

        if (parseResult.hasRequiredFields) {
            val missingFields = parseResult.missingQualityFieldNames
            if (missingFields.isNotEmpty()) {
                Log.d(TAG, "解析路径：第二级·轻量补全（缺失：${missingFields.joinToString("、")}）")
                val storyEnd = parseResult.content.takeLast(500)
                val completionPrompt = StoryMetadataParser.buildCompletionPrompt(storyEnd, missingFields)
                val completed = runCatching {
                    requestMetadataCompletion(completionPrompt, parseResult, structConfig)
                }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }.getOrNull()
                if (completed != null) {
                    Log.d(TAG, "轻量补全成功")
                    return StoryGenerationParsing.buildPayload(completed, chapterNumber)
                }
                Log.e(TAG, "轻量补全失败 → 使用已有字段 + 默认值")
            }
            Log.d(TAG, "解析路径：第二级降级·已有字段 + 默认值")
            return StoryGenerationParsing.buildPayload(parseResult, chapterNumber)
        }

        Log.w(TAG, "解析路径：第三级·元数据 LLM 结构化（正文恒用代码切分侧）")
        val metaText = parseResult.rawMetadataText
        if (metaText != null) {
            // LLM 整理失败只损失元数据（buildPayload 补默认），绝不毁掉整次生成——正文明明在手（图纸 §0.3-12）。
            val jsonText = runCatching { requestMetadataStructuring(metaText, structConfig) }
                .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
                .getOrNull()
            val structured = jsonText?.let { StoryGenerationParsing.decodeMetadataFields(it) }
            if (structured != null) {
                Log.d(TAG, "元数据结构化成功")
                return StoryGenerationParsing.buildPayload(
                    StoryGenerationParsing.mergeStructuredMetadata(parseResult, structured),
                    chapterNumber,
                )
            }
            Log.e(TAG, "元数据结构化失败 → 最终兜底")
        }
        Log.w(TAG, "解析路径：最终兜底·默认元数据 + 代码切分正文")
        return StoryGenerationParsing.buildPayload(parseResult, chapterNumber)
    }

    /** 第二级·轻量补全（1:1 iOS `requestMetadataCompletion` :167-200）：LLM 只补缺失字段，与已有结果字段级合并。 */
    private suspend fun requestMetadataCompletion(
        prompt: String,
        baseResult: StoryMetadataParser.ParseResult,
        config: ApiConfigValues,
    ): StoryMetadataParser.ParseResult {
        val messages = listOf(
            ChatMessageDto(role = "system", content = prompt),
            ChatMessageDto(role = "user", content = "请补充以上缺失的元数据字段。"),
        )
        val responseText = llmClient.completion(
            messages = messages, config = config, temperature = 0.1, maxTokens = 800,
            responseFormat = ResponseFormatDto("json_object"),
        )
        // 非流式 completion 不剥内联 <think>；逐行「key: value」解析会认走思考里的草稿行（summary: … 等），
        // 且 characterStates/openThreads 会拼进 storyBible——解析前剥净（内联 think 的开源模型未必遵守 json_object）。
        val completionFields = StoryMetadataParser.parse("---METADATA---\n" + StoryTextCleaning.cleanContentThinkingTags(responseText))
        return StoryGenerationParsing.mergeMetadataCompletion(baseResult, completionFields)
    }

    /**
     * 第三级·元数据结构化（图纸一 C2 第 3 层）：**只把元数据文本发给 LLM，正文一个字都不发**——正文恒由
     * 代码切分侧提供，模型产物结构上不可能替换正文。空响应 → 抛 EmptyResponse（由调用侧 runCatching 吞、走兜底）。
     *
     * maxTokens 恒 1_500 不分档：元数据量与章节长度无关（十二字段各 ≤150 字级）；结构化槽若配思考模型撞限，
     * [LlmClient.completion] 自带的升额 ×3 重试已兜。
     */
    private suspend fun requestMetadataStructuring(metadataText: String, config: ApiConfigValues): String {
        val messages = listOf(
            ChatMessageDto(
                role = "system",
                content = StoryGenerationPromptBuilder.buildMetadataStructuringPrompt(metadataText),
            ),
            ChatMessageDto(role = "user", content = "请将上述元数据整理为一行 JSON。"),
        )
        val responseText = llmClient.completion(
            messages = messages, config = config, temperature = 0.1, maxTokens = 1_500,
            responseFormat = ResponseFormatDto("json_object"),
        )
        val trimmed = responseText.trim()
        if (trimmed.isEmpty()) throw StoryGenerationError.EmptyResponse
        return trimmed
    }

    /**
     * 截断续写（1:1 iOS `requestContinuation` :354-380）：从断点自然续写到段落结尾。失败/空 → 返回原内容（不阻断保存）。
     * 取原内容末 500 字作上下文，拼接续写结果返回。
     *
     * [temperature] = 故事创作温度（卷一 V1）：与正章共用同一设置值——续写补的也是创作散文，冷参数会写出与正章割裂的腔调。
     */
    internal suspend fun requestContinuation(
        truncatedContent: String,
        config: ApiConfigValues,
        temperature: Double,
        maxTokens: Int = 2_000,
    ): String {
        val messages = listOf(
            ChatMessageDto(role = "system", content = CONTINUATION_SYSTEM_PROMPT),
            ChatMessageDto(
                role = "user",
                content = "以下内容在句子中间被截断了，请自然地续写完成：\n\n${truncatedContent.takeLast(500)}",
            ),
        )
        return try {
            // 批 D 上下文日志：主生成（截断续写），经 contextLog 落库（source=STORY_GENERATION·用户级 ""·重任务截断）。
            val continuation = contextLog.completion(
                source = LogSource.STORY_GENERATION,
                characterName = "",
                config = config,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens,
            )
            // 剥标签必须在拼接前对续写响应单独做：若续写带孤闭合 </think>（前文全是思考、连前文一起删），
            // 拼接后再剥会把前面的真正文一并误删。剥空 = 纯思考续写，视同失败返回原内容（不阻断保存）。
            val trimmed = StoryTextCleaning.cleanContentThinkingTags(continuation)
            if (trimmed.isEmpty()) truncatedContent else truncatedContent + trimmed
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 取消如实传播（照 [StoryGenerationService.requestCreation] 范式）：取消 ≠ 续写失败，
            // 吞掉它会让已取消的生成继续往下走完落库。
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "续写失败，返回原内容", e)
            truncatedContent
        }
    }

    private companion object {
        // ST2「零新字符串字面量」的唯一例外（R1 🔵-6·就地记档）：有意与 StoryGenerationService.TAG 同字面
        // （"StoryGeneration"），让本协作者的 Logcat 延续同一日志流（见类 KDoc）——非搬移遗漏，行为零差。
        const val TAG = "StoryGeneration"
    }
}

/** 截断续写系统提示（1:1 iOS `requestContinuation` 内 systemPrompt :359）。 */
private const val CONTINUATION_SYSTEM_PROMPT =
    "你是一个故事续写助手。用户会给你一段被截断的故事内容，请你从断点处自然地继续写下去，直到这个场景自然结束（以完整的句子和标点收尾）。" +
        "规则：1. 直接接着写，不要重复已有内容；2. 保持原文的风格、人称和语气；3. 不要加任何标注、说明或元数据；4. 写到一个自然的段落结尾就停。"
