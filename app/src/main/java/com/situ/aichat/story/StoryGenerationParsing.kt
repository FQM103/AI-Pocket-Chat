package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.prompt.memory.MemoryService
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `StoryGenerationService` 的纯逻辑伴生：payload 组装/编码、截断与摘要压缩判定、LLM 输出 JSON 弹性解析
 * （1:1 iOS `StoryGenerationService(.swift/+Parsing)` 中的 nonisolated 纯函数）。零 LLM/DB 依赖、100% 可单测。
 *
 * - 11.1e-1 payload 组装层：buildPayload / encodeChoiceOptions / isContentTruncated /
 *   payloadWithContinuation / shouldCompressSummary + 共享常量。
 * - 11.1e-2 JSON 弹性解析：stripThinkingTagsForJSON / cleanBufferForPreview（流式预览）/ preprocessJSONText /
 *   normalizedJSONCandidates / decodePayload。
 * LLM 编排（resolvePayload/requestCreation/requestStructuring…）与 materialize 落库在后续 e 子块。
 */
internal object StoryGenerationParsing {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 创作输出里正文与元数据的分隔符（**红线**：值与 [StoryFormatRules] 提示词、[StoryMetadataParser] 解析端
     * 必须逐字节一致；本常量只是把既有字面量收拢到一处，值一个字节未变）。
     */
    const val METADATA_DELIMITER = "---METADATA---"

    /** 摘要压缩触发字数阈值（1:1 iOS `summaryCompressionWordThreshold` :65）。 */
    const val SUMMARY_COMPRESSION_WORD_THRESHOLD = 3_000

    /** 摘要压缩章节间隔（1:1 iOS `summaryCompressionChapterInterval` :67）。 */
    const val SUMMARY_COMPRESSION_CHAPTER_INTERVAL = 8

    /**
     * 由纯代码解析结果组装 payload（1:1 iOS `buildPayload` +Parsing:141-165）。
     * 兜底：title→「第N章」、mood→peaceful；hasChoice 缺失时只要有选项或提示语即判 true。
     */
    fun buildPayload(result: StoryMetadataParser.ParseResult, chapterNumber: Int): StoryChapterPayload {
        val hasOptions = !result.choiceOptions.isNullOrEmpty()
        val hasPrompt = !result.choicePrompt.isNullOrEmpty()
        val finalHasChoice = result.hasChoice ?: (hasOptions || hasPrompt)

        return StoryChapterPayload(
            title = result.title ?: "第${chapterNumber}章",
            teaser = result.teaser,
            mood = result.mood ?: "peaceful",
            content = result.content,
            hasChoice = finalHasChoice,
            choicePrompt = result.choicePrompt,
            choiceOptions = result.choiceOptions,
            summary = result.summary,
            currentArc = result.currentArc,
            isEnding = result.isEnding,
            characterStates = result.characterStates,
            openThreads = result.openThreads,
            nextChapterBeats = result.nextChapterBeats,
            // 故事二期卷一：三个可选字段直传（兜底/归一都不在这层——「无」的判定在落库口）
            intimacyUpdates = result.intimacyUpdates,
            sceneEndState = result.sceneEndState,
            sceneTag = result.sceneTag,
        )
    }

    /**
     * 轻量补全（第二级）合并：以纯代码解析的 [base] 为主，仅用 LLM 补全结果 [completion] 填补 base 缺失的可选字段
     * （1:1 iOS `requestMetadataCompletion` :184-199 的字段级 `base ?? completion`）。content/title/mood 恒取 base，不合并。
     */
    fun mergeMetadataCompletion(
        base: StoryMetadataParser.ParseResult,
        completion: StoryMetadataParser.ParseResult,
    ): StoryMetadataParser.ParseResult = base.copy(
        teaser = base.teaser ?: completion.teaser,
        hasChoice = base.hasChoice ?: completion.hasChoice,
        choicePrompt = base.choicePrompt ?: completion.choicePrompt,
        choiceOptions = base.choiceOptions ?: completion.choiceOptions,
        summary = base.summary ?: completion.summary,
        currentArc = base.currentArc ?: completion.currentArc,
        characterStates = base.characterStates ?: completion.characterStates,
        openThreads = base.openThreads ?: completion.openThreads,
        nextChapterBeats = base.nextChapterBeats ?: completion.nextChapterBeats,
        isEnding = base.isEnding ?: completion.isEnding,
    )

