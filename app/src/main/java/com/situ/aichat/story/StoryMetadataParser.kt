package com.situ.aichat.story

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * 从创作模型的纯文本输出中解析故事内容和元数据（源自 iOS `Services/StoryMetadataParser.swift`；
 * 2026-07-02 起按 FABLE5_STORY_REDESIGN_PROPOSAL.md §7 加固，容错面超越 iOS）。
 *
 * 这是「三级防御」的**第一级=纯代码解析**（0 token）；二级轻量补全 / 三级完整结构化由生成服务 resolvePayload 编排（11.1e）。
 * 支持 7 种 `---METADATA---` 分隔符变体（大小写不敏感）、三级冒号、中英字段名归一、choiceA-D 合并、
 * hasChoice 智能推断、mood 归一到 11 合法值（词表单源 [StoryMoods]）。
 * 加固三处（E3/E6）：① 选项值为 JSON 数组时解开取元素（旧行为=数组字符串原样当一个选项显示给用户）；
 * ② 元数据行容忍 JSON 式写法（键值裹引号 / 行尾逗号 / 围栏行自然跳过）——更多输出留在 0 token 的一级救回；
 * ③ 无分隔符回退提取 mood 时容忍大小写与中文值。
 */
internal object StoryMetadataParser {

    /** 心情标签正则（宽容：大小写任意、值可为中文，归一交 [StoryMoods]）。 */
    private val moodTagRegex = Regex("""\[\s*mood\s*:\s*([^\]\s]{1,20})\s*\]""", RegexOption.IGNORE_CASE)

    /** 选项 JSON 数组解析（宽松模式：容忍未加引号的元素）。 */
    private val optionsJson = Json { isLenient = true }

    /** 解析产物（1:1 iOS `ParseResult` :9-42）；各字段可空，由上层 resolvePayload 填默认。 */
    data class ParseResult(
        val content: String,
        val title: String?,
        val teaser: String?,
        val mood: String?,
        val hasChoice: Boolean?,
        val choicePrompt: String?,
        val choiceOptions: List<String>?,
        val summary: String?,
        val currentArc: String?,
        val characterStates: String?,
        val openThreads: String?,
        val nextChapterBeats: String?,
        val isEnding: Boolean?,
        /**
         * 本章人物关系新进展（故事二期卷一·**可选**字段）：`[里程碑]`/`[近况]` 开头、分号分隔的 0–3 条。
         * 缺失 / 模型写「无」/ 老章 → null（「无」的归一在落库口 [StoryLedgers.normalizeMeta]，解析端只如实带回）。
         */
        val intimacyUpdates: String? = null,
        /** 章末场景状态（可选）：一行「地点｜在场人物及状态要点」。缺失=沿用旧值、「无」=清空，两分在落库口。 */
        val sceneEndState: String? = null,
        /** 本章重点场景标签（可选）：一行「场景·地点·要点」，供场景台账追加。 */
        val sceneTag: String? = null,
        /**
         * 切出来的元数据原文（图纸一 C2 第 3 层用）：分隔符路 = 分隔符之后的整段、尾部识别路 = 尾块、
         * 两路都未命中恒 null。**只喂给第三级的元数据结构化，绝不参与既有字段取值**。
         */
        val rawMetadataText: String? = null,
    ) {
        val hasRequiredFields: Boolean
            get() = content.isNotEmpty() && title != null && mood != null

        val hasQualityFields: Boolean
            get() = summary != null && hasChoice != null

        val missingQualityFieldNames: List<String>
            get() {
                val missing = mutableListOf<String>()
                if (summary == null) missing.add("summary")
                if (currentArc == null) missing.add("currentArc")
                if (characterStates == null) missing.add("characterStates")
                if (openThreads == null) missing.add("openThreads")
                if (hasChoice == null) missing.add("hasChoice")
                if (isEnding == null) missing.add("isEnding")
                return missing
            }
    }

