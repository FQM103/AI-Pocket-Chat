package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.util.JSONExtractor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 结构化记忆提取错误（对齐 iOS StructuredMemoryError）。 */
sealed class StructuredMemoryError(message: String) : Exception(message) {
    data object NoMessages : StructuredMemoryError("没有可分析的消息")
    data class InvalidResponse(val detail: String) : StructuredMemoryError("无法解析提取结果：$detail")
}

/**
 * 1:1 port of iOS `StructuredMemoryService`（Services/StructuredMemoryService.swift）——从对话记录中提取
 * 10 个固定字段的人际关系记忆碎片（称呼/内部梗/印象/约定…）。无状态、独立于成长分析。
 *
 * LLM 输出为 **snake_case** JSON（[RawStructuredMemory]），解析后映射到 camelCase 的 [StructuredMemory]
 * （与 iOS RawStructuredMemory→StructuredMemory 一致）。`temperature=0.1`、`response_format=json_object`。
 *
 * ⚠️ 抽取 prompt 复用 [MemoryService.formatMessages] 渲染对话，沿用同一套脏消息/邀约卡剥离（spec M05 §1.3）。
 */
@Singleton
class StructuredMemoryService @Inject constructor(
    private val contextLog: ContextLogService,
) {

    suspend fun extractMemory(
        messages: List<MessageEntity>,
        existing: StructuredMemory,
        characterName: String,
        config: ApiConfigValues,
        userName: String,
    ): StructuredMemory {
        if (messages.isEmpty()) throw StructuredMemoryError.NoMessages

        val (systemPrompt, userPrompt) = buildExtractionPrompt(messages, existing, characterName, userName)
        val chatMessages = listOf(
            ChatMessageDto(role = "system", content = systemPrompt),
            ChatMessageDto(role = "user", content = userPrompt),
        )

        val response = contextLog.completion(
            source = LogSource.STRUCTURED_MEMORY,
            characterName = characterName,
            config = config,
            messages = chatMessages,
            temperature = 0.1,
            responseFormat = ResponseFormatDto(type = "json_object"),
        )
        return parseResponse(response)
    }

    // MARK: - 提示词构建

    private fun buildExtractionPrompt(
        messages: List<MessageEntity>,
        existing: StructuredMemory,
        characterName: String,
        userName: String,
    ): Pair<String, String> {
        val charName = characterName
        val uName = userName.ifEmpty { "用户" }
        val existingText = buildExistingText(existing, charName, uName)

        val systemPrompt = SYSTEM_TEMPLATE
            .replace("{{char}}", charName)
            .replace("{{user}}", uName)
            .replace("{{existing}}", existingText)

        // 第三人称指名（2026-07-14·D-3）：对话记录说话人直接渲染真实名字（uName 已带「用户」兜底·:72）。
        val conversationText = MemoryService.formatMessages(messages, userLabel = uName, charLabel = charName)
        val userPrompt = "以下是最近的对话记录，请提取关键的关系记忆碎片：\n\n$conversationText"

        return systemPrompt to userPrompt
    }

    /** 已有数据展示（让模型知道当前状态，避免丢失已有信息）。对齐 iOS buildExtractionPrompt 的 existingText。 */
    private fun buildExistingText(existing: StructuredMemory, charName: String, uName: String): String {
        if (!existing.hasAnyData) return ""
        val lines = mutableListOf<String>()
        if (existing.nicknameFromChar.isNotEmpty()) lines.add("- ${charName}对${uName}的称呼: ${existing.nicknameFromChar}")
        if (existing.nicknameToChar.isNotEmpty()) lines.add("- ${uName}对${charName}的称呼: ${existing.nicknameToChar}")
        if (existing.insideJoke.isNotEmpty()) lines.add("- 内部梗: ${existing.insideJoke}")
        if (existing.deepestChat.isNotEmpty()) lines.add("- 最深刻的聊天: ${existing.deepestChat}")
        if (existing.impressionOfUser.isNotEmpty()) lines.add("- ${charName}眼中的${uName}: ${existing.impressionOfUser}")
        if (existing.sharedLikes.isNotEmpty()) lines.add("- 共同喜欢的: ${existing.sharedLikes}")
        if (existing.learnedPhrase.isNotEmpty()) lines.add("- 学会的口头禅: ${existing.learnedPhrase}")
        if (existing.importantPromise.isNotEmpty()) lines.add("- 最重要的约定: ${existing.importantPromise}")
        if (existing.firstConflict.isNotEmpty()) lines.add("- 第一次闹矛盾: ${existing.firstConflict}")
        if (existing.comfortStyle.isNotEmpty()) lines.add("- 安慰方式: ${existing.comfortStyle}")
        return "\n## 已有记忆（之前提取的）\n" + lines.joinToString("\n")
    }

    // MARK: - JSON 解析（多候选容错：剥思考标签 → 直解 / 提取花括号）

    private fun parseResponse(response: String): StructuredMemory {
        val cleaned = MemoryService.strippingThinkingTags(response)
        val candidates = listOf(cleaned.trim(), JSONExtractor.extract(cleaned))
        for (candidate in candidates) {
            val raw = runCatching { json.decodeFromString(RawStructuredMemory.serializer(), candidate) }.getOrNull()
                ?: continue
            return StructuredMemory(
                nicknameFromChar = (raw.nicknameFromChar ?: "").trim(),
                nicknameToChar = (raw.nicknameToChar ?: "").trim(),
                insideJoke = (raw.insideJoke ?: "").trim(),
                deepestChat = (raw.deepestChat ?: "").trim(),
                impressionOfUser = (raw.impressionOfUser ?: "").trim(),
                sharedLikes = (raw.sharedLikes ?: "").trim(),
                learnedPhrase = (raw.learnedPhrase ?: "").trim(),
                importantPromise = (raw.importantPromise ?: "").trim(),
                firstConflict = (raw.firstConflict ?: "").trim(),
                comfortStyle = (raw.comfortStyle ?: "").trim(),
            )
        }
        throw StructuredMemoryError.InvalidResponse("解析失败：所有候选文本均无法解码为有效 JSON")
    }

    /** 与 LLM 输出 JSON 一一对应的 snake_case 原始结构（全可选，容错；对齐 iOS RawStructuredMemory）。 */
    @Serializable
    private data class RawStructuredMemory(
        @SerialName("nickname_from_char") val nicknameFromChar: String? = null,
        @SerialName("nickname_to_char") val nicknameToChar: String? = null,
        @SerialName("inside_joke") val insideJoke: String? = null,
        @SerialName("deepest_chat") val deepestChat: String? = null,
        @SerialName("impression_of_user") val impressionOfUser: String? = null,
        @SerialName("shared_likes") val sharedLikes: String? = null,
        @SerialName("learned_phrase") val learnedPhrase: String? = null,
        @SerialName("important_promise") val importantPromise: String? = null,
        @SerialName("first_conflict") val firstConflict: String? = null,
        @SerialName("comfort_style") val comfortStyle: String? = null,
    )

    private companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 抽取提示词模板（硬编码中文，对齐 iOS buildExtractionPrompt 的 systemPrompt）。
         * 用占位符 `{{char}}`/`{{user}}`/`{{existing}}` 而非 Kotlin 插值，避免 trimIndent 与多行插值内容相互干扰。
         */
        private val SYSTEM_TEMPLATE: String = """
            你是一个对话记忆提取师。你的唯一任务是从对话记录中提取关键的人际关系记忆碎片。

            ## 角色信息
            - 角色名：{{char}}
            - 用户名：{{user}}
            {{existing}}

            ## 提取规则
            1. 只填有明确对话证据的字段，没有证据的留空字符串 ""
            2. 每个字段内容简短精炼，不超过 20 个字
            3. 如果已有值仍然准确，保持不变；有更好的描述才更新
            4. first_conflict 只在第一次出现时填写，之后保持不变不要覆盖
            5. 所有内容用中文

            ## 字段说明
            - nickname_from_char: {{char}}对{{user}}的称呼（如"小笨蛋"、"亲爱的"）
            - nickname_to_char: {{user}}对{{char}}的称呼（如"宝宝"、"老师"）
            - inside_joke: 只有两人才懂的梗或暗语
            - deepest_chat: 印象最深的一次对话主题
            - impression_of_user: {{char}}对{{user}}的整体印象
            - shared_likes: 两人共同喜欢的东西
            - learned_phrase: {{char}}从{{user}}那学到的口头禅或表达方式
            - important_promise: 两人之间最有分量的一个约定（完整的约定清单由系统单独维护，这里只挑最重要的一条）
            - first_conflict: 第一次闹矛盾的原因（只填首次，后续不覆盖）
            - comfort_style: {{char}}安慰{{user}}时的典型方式

            ## 输出格式
            请严格以 JSON 格式输出，不要包含任何其他文字：
            {
              "nickname_from_char": "",
              "nickname_to_char": "",
              "inside_joke": "",
              "deepest_chat": "",
              "impression_of_user": "",
              "shared_likes": "",
              "learned_phrase": "",
              "important_promise": "",
              "first_conflict": "",
              "comfort_style": ""
            }
            """.trimIndent()
    }
}