    /**
     * 第三级元数据结构化的 LLM 产物（图纸一 §3.3-2）。**十五字段与
     * [StoryGenerationPromptBuilder.buildMetadataStructuringPrompt] 的「## 输出格式」行一一镜像，一个不多一个不少**
     * ——METADATA 字段口径在解码侧的镜像（故事二期卷一 D-1 修订后 = 12 既有 + 3 可选）。
     * 全可空：模型漏写即 null，交 [buildPayload] 兜默认。
     */
    @Serializable
    private data class MetadataFieldsJson(
        val title: String? = null,
        val teaser: String? = null,
        val mood: String? = null,
        val hasChoice: Boolean? = null,
        val choicePrompt: String? = null,
        val choiceOptions: List<String>? = null,
        val summary: String? = null,
        val currentArc: String? = null,
        val characterStates: String? = null,
        val openThreads: String? = null,
        val nextChapterBeats: String? = null,
        val isEnding: Boolean? = null,
        val intimacyUpdates: String? = null,
        val sceneEndState: String? = null,
        val sceneTag: String? = null,
    )

    /**
     * 第三级·元数据 JSON 解码（图纸一 §3.3-2）：逐候选复用 [normalizedJSONCandidates]（去 think / 去围栏 /
     * 取花括号子串 / 预处理），首个解得通的即转 ParseResult；全候选失败 → null。
     *
     * **content 恒空串占位**——正文由代码切分侧提供、合并时被 base 覆盖，LLM 文本结构上进不了正文。空串字段
     * 折叠 null（与 [StoryMetadataParser] 跳空值同哲学）；mood 过 [StoryMoods.normalize]（与一级同口径，怪值
     * →null→[buildPayload] 兜 peaceful）；空选项列表折叠 null。
     */
    fun decodeMetadataFields(responseText: String): StoryMetadataParser.ParseResult? {
        for (candidate in normalizedJSONCandidates(responseText)) {
            val f = runCatching { json.decodeFromString<MetadataFieldsJson>(candidate) }.getOrNull() ?: continue
            return StoryMetadataParser.ParseResult(
                content = "",
                title = f.title?.takeIf { it.isNotEmpty() },
                teaser = f.teaser?.takeIf { it.isNotEmpty() },
                mood = StoryMoods.normalize(f.mood),
                hasChoice = f.hasChoice,
                choicePrompt = f.choicePrompt?.takeIf { it.isNotEmpty() },
                choiceOptions = f.choiceOptions?.takeIf { it.isNotEmpty() },
                summary = f.summary?.takeIf { it.isNotEmpty() },
                currentArc = f.currentArc?.takeIf { it.isNotEmpty() },
                characterStates = f.characterStates?.takeIf { it.isNotEmpty() },
                openThreads = f.openThreads?.takeIf { it.isNotEmpty() },
                nextChapterBeats = f.nextChapterBeats?.takeIf { it.isNotEmpty() },
                isEnding = f.isEnding,
                intimacyUpdates = f.intimacyUpdates?.takeIf { it.isNotEmpty() },
                sceneEndState = f.sceneEndState?.takeIf { it.isNotEmpty() },
                sceneTag = f.sceneTag?.takeIf { it.isNotEmpty() },
            )
        }
        return null
    }

