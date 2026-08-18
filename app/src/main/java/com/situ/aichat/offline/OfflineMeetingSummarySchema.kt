package com.situ.aichat.offline

import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 见面摘要 v2（梦剧场 B 部·图纸 §3.5）：JSON prompt 模板 + 严格解析校验。**纯逻辑**（无 IO / 无 Android）——
 * LLM 调用与退避由 [OfflineSummaryRetryCoordinator] 编排（范式 [com.situ.aichat.gift.ProactiveGiftLLMService]）；
 * 本对象只负责「拼 user prompt」与「把 LLM 原文解析成 [MeetingSummaryDraft]（失败带回喂 prompt 的中文错误）」。
 * 复用 [JSONExtractor] + [MemoryService.strippingThinkingTags]（对齐 gift/redpacket 决策校验范式），便于 T1 逐例反推。
 *
 * ⚠️ 禁改：[buildUserPrompt] 模板逐字（一个标点不改·人称统一+记录名字化两变体锁在图纸
 * docs/handoff/2026-07-15-见面摘要总结提示词优化.md §4，2026-07-15 用户拍板取代 2026-07-11 日记体模板·D-5 重开锁）；解析产出的
 * [MeetingSummaryDraft.summary] **不含**【见面 · 】标题行——标题行由注入端 [OfflineMeetingMemoryRenderer] 唯一拼装。
 */
internal object OfflineMeetingSummarySchema {

    /** 结构化见面摘要草稿（§3.5）：summary=注入正文（不含标题行）；highlights/promises ≤3 条；mood=五枚举或 ""。 */
    data class MeetingSummaryDraft(
        val summary: String,
        val highlights: List<String>,
        val promises: List<String>,
        val mood: String,
    )

    /** 解析结果：[Success] 带草稿；[Failure] 带**面向 LLM 的中文错误**（回喂 prompt 做 retry-with-feedback）。 */
    sealed interface ParseResult {
        data class Success(val draft: MeetingSummaryDraft) : ParseResult
        data class Failure(val error: String) : ParseResult
    }

    /** 合法情绪枚举（小写）。 */
    val MOODS = setOf("warm", "sweet", "melancholic", "awkward", "neutral")