    /**
     * @param diagnostics 可选剥离观测收集器（§7）：非 null 时 mood 归一失败处收「mood@meta」——只记标签名，值不进日志；
     *   默认 null 时行为 / 输出与不带该参完全一致。
     */
    fun parse(rawOutput: String, diagnostics: MutableList<String>? = null): ParseResult {
        val trimmed = rawOutput.trim()
        if (trimmed.isEmpty()) {
            return ParseResult(
                content = "", title = null, teaser = null, mood = null,
                hasChoice = null, choicePrompt = null, choiceOptions = null,
                summary = null, currentArc = null, characterStates = null,
                openThreads = null, nextChapterBeats = null, isEnding = null,
                // 空输入：三个亲密叙事字段同样恒 null（落库口按「缺失」处理 = 账本不追加、场景状态沿用）
                intimacyUpdates = null, sceneEndState = null, sceneTag = null,
            )
        }

        val (contentPart, metadataPart) = splitByMetadataSeparator(trimmed)
        if (metadataPart != null) {
            return parseWithMetadataBlock(contentPart, metadataPart, trimmed, diagnostics)
        }

        // 尾部元数据块识别（图纸一 C2 第 1 层）：无分隔符时最后一次抢救，命中即走与分隔符路完全相同的字段解析
        val (trailingContent, trailingMetadata) = splitByTrailingMetadataBlock(trimmed)
        if (trailingMetadata != null) {
            diagnostics?.add("trailingMeta@parse")
            return parseWithMetadataBlock(trailingContent, trailingMetadata, trimmed, diagnostics)
        }

        return ParseResult(
            content = trimmed,
            title = null,
            teaser = null,
            mood = extractFirstMoodTag(trimmed, diagnostics),
            hasChoice = null,
            choicePrompt = null,
            choiceOptions = null,
            summary = null,
            currentArc = null,
            characterStates = null,
            openThreads = null,
            nextChapterBeats = null,
            isEnding = null,
            // 无元数据块（整篇当正文）：三个亲密叙事字段无从解析，恒 null
            intimacyUpdates = null,
            sceneEndState = null,
            sceneTag = null,
        )
    }

    /**
     * 由「正文段 + 元数据段」组装 ParseResult（自 parse 抽出的共用体，**逻辑逐字未动**）：分隔符路与
     * 尾部识别路共用同一套字段解析与智能推断，避免两份实现漂移。
     *
     * @param fallbackContent 正文段 trim 后为空时的回退内容（既有行为：整篇当正文）
     */
    private fun parseWithMetadataBlock(
        contentPart: String,
        metadataPart: String,
        fallbackContent: String,
        diagnostics: MutableList<String>?,
    ): ParseResult {
        val fields = parseMetadataLines(metadataPart)
        val content = contentPart.trim()

        val choiceOptions = parseChoiceOptions(fields)
        val isEnding = parseBool(fields["isending"])
        val explicitHasChoice = parseBool(fields["haschoice"])
        // 智能推断：有选项内容时，即使 hasChoice 字段缺失也推断为 true
        val inferredHasChoice = inferHasChoice(
            explicit = explicitHasChoice,
            choiceOptions = choiceOptions,
            choicePrompt = fields["choiceprompt"],
            isEnding = isEnding,
        )

        return ParseResult(
            content = if (content.isEmpty()) fallbackContent else content,
            title = fields["title"],
            teaser = fields["teaser"],
            mood = normalizeMood(fields["mood"], diagnostics),
            hasChoice = inferredHasChoice,
            choicePrompt = fields["choiceprompt"],
            choiceOptions = choiceOptions,
            summary = fields["summary"],
            currentArc = fields["currentarc"],
            characterStates = fields["characterstates"],
            openThreads = fields["openthreads"],
            nextChapterBeats = fields["nextchapterbeats"],
            isEnding = isEnding,
            // 故事二期卷一：三个可选字段如实带回（「无」的归一在落库口，解析端不做语义判断）
            intimacyUpdates = fields["intimacyupdates"],
            sceneEndState = fields["sceneendstate"],
            sceneTag = fields["scenetag"],
            rawMetadataText = metadataPart,
        )
    }

