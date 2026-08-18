package com.situ.aichat.story

/**
 * 正文 → 块解析器（源自 iOS `Services/StoryContentParser.swift`；2026-07-02 起按「零生肉」原则有意超越 iOS，
 * 契约 FABLE5_STORY_REDESIGN_PROPOSAL.md §7）。
 *
 * 正则优先：先提取所有标签的位置与对应块，再把标签之间的文字作为正文块；合并相邻同样式文字块。
 * 容错面：全角括号/冒号、标签内空白换行、**大小写任意**（E1）、重复开标签当闭合、裸 `[text]` 当闭合。
 *
 * 零生肉（有意偏离 iOS 的三处，原则=用户永远不该看到原始标签）：
 * · 双开标签修复只在中段确无闭合时介入，不再误伤相邻良构块（E2）；
 * · 未知标签名（如 `[foo:bar]`）在归一化阶段整体剥离——句中野标签剥掉后句子保持连续（E5）；
 * · 已知名孤儿残片（`[/text]`、未配对 `[text:style]`）、未知样式的包裹标签剥离，内文保留为普通正文（E5）。
 *
 * **2026-08-03 格式块精简**：词表收缩为 `text|scene|chapter_end`——氛围演出类
 * （`[mood:]`/`[weather:]`/`[effect:]`/`[pause:]`）整族退役。老章里的这些标签自此落入「未知标签」桶，
 * 由 [unknownTagStripRegex] 与 [com.situ.aichat.story.StoryTextSanitizer] 剥净（**预期行为，不是回归**）。
 * METADATA 的 `mood:` 字段是另一条根，仍由 [StoryMetadataParser] + [StoryMoods] 处理，本文件零关联。
 *
 * 正则跨行语义：`[\s\S]` 始终跨行；匹配区间用 [IntRange] 半闭合换算（last+1=Swift upperBound）。
 */
internal object StoryContentParser {

    // MARK: - 正则模式（编译一次，复用）

    private const val KNOWN_TAG_NAMES = "text|scene|chapter_end"

    /** 样式文字：`[text:style]内容[/text]`（允许标签内空白，大小写任意）。 */
    private val styledTextRegex =
        Regex("""\[\s*text\s*:\s*(\w+)\s*\]([\s\S]*?)\[\s*/\s*text\s*\]""", RegexOption.IGNORE_CASE)

    /** 独立标签：`[scene:x]` / `[chapter_end]`（允许标签内空白和换行，大小写任意）。 */
    private val standaloneTagRegex =
        Regex("""\[\s*(scene|chapter_end)\s*(?::\s*([^\]]*?))?\s*\]""", RegexOption.IGNORE_CASE)

    /** 已知名孤儿残片：正常匹配收不走的 `[/text]` / 裸 `[text]` / 未配对 `[text:style]`（区间层剥离，E5）。 */
    private val orphanKnownTagRegex =
        Regex("""\[\s*/?\s*(?:$KNOWN_TAG_NAMES)\s*(?::\s*[^\]\n]{0,60})?\s*\]""", RegexOption.IGNORE_CASE)

    /**
     * 未知标签名（ASCII 名 + 可选值）＝疑似标签形态但不在已知族 → 归一化阶段整体剥离（E5）。
     * 名字须 ASCII 字母/下划线开头，中文方括号内容（如「[注]」）不受影响；
     * 已知名靠负向前瞻放行（`[:\]]` 锚防止 `texture` 被当成 `text` 前缀误放行）。
     */
    private val unknownTagStripRegex = Regex(
        """\[\s*/?\s*(?!(?:$KNOWN_TAG_NAMES)\s*[:\]])[A-Za-z_][\w-]{0,19}\s*(?::\s*[^\]\n]{0,60})?\s*\]""",
        RegexOption.IGNORE_CASE,
    )

    /** 标签内全角冒号修正。 */
    private val colonFixRegex = Regex("""\[([^\]]*?)：([^\]]*?)\]""")

    /** 修复重复开标签（LLM 用第二个开标签当闭合）；中段禁含真闭合 `[/text]`，防误伤相邻良构块（E2）。 */
    private val doubledTagFixRegex = Regex(
        """(\[\s*text\s*:\s*\w+\s*\])((?:(?!\[\s*/\s*text\s*\])[\s\S])*?)\[\s*text\s*:\s*\w+\s*\]""",
        RegexOption.IGNORE_CASE,
    )