    /**
     * 第三级合并（图纸一 §3.3-3）：**content 恒取 [base]**（代码切分侧的正文），其余十二字段 `base ?: structured`。
     *
     * 与 [mergeMetadataCompletion] 的分工——**看着像重复，绝不许合成一个**：二级走的前提是必填字段已齐
     * （base 必有 title/mood），故那边 title/mood 恒取 base 不合并；三级走的前提恰恰是必填字段不齐
     * （base 缺 title/mood），故这边 **title/mood 必须参与合并**，否则 LLM 整理白做。
     */
    fun mergeStructuredMetadata(
        base: StoryMetadataParser.ParseResult,
        structured: StoryMetadataParser.ParseResult,
    ): StoryMetadataParser.ParseResult = base.copy(
        title = base.title ?: structured.title,
        teaser = base.teaser ?: structured.teaser,
        mood = base.mood ?: structured.mood,
        hasChoice = base.hasChoice ?: structured.hasChoice,
        choicePrompt = base.choicePrompt ?: structured.choicePrompt,
        choiceOptions = base.choiceOptions ?: structured.choiceOptions,
        summary = base.summary ?: structured.summary,
        currentArc = base.currentArc ?: structured.currentArc,
        characterStates = base.characterStates ?: structured.characterStates,
        openThreads = base.openThreads ?: structured.openThreads,
        nextChapterBeats = base.nextChapterBeats ?: structured.nextChapterBeats,
        isEnding = base.isEnding ?: structured.isEnding,
        intimacyUpdates = base.intimacyUpdates ?: structured.intimacyUpdates,
        sceneEndState = base.sceneEndState ?: structured.sceneEndState,
        sceneTag = base.sceneTag ?: structured.sceneTag,
    )

    /** 选项数组编码为 JSON 串供 [com.situ.aichat.data.local.entity.StoryChapterEntity.choiceOptions] 存储（1:1 iOS `encodeChoiceOptions` :541-545）。空/失败→null。 */
    fun encodeChoiceOptions(choiceOptions: List<String>?): String? {
        if (choiceOptions.isNullOrEmpty()) return null
        return runCatching { json.encodeToString(choiceOptions) }.getOrNull()
    }

    /** 用续写完成的内容替换截断 payload（1:1 iOS `payloadWithContinuation` :70-86）。 */
    fun payloadWithContinuation(payload: StoryChapterPayload, completedContent: String): StoryChapterPayload =
        payload.copy(content = completedContent)

    /**
     * 检测章节内容是否被截断（未以正常句末标点结尾，1:1 iOS `isContentTruncated` :345-350）。
     * 空内容视为截断（true）。
     */
    fun isContentTruncated(content: String): Boolean {
        val trimmed = content.trim()
        val lastChar = trimmed.lastOrNull() ?: return true
        return lastChar !in SENTENCE_ENDING_CHARS
    }

    /**
     * 是否触发摘要压缩（1:1 iOS `shouldCompressSummary` :88-97）：距上次压缩 ≥ 间隔，
     * 且 (lastCompressed, chapterNumber] 区间各章摘要总字数 > 阈值。
     *
     * @param chapterSummaries 该故事所有章的摘要投影（[StoryChapterSummaryRow]，不含正文），由生成服务预取
     */
    fun shouldCompressSummary(
        lastCompressedAtChapter: Int?,
        chapterNumber: Int,
        chapterSummaries: List<StoryChapterSummaryRow>,
    ): Boolean {
        val lastCompressed = lastCompressedAtChapter ?: 0
        if (chapterNumber - lastCompressed < SUMMARY_COMPRESSION_CHAPTER_INTERVAL) return false
        val uncompressedWordCount = chapterSummaries
            .filter { it.chapterNumber > lastCompressed && it.chapterNumber <= chapterNumber }
            .mapNotNull { it.chapterSummary }
            .sumOf { it.length }
        return uncompressedWordCount > SUMMARY_COMPRESSION_WORD_THRESHOLD
    }

    /**
     * 拼接待压缩的「新增章节摘要」块（1:1 iOS `compressSummaryChainIfNeeded` :98-104，纯函数）：
     * 取 (lastCompressedChapter, currentChapter] 区间各章，摘要非空者格式化为「第N章：摘要」，按入参顺序以 \n 连接。
     *
     * @param chapterSummaries 该故事所有章摘要投影（[StoryChapterSummaryRow]，**期望按章号升序**，由生成服务经
     *   [com.situ.aichat.data.local.dao.StoryDao.getChapterSummaries] 预取）；全空区间 → 空串（调用方据此跳过压缩）
     */
    fun buildNewSummariesBlock(
        chapterSummaries: List<StoryChapterSummaryRow>,
        lastCompressedChapter: Int,
        currentChapter: Int,
    ): String = chapterSummaries
        .filter { it.chapterNumber > lastCompressedChapter && it.chapterNumber <= currentChapter }
        .mapNotNull { row -> row.chapterSummary?.takeIf { it.isNotEmpty() }?.let { "第${row.chapterNumber}章：$it" } }
        .joinToString("\n")