    private fun splitByMetadataSeparator(text: String): Pair<String, String?> {
        val separatorPatterns = listOf(
            "---METADATA---",
            "---metadata---",
            "---Metadata---",
            "===METADATA===",
            "— METADATA —",
            "METADATA:",
            "METADATA",
        )

        for (separator in separatorPatterns) {
            val idx = text.indexOf(separator, ignoreCase = true)
            if (idx >= 0) {
                val content = text.substring(0, idx)
                val metadata = text.substring(idx + separator.length)
                if (metadata.trim().isNotEmpty()) {
                    return content to metadata
                }
            }
        }

        return text to null
    }

    // MARK: - 尾部元数据块识别（图纸一 C2 第 1/2 层）

    /**
     * 无分隔符时的尾部元数据块识别：**自末行向上**回溯，吸纳「空行 / 白名单字段行 / 疑似分隔符行」，遇任何
     * 其他行（正文、对话冒号行、非白名单字段行）立即终止；至少出现一个字段行才算命中，切完没正文则放弃。
     *
     * 只认 [directMap] 的英文键（图纸 §0.3-1）：中文键（「悬念」「结局」）是常用汉语词，进白名单会把
     * 「悬念：他到底是谁」这类正文末句整段切走；英文键在中文正文里的自然出现率≈0。
     *
     * @return (正文, 元数据文本)；未命中恒 (原文, null)
     */
    private fun splitByTrailingMetadataBlock(text: String): Pair<String, String?> {
        val lines = text.lines()
        var blockStart = -1
        var sawFieldLine = false
        var index = lines.lastIndex
        while (index >= 0) {
            val line = lines[index].trim()
            when {
                // 空行可吸纳，但不把块上沿推到它身上（免得把正文与元数据之间的空行算进元数据）
                line.isEmpty() -> Unit
                isWhitelistedFieldLine(line) -> {
                    blockStart = index
                    sawFieldLine = true
                }
                isMetadataSeparatorLine(line) -> blockStart = index
                else -> break
            }
            index--
        }
        if (!sawFieldLine || blockStart < 0) return text to null

        val content = lines.subList(0, blockStart).joinToString("\n").trim()
        if (content.isEmpty()) return text to null
        return content to lines.subList(blockStart, lines.size).joinToString("\n")
    }

    /**
     * 渲染层尾部残渣剥离（图纸一 C2 第 2 层）：**只在显示路径用，绝不写回 DB**——历史已落库的坏章立刻在
     * 显示层变干净，库里内容原样不动。① 分隔符命中 **且** 元数据侧首个非空行是白名单字段行 → 取正文部分；
     * ② 尾部块识别命中 → 取正文部分；③ 都不命中 → 原样返回。
     *
     * ① 比解析层多一道「首行必须是字段行」保守闸：解析层切错还有第三级 + content 兜底接着，
     * 渲染层切错则是用户当场少半章。
     */
    internal fun stripTrailingMetadata(text: String): String {
        val (contentPart, metadataPart) = splitByMetadataSeparator(text)
        if (metadataPart != null) {
            val firstMetaLine = metadataPart.lines().firstOrNull { it.isNotBlank() }?.trim()
            if (firstMetaLine != null && isWhitelistedFieldLine(firstMetaLine)) return contentPart.trim()
        }
        val (trailingContent, trailingMetadata) = splitByTrailingMetadataBlock(text)
        if (trailingMetadata != null) return trailingContent
        return text
    }