    /** 修复裸 `[text]` 当闭合标签（同样不跨真闭合）。 */
    private val bareCloseFixRegex = Regex(
        """(\[\s*text\s*:\s*\w+\s*\])((?:(?!\[\s*/\s*text\s*\])[\s\S])*?)\[\s*text\s*\]""",
        RegexOption.IGNORE_CASE,
    )

    // MARK: - 预处理

    /** 全角括号/冒号统一为半角、未知标签剥离、修复 LLM 常见格式错误（超集自 iOS `normalize` :63-82）。 */
    private fun normalize(content: String, diagnostics: MutableList<String>? = null): String {
        var result = content
        // 全角括号 → 半角
        result = result.replace("［", "[")
        result = result.replace("］", "]")
        result = result.replace("【", "[")
        result = result.replace("】", "]")
        // 标签内全角冒号 → 半角
        result = colonFixRegex.replace(result, "[$1:$2]")
        // 未知标签名整体剥离（E5）：纯文本替换，句中野标签剥掉后句子保持连续（不产生块边界）
        // 观测点（§7）：diagnostics != null 时先 findAll 收「标签名@位置」再 replace；null 路径零额外工作、输出字节级不变。
        if (diagnostics != null) {
            for (m in unknownTagStripRegex.findAll(result)) diagnostics.add(tagLabel(m.value, m.range.first))
        }
        result = unknownTagStripRegex.replace(result, "")
        // 修复重复开标签：[text:style]...[text:style] → [text:style]...[/text]
        result = doubledTagFixRegex.replace(result, "$1$2[/text]")
        // 修复裸 [text] 当闭合：[text:style]...[text] → [text:style]...[/text]
        result = bareCloseFixRegex.replace(result, "$1$2[/text]")
        return result
    }

    // MARK: - 主解析入口

    /**
     * @param diagnostics 可选剥离观测收集器（§7）：非 null 时向其收集每处被剥离标签的「标签名@位置」——**只收名与位置，
     *   标签值与正文内容绝不进**（遵日志约定）；由调用侧 Log.i 输出。默认 null 时行为 / 输出与不带该参完全一致（字节级零变化）。
     */
    fun parse(content: String, diagnostics: MutableList<String>? = null): List<StoryContentBlock> {
        val text = normalize(content, diagnostics)

        // 第一步：收集所有标签的位置（IntRange，闭区间）和对应的 block；空块列表 = 剥离该区间
        val tagSpans = mutableListOf<Pair<IntRange, List<StoryContentBlock>>>()

        // 匹配样式文字 [text:style]...[/text]；未知样式剥掉包裹标签、内文回落普通正文（E5，偏离 iOS 的整段原样保留）
        for (match in styledTextRegex.findAll(text)) {
            val style = StoryTextStyle.fromRaw(match.groupValues[1].trim().lowercase())
            val innerText = stripResidualTags(match.groupValues[2], diagnostics)
            val blocks = when {
                style != null -> listOf(StoryContentBlock.Text(innerText, style))
                innerText.isBlank() -> emptyList()
                else -> listOf(StoryContentBlock.Text(innerText, StoryTextStyle.NORMAL))
            }
            tagSpans.add(match.range to blocks)
        }

        // 匹配独立标签 [scene:xxx] / [chapter_end]
        for (match in standaloneTagRegex.findAll(text)) {
            val tagName = match.groupValues[1].trim().lowercase()
            val value = match.groups[2]?.value?.trim() ?: ""
            val wholeRange = match.range

            // 避免和样式文字标签重叠（[text:style] 也能被独立标签正则部分匹配到）
            val overlaps = tagSpans.any { it.first overlaps wholeRange }
            if (overlaps) continue

            val block: StoryContentBlock? = when (tagName) {
                "scene" -> StoryContentBlock.SceneTransition(value)
                "chapter_end" -> StoryContentBlock.ChapterEnd
                else -> null
            }

            // 零生肉：block 为 null 也消费区间，把标签从正文剥掉（偏离 iOS 的「不登记 span」）
            tagSpans.add(wholeRange to (block?.let { listOf(it) } ?: emptyList()))
        }

        // 已知名孤儿残片（[/text] / 裸 [text] / 未配对 [text:style]）→ 剥离（E5）
        for (match in orphanKnownTagRegex.findAll(text)) {
            val wholeRange = match.range
            if (tagSpans.any { it.first overlaps wholeRange }) continue
            // 观测点（§7-E5）：记孤儿标签名@位置（含前导 /，值不进）
            diagnostics?.add(tagLabel(match.value, wholeRange.first))
            tagSpans.add(wholeRange to emptyList())
        }

        // 第二步：按位置排序
        tagSpans.sortBy { it.first.first }

        // 第三步：把标签之间的文字作为正文块
        val blocks = mutableListOf<StoryContentBlock>()
        var cursor = 0

        for (span in tagSpans) {
            val spanStart = span.first.first
            val spanEnd = span.first.last + 1 // 闭区间 last → 半开 upperBound
            // 标签和之前标签重叠时跳过（防御性）
            if (spanStart < cursor) continue

            // 标签前的文字 → 正文块
            if (cursor < spanStart) {
                val plain = text.substring(cursor, spanStart).trim()
                if (plain.isNotEmpty()) blocks.add(StoryContentBlock.Text(plain, StoryTextStyle.NORMAL))
            }

            // 标签本身
            blocks.addAll(span.second)

            // 游标前进
            cursor = spanEnd
        }

        // 最后一段文字
        if (cursor < text.length) {
            val plain = text.substring(cursor).trim()
            if (plain.isNotEmpty()) blocks.add(StoryContentBlock.Text(plain, StoryTextStyle.NORMAL))
        }

        return mergeAdjacentTextBlocks(blocks)
    }