    /** summary 拒绝子串：中文书名号 + 9 种线下标签（防 LLM 把线下格式泄进摘要正文·§3.5/E5）。 */
    private val FORBIDDEN_TAG_SUBSTRINGS = listOf(
        "【", "[环境]", "[叙述]", "[对话]", "[动作]", "[内心]", "[情绪]", "[你]", "[时间]", "[场景", "[过渡]",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 摘要 user prompt（日记体·图纸 §4 逐字·单条 user·无 system）。[userName] = 用户昵称原值——空白或恰为
     * 「用户」走无名变体 B（记录标签由调用方用「对方」、日记正文称对方「你」，不退回「用户」）。[previousError]
     * 非空时末尾追加校验反馈。时间/时长/地点/活动/轮数为已格式化事实；[conversationRecord] 由调用方装配——自动/重试路经
     * [OfflineSummaryRetryCoordinator] → [MemoryService.formatMessages] 传真名字标签（用户侧=昵称/无昵称「对方」、角色侧=角色名）。
     */
    fun buildUserPrompt(
        characterName: String,
        startText: String, // yyyy-MM-dd HH:mm
        endText: String, // HH:mm
        durationText: String,
        location: String,
        activity: String,
        messageCount: Int,
        conversationRecord: String,
        userName: String,
        previousError: String? = null,
    ): String {
        val partner = userName.trim().takeIf { it.isNotEmpty() && it != "用户" }
        val meetLine = if (partner != null) "刚才你和${partner}线下见了一面" else "刚才你和对方线下见了一面"
        val addressRule = if (partner != null) "提到对方就写「$partner」，不要用「你」" else "提到对方就写「你」"
        val highlightExample =
            if (partner != null) "${partner}记得我怕辣，悄悄跟店员说了不要辣" else "你记得我怕辣，悄悄跟店员说了不要辣"
        val base = """
你就是角色「$characterName」本人。$meetLine，这是你们真实的相处。
见面事实（系统已记录，不用复述）：
- 时间：$startText 至 $endText（约$durationText）
- 地点：$location；活动：$activity；共 $messageCount 轮对话

## 这次见面的对话记录
$conversationRecord

现在请你以第一人称、像睡前写日记一样回忆这次见面。写作要求：用「我」指代你自己，$addressRule，绝对不要出现「用户」这个词；自由中文句子，不用列表、不用标签、不用 markdown。

只输出一个 JSON 对象，不要任何其他文字：
{
  "summary": "100-200 字一段话，日记口吻：我们做了什么、聊了什么、最打动我的瞬间、我们的情绪基调。",
  "highlights": ["最多 3 条难忘的具体细节，每条从「我」的视角写完整一句、带上是谁（我/名字），例：「$highlightExample」；没有就给空数组"],
  "promises": ["最多 3 条这次见面里许下的约定、说好的下次、或没说完的话头；没有就给空数组"],
  "mood": "从 warm/sweet/melancholic/awkward/neutral 里选一个最贴切的"
}
        """.trim()
        return if (previousError.isNullOrEmpty()) {
            base
        } else {
            base + "\n\n上一次输出未通过校验：$previousError。请修正后重新只输出 JSON。"
        }
    }

    /**
     * 严格解析校验（§3.5·范式 ProactiveGiftLLMService.parseAndValidate）：strip think → [JSONExtractor] → [JsonObject]。
     * summary 必填 / trim 后 40–400 字符 / 不含线下标签；highlights/promises 字符串数组各 trim 去空、截 ≤60、取前 3；
     * mood ∈ 五枚举否则置 ""。失败返回具体中文错误（喂回 prompt 做 retry-with-feedback）。
     */
    fun parseAndValidate(response: String): ParseResult {
        val cleaned = MemoryService.strippingThinkingTags(response)
        val jsonStr = JSONExtractor.extract(cleaned)
        val obj = runCatching { json.parseToJsonElement(jsonStr) }.getOrNull() as? JsonObject
            ?: return ParseResult.Failure("输出不是合法的 JSON 对象")

        val summary = stringField(obj, "summary")?.trim()
        if (summary.isNullOrEmpty()) return ParseResult.Failure("summary 必须是非空字符串")
        if (summary.length < 40) return ParseResult.Failure("summary 太短（需 100-200 字，至少 40 字）")
        if (summary.length > 400) return ParseResult.Failure("summary 太长（需 100-200 字，最多 400 字）")
        FORBIDDEN_TAG_SUBSTRINGS.firstOrNull { summary.contains(it) }?.let {
            return ParseResult.Failure("summary 不能包含线下格式标签「$it」，请写成自然中文句子")
        }

        val highlights = stringArray(obj, "highlights")
        val promises = stringArray(obj, "promises")
        // 日记体硬闸（微图纸 §4/§7）：文本「裸用」用户即打回（retry-with-feedback 换稿）；职业/领域复合词豁免。
        if ((listOf(summary) + highlights + promises).any { containsBareYonghu(it) }) {
            return ParseResult.Failure("摘要中不能出现「用户」这个词，请用名字或「你」称呼对方")
        }
        val mood = stringField(obj, "mood")?.trim()?.lowercase()?.takeIf { it in MOODS } ?: ""

        return ParseResult.Success(MeetingSummaryDraft(summary, highlights, promises, mood))
    }

    /** 「用户」豁免复合词（微图纸 §7 逐字·后缀命中即放行）：只拦「用户」作称呼的裸用法，漏网词按需追加。 */
    private val YONGHU_EXEMPT_COMPOUNDS = listOf("用户运营", "用户体验", "用户研究", "用户调研", "用户增长", "用户画像")

    /** 文本是否「裸用」了「用户」：逐处扫描，该处起匹配任一豁免复合词则放行继续，任一处不匹配即裸用。 */
    internal fun containsBareYonghu(text: String): Boolean {
        var i = text.indexOf("用户")
        while (i >= 0) {
            if (YONGHU_EXEMPT_COMPOUNDS.none { text.startsWith(it, i) }) return true
            i = text.indexOf("用户", i + 2)
        }
        return false
    }

    /** JSON 字符串字段（须为 JSON string 原语，否则视为缺失·对齐 iOS `json[key] as? String`）。 */
    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** JSON 字符串数组：缺省 / 非数组 → 空；每项须为字符串，trim 去空、截前 60 字符、取前 3（§3.5）。 */
    private fun stringArray(obj: JsonObject, key: String): List<String> {
        val arr = obj[key] as? JsonArray ?: return emptyList()
        return arr
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(60) }
            .take(3)
    }
}