    /**
     * 白名单字段行判定：行内**首个**冒号（半角 / 全角取靠前者）之前的段，经 [cleanKey] 清洗 + lowercase 后命中
     * [directMap]（35 键**单源引用**，绝不复制成第二份常量——加字段时白名单自动跟着扩）即为真。
     * 值侧可空；无冒号 → 非字段行。
     */
    private fun isWhitelistedFieldLine(trimmedLine: String): Boolean {
        val halfIdx = trimmedLine.indexOf(':')
        val fullIdx = trimmedLine.indexOf('：')
        val colonIdx = when {
            halfIdx < 0 -> fullIdx
            fullIdx < 0 -> halfIdx
            else -> minOf(halfIdx, fullIdx)
        }
        if (colonIdx < 0) return false
        return directMap.containsKey(cleanKey(trimmedLine.substring(0, colonIdx)).lowercase())
    }

    /**
     * 疑似分隔符行判定：整行删除全部 `-`/`=`/`—`/空格/tab 后 lowercase 等于 `metadata` 或 `metadata:`
     * （覆盖 [splitByMetadataSeparator] 7 变体之外的 `--METADATA--` 类近似形态）。**整行判定，绝不匹配行中段。**
     */
    private fun isMetadataSeparatorLine(trimmedLine: String): Boolean {
        val stripped = trimmedLine
            .filterNot { it == '-' || it == '=' || it == '—' || it == ' ' || it == '\t' }
            .lowercase()
        return stripped == "metadata" || stripped == "metadata:"
    }

    private fun parseMetadataLines(metadata: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()

        for (line in metadata.lines()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            var key: String? = null
            var value: String? = null

            val colonSpaceIdx = trimmedLine.indexOf(": ")
            if (colonSpaceIdx >= 0) {
                key = trimmedLine.substring(0, colonSpaceIdx)
                value = trimmedLine.substring(colonSpaceIdx + 2)
            } else {
                val fullColonIdx = trimmedLine.indexOf("：")
                if (fullColonIdx >= 0) {
                    key = trimmedLine.substring(0, fullColonIdx)
                    value = trimmedLine.substring(fullColonIdx + 1)
                } else {
                    val colonIdx = trimmedLine.indexOf(":")
                    if (colonIdx >= 0) {
                        key = trimmedLine.substring(0, colonIdx)
                        value = trimmedLine.substring(colonIdx + 1)
                    }
                }
            }

            // JSON 式行容错（E6）：键剥引号/列表符，值剥行尾逗号+成对包裹引号——"title": "第七章", 也能在一级救回
            val rawKey = key?.let { cleanKey(it) }
            val rawValue = value?.let { cleanValue(it) }
            if (rawKey.isNullOrEmpty() || rawValue.isNullOrEmpty()) continue

            fields[normalizeFieldName(rawKey)] = rawValue
        }

        return fields
    }

    private fun normalizeFieldName(rawKey: String): String {
        val lowered = rawKey.lowercase().trim()
        directMap[lowered]?.let { return it }
        chineseMap[lowered]?.let { return it }
        return lowered
    }

    /**
     * 智能推断 hasChoice：① 明确写了→直接用 ② 有选项/选择提示→true ③ isEnding=true 且无选项→false ④ 都没有→null。
     */
    private fun inferHasChoice(
        explicit: Boolean?,
        choiceOptions: List<String>?,
        choicePrompt: String?,
        isEnding: Boolean?,
    ): Boolean? {
        if (explicit != null) return explicit
        if (choiceOptions != null && choiceOptions.isNotEmpty()) return true
        if (!choicePrompt.isNullOrEmpty()) return true
        if (isEnding == true) return false
        return null
    }

    private fun parseBool(value: String?): Boolean? {
        val v = value?.lowercase()?.trim() ?: return null
        return when (v) {
            "true", "yes", "是", "1" -> true
            "false", "no", "否", "0" -> false
            else -> null
        }
    }