    // MARK: - LLM 输出 JSON 弹性解析（11.1e-2，1:1 iOS +Parsing.swift:277-492）

    /**
     * 故事解析专用：strip 思考标签，但若结果为空或不含 JSON 花括号则返回原文（整段可能本就是 JSON）。
     * 1:1 iOS `stripThinkingTagsForJSON` :277-284。
     */
    fun stripThinkingTagsForJSON(text: String): String {
        val result = MemoryService.strippingThinkingTags(text)
        if (result.isEmpty() || !result.contains("{")) return text
        return result
    }

    /**
     * 流式创作预览清理（1:1 iOS `cleanBufferForPreview` :313-354）：截到 `---METADATA---`（大小写不敏感）之前→
     * 去 think 标签（闭合+未闭）→去沉浸标记→压缩 3 连空行→trim→只留末 200 字。
     *
     * @return (preview, reachedMetadata)；reachedMetadata 用于停止后续预览刷新
     */
    fun cleanBufferForPreview(buffer: String): Pair<String, Boolean> {
        var text = buffer
        var reachedMetadata = false

        val mdIndex = text.indexOf(METADATA_DELIMITER, ignoreCase = true)
        if (mdIndex >= 0) {
            text = text.substring(0, mdIndex)
            reachedMetadata = true
        }

        text = THINK_CLOSED_REGEX.replace(text, "")
        text = THINK_UNCLOSED_REGEX.replace(text, "")
        text = PREVIEW_MARKUP_TAG_REGEX.replace(text, "")

        while (text.contains("\n\n\n")) {
            text = text.replace("\n\n\n", "\n\n")
        }

        text = text.trim()
        if (text.length > 200) text = text.takeLast(200)

        return text to reachedMetadata
    }

    /**
     * JSON 文本预处理（1:1 iOS `preprocessJSONText` :356-384）：去零宽字符；把 JSON 字符串值内部的真实换行
     * 转义为 `\n`（LLM 常忘转义导致解析失败）。值定位见 [JSON_STRING_VALUE_REGEX]（连分隔符与引号一并匹配，
     * 回调按捕获组重建），只改写值内换行。
     */
    fun preprocessJSONText(text: String): String {
        var result = text
            .replace("\uFEFF", "") // BOM / 零宽不换行空格
            .replace("\u200B", "") // 零宽空格
            .replace("\u200C", "") // 零宽不连字
            .replace("\u200D", "") // 零宽连字

        result = JSON_STRING_VALUE_REGEX.replace(result) { match ->
            val whitespace = match.groupValues[1]
            val value = match.groupValues[2]
            val escapedValue = if (value.contains("\n") || value.contains("\r")) {
                value.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")
            } else {
                value
            }
            // 原样重建分隔符与左右引号，仅替换值体——等价于旧零宽断言版只改写「值」。
            // replace(input, transform) 的回调返回值按字面插入（不做 $N 组引用展开），故值内 `$`/`\` 安全。
            ":$whitespace\"$escapedValue\""
        }

        return result
    }