    /** 样式块内文的残留标签剥离（块内独立标签在重叠判定下不成块，但绝不能漏显给用户，E5）。 */
    private fun stripResidualTags(inner: String, diagnostics: MutableList<String>? = null): String {
        // 观测点（§7-E5）：非 null 时先 findAll 收「标签名@位置」再 replace；null 路径与旧行为字节级一致。
        if (diagnostics != null) {
            for (m in standaloneTagRegex.findAll(inner)) diagnostics.add(tagLabel(m.value, m.range.first))
        }
        var result = standaloneTagRegex.replace(inner, "")
        if (diagnostics != null) {
            for (m in orphanKnownTagRegex.findAll(result)) diagnostics.add(tagLabel(m.value, m.range.first))
        }
        result = orphanKnownTagRegex.replace(result, "")
        return result
    }

    /**
     * 从原始标签串提取诊断标签「名字（含可选前导 /）@位置」——丢弃冒号后的值。
     * **只记标签名与位置，标签值与正文内容绝不进日志**（§7 日志约定：值可能是任意文字）。
     * 例：`[/text]`@40 → `/text@40`；`[ mood : 任意值 ]`@40 → `mood@40`；`[foo:bar]`@40 → `foo@40`。
     */
    private fun tagLabel(rawTag: String, offset: Int): String {
        val inner = rawTag.trim().removePrefix("[").removeSuffix("]")
        val name = inner.substringBefore(':').replace(whitespaceRegex, "")
        return "$name@$offset"
    }

    private val whitespaceRegex = Regex("""\s+""")

    // MARK: - 合并相邻同样式文字块

    private fun mergeAdjacentTextBlocks(blocks: List<StoryContentBlock>): List<StoryContentBlock> {
        val merged = mutableListOf<StoryContentBlock>()
        for (block in blocks) {
            val last = merged.lastOrNull()
            if (last is StoryContentBlock.Text && block is StoryContentBlock.Text && last.style == block.style) {
                merged[merged.size - 1] = StoryContentBlock.Text(last.text + "\n" + block.text, last.style)
            } else {
                merged.add(block)
            }
        }
        return merged
    }

    /** 闭区间重叠判定，等价 Swift 半开 `Range.overlaps`（换算后 first<=other.last && other.first<=last）。 */
    private infix fun IntRange.overlaps(other: IntRange): Boolean =
        this.first <= other.last && other.first <= this.last
}