    private fun parseChoiceOptions(fields: Map<String, String>): List<String>? {
        val options = mutableListOf<String>()
        fields["choiceoptions"]?.let { options += expandOptionValue(it) }
        for (key in listOf("choicea", "choiceb", "choicec", "choiced")) {
            val value = fields[key]
            if (value != null && value.isNotEmpty()) options += expandOptionValue(value)
        }
        val cleaned = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (cleaned.isEmpty()) null else cleaned.take(4)
    }

    /**
     * 选项值展开（E3）：值形如 JSON 数组（`["A","B"]`）→ 解开取元素；宽松解析失败再按分隔符切；
     * 都不成立则原值作单选项。旧行为=数组字符串整段当一个选项 → 用户在选择区看到 `["A","B"]` 生肉。
     */
    private fun expandOptionValue(raw: String): List<String> {
        val v = raw.trim()
        if (v.startsWith("[") && v.endsWith("]") && v.length > 2) {
            val jsonItems = runCatching {
                optionsJson.parseToJsonElement(v).jsonArray
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                    .filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
            // 宽松 JSON 会把「[拨通电话，假装没看见]」（全角逗号）整体吞成单元素——
            // 解出 ≥2 个才采信；单元素先让全/半角分隔符切分兜一手
            if (jsonItems.size >= 2) return jsonItems
            val parts = v.substring(1, v.length - 1)
                .split(',', '，', '、')
                .map { stripWrappingQuotes(it.trim()) }
                .filter { it.isNotEmpty() }
            if (parts.size >= 2) return parts
            if (jsonItems.size == 1) return jsonItems
        }
        return listOf(v)
    }

    private fun cleanKey(raw: String): String = raw.trim().trim('-', '*', '•', '"', '\'', '“', '”', ' ')

    private fun cleanValue(raw: String): String {
        var v = raw.trim()
        if (v.endsWith(",")) v = v.dropLast(1).trim()
        return stripWrappingQuotes(v)
    }

    /** 成对包裹引号剥离（仅 ASCII/弯双引号/单引号；「」可能是有意排版，不动）。 */
    private fun stripWrappingQuotes(v: String): String {
        if (v.length < 2) return v
        val pairs = listOf('"' to '"', '\'' to '\'', '“' to '”')
        for ((open, close) in pairs) {
            if (v.first() == open && v.last() == close) return v.substring(1, v.length - 1).trim()
        }
        return v
    }

    private fun normalizeMood(raw: String?, diagnostics: MutableList<String>? = null): String? {
        val normalized = StoryMoods.normalize(raw)
        // 观测点（§7-E4）：非空 mood 归一失败即被丢弃，记「mood@meta」（值不进日志）
        if (normalized == null && !raw.isNullOrBlank()) diagnostics?.add("mood@meta")
        return normalized
    }

    private fun extractFirstMoodTag(content: String, diagnostics: MutableList<String>? = null): String? {
        val match = moodTagRegex.find(content) ?: return null
        return normalizeMood(match.groupValues[1], diagnostics)
    }

    /** 二级轻量补全 prompt（只缺质量字段时把正文末段 + 缺失字段名发给结构化模型；1:1 iOS `buildCompletionPrompt` :319-348）。 */
    fun buildCompletionPrompt(storyEndExcerpt: String, missingFields: List<String>): String {
        val fieldDescriptions = mapOf(
            "summary" to "本章剧情摘要（50-100字）",
            "currentArc" to "当前剧情弧线描述（30-60字）",
            "characterStates" to "各角色当前心理状态和关系变化（每个在场角色一条、用分号分隔，总长不超过150字）",
            "openThreads" to "当前未解决的伏笔和悬念（用分号分隔，30-80字）",
            "nextChapterBeats" to "下一章的计划草稿（单行 2-4 句：下一章打算写什么、有无重点场景）",
            "hasChoice" to "本章结尾是否需要用户做选择（true 或 false）",
            "isEnding" to "本章是否为故事结局（true 或 false）",
        )

        val lines = mutableListOf<String>()
        lines.add("根据以下故事章节的结尾部分，补充缺失的元数据字段。")
        lines.add("")
        lines.add("## 故事结尾摘录")
        lines.add(storyEndExcerpt)
        lines.add("")
        lines.add("## 需要补充的字段")
        lines.add("请按以下格式逐行输出，每行一个字段（字段名: 值），不要输出其他内容：")
        lines.add("")
        for (field in missingFields) {
            val desc = fieldDescriptions[field]
            if (desc != null) lines.add("$field: $desc")
        }
        return lines.joinToString("\n")
    }

    // MARK: - 字段名归一化映射表

    /**
     * 英文字段名归一表（**尾部识别白名单的唯一数据源**，见 [isWhitelistedFieldLine]）。
     * `internal` 而非 private：供「五面同步」单源锁测试机器点数与四方对表（图纸 §7 T1-3），生产侧只此一处消费。
     */
    internal val directMap: Map<String, String> = mapOf(
        "title" to "title",
        "teaser" to "teaser",
        "mood" to "mood",
        "summary" to "summary",
        "currentarc" to "currentarc",
        "current_arc" to "currentarc",
        "characterstates" to "characterstates",
        "character_states" to "characterstates",
        "openthreads" to "openthreads",
        "open_threads" to "openthreads",
        "nextchapterbeats" to "nextchapterbeats",
        "next_chapter_beats" to "nextchapterbeats",
        "haschoice" to "haschoice",
        "has_choice" to "haschoice",
        "choiceprompt" to "choiceprompt",
        "choice_prompt" to "choiceprompt",
        "choicea" to "choicea",
        "choiceb" to "choiceb",
        "choicec" to "choicec",
        "choiced" to "choiced",
        "choice_a" to "choicea",
        "choice_b" to "choiceb",
        "choice_c" to "choicec",
        "choice_d" to "choiced",
        "choiceoptions" to "choiceoptions",
        "choice_options" to "choiceoptions",
        "options" to "choiceoptions",
        "isending" to "isending",
        "is_ending" to "isending",
        // 故事二期卷一（D-1 红线修订）：三个可选字段，camel 与 snake 双别名。
        "intimacyupdates" to "intimacyupdates",
        "intimacy_updates" to "intimacyupdates",
        "sceneendstate" to "sceneendstate",
        "scene_end_state" to "sceneendstate",
        "scenetag" to "scenetag",
        "scene_tag" to "scenetag",
    )

    private val chineseMap: Map<String, String> = mapOf(
        "标题" to "title",
        "副标题" to "teaser",
        "预告" to "teaser",
        "氛围" to "mood",
        "心情" to "mood",
        "摘要" to "summary",
        "剧情摘要" to "summary",
        "本章摘要" to "summary",
        "当前弧线" to "currentarc",
        "剧情弧线" to "currentarc",
        "弧线" to "currentarc",
        "角色状态" to "characterstates",
        "伏笔" to "openthreads",
        "待回收伏笔" to "openthreads",
        "悬念" to "openthreads",
        "下一章节拍" to "nextchapterbeats",
        "下章提示" to "nextchapterbeats",
        "下章方向" to "nextchapterbeats",
        "是否有选择" to "haschoice",
        "有选择" to "haschoice",
        "分支选择" to "haschoice",
        "选择提示" to "choiceprompt",
        "选项" to "choiceoptions",
        "选项a" to "choicea",
        "选项b" to "choiceb",
        "选项c" to "choicec",
        "选项d" to "choiced",
        "选项1" to "choicea",
        "选项2" to "choiceb",
        "选项3" to "choicec",
        "选项4" to "choiced",
        "是否结局" to "isending",
        "结局" to "isending",
        // 故事二期卷一：三个可选字段的中文写法
        "亲密史新增" to "intimacyupdates",
        "章末场景状态" to "sceneendstate",
        "场面标签" to "scenetag",
    )

    // mood 词表已单源化到 StoryMoods（与正文标签解析共用，E4）。
}