    /**
     * 生成 JSON 解码候选串（按优先级去重保序，1:1 iOS `normalizedJSONCandidates` :445-492）：
     * 原文 → 去 think → 去 ```代码围栏 → 取首尾花括号子串 → 各候选再跑 [preprocessJSONText]。
     */
    fun normalizedJSONCandidates(responseText: String): List<String> {
        val trimmed = responseText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val candidates = mutableListOf<String>()
        candidates.add(trimmed)

        val thinkStripped = stripThinkingTagsForJSON(trimmed).trim()
        if (thinkStripped.isNotEmpty() && thinkStripped != trimmed) {
            candidates.add(thinkStripped)
        }

        val bases = listOf(trimmed, thinkStripped).filter { it.isNotEmpty() }.distinct()
        val fencePrefixes = listOf("```json", "```JSON", "```")
        for (base in bases) {
            for (prefix in fencePrefixes) {
                if (base.startsWith(prefix) && base.endsWith("```")) {
                    val stripped = base.replace(prefix, "").replace("```", "").trim()
                    if (stripped.isNotEmpty()) candidates.add(stripped)
                }
            }
        }

        for (base in bases) {
            val jsonStart = base.indexOf('{')
            val jsonEnd = base.lastIndexOf('}')
            if (jsonStart in 0 until jsonEnd) {
                candidates.add(base.substring(jsonStart, jsonEnd + 1))
            }
        }

        val rawCandidates = candidates.toList()
        for (candidate in rawCandidates) {
            val preprocessed = preprocessJSONText(candidate)
            if (preprocessed != candidate) candidates.add(preprocessed)
        }

        return candidates.distinct()
    }

    /**
     * 逐候选尝试 [kotlinx] 解码为 [StoryChapterPayload]（1:1 iOS `decodePayload` :386-393）。
     * 全失败→null（iOS 抛 invalidResponse，调用方 `try?` 等价 null）。
     */
    fun decodePayload(responseText: String): StoryChapterPayload? {
        for (candidate in normalizedJSONCandidates(responseText)) {
            runCatching { json.decodeFromString<StoryChapterPayload>(candidate) }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 中文句末标点集合（1:1 iOS `sentenceEndingCharacters` :337-342）——内容以这些字符结尾即视为完整。
     * 含右双/单引号（U+201D/U+2019）与 ASCII 句末符号。
     */
    private val SENTENCE_ENDING_CHARS: Set<Char> = setOf(
        '。', '！', '？', '…', '」', '】', '）',
        '”', '’',
        '.', '!', '?', '"', '\'', ')', ']',
    )

    // ── 预编译正则（1:1 iOS +Parsing.swift 内联 NSRegularExpression） ──

    /** 闭合 think/thinking 标签（`[\s\S]` 已跨行，无需 DOT_MATCHES_ALL）。 */
    private val THINK_CLOSED_REGEX = Regex("""<think(?:ing)?>[\s\S]*?</think(?:ing)?>""")

    /** 未闭合 think/thinking 标签到串尾（输出截断时）。 */
    private val THINK_UNCLOSED_REGEX = Regex("""<think(?:ing)?>[\s\S]*$""")

    /**
     * 预览用沉浸标记 `[tag]`/`[/tag]`/`[tag:value]`（值含中英字母数字、点、CJK、间隔号·）。
     * 标签名 `\w` 取 ASCII 语义（标签名恒 ASCII），值另含显式 CJK 区间，与 iOS 等价。
     */
    private val PREVIEW_MARKUP_TAG_REGEX = Regex("""\[/?[\w_]+(?::[\w.一-鿿·]+)?]""")

    /**
     * JSON 键值对的字符串值，连同前导 `:`、空白与左右引号一并匹配——`:`、组 1=空白、`"`、组 2=值体、`"`。
     *
     * **刻意不用后顾/前瞻零宽断言**：Android 的 `java.util.regex` 由 ICU 实现，不支持变长 look-behind——
     * 旧式 `(?<=:\s*")` 因 `\s*` 不定长，在真机/模拟器编译期即抛 `PatternSyntaxException`
     * （“Look-behind pattern matches must have a bounded maximum length”），令本类静态初始化整体崩溃；
     * 而桌面 JVM 正则引擎接受变长后顾，故 JVM 单测测不出该差异（参见同类 ICU↔JVM 正则分歧的历史踩坑）。
     * 改用「消费分隔符 + 捕获组」是跨引擎一致的等价写法：[preprocessJSONText] 的替换回调据组 1/组 2
     * 原样重建 `:`、空白与左右引号，仅转义组 2 的值内换行，行为与旧零宽断言版完全一致。
     *
     * `(?:[^"\\]|\\.)*` 值体允许转义序列；DOT_MATCHES_ALL 使 `\\.` 的 `.` 可跨行吃掉「反斜杠+真实换行」。
     */
    private val JSON_STRING_VALUE_REGEX =
        Regex(""":(\s*)"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL)
}
