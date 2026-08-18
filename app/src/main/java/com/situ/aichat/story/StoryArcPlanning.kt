package com.situ.aichat.story

/**
 * 无限连载的「弧线变速箱」纯逻辑（无 LLM/DB，100% 可单测）——2026-07-26 卷二 B1–B4。
 *
 * 三件事：
 * 1. **自报章数**（B1）：弧线大纲首行输出「本弧预计章数：N」，[parseArcPlannedLength] 取回 N，
 *    [effectiveArcLength] 钳位/回退。该长则长、该短则短，取代原来写死的 12 章换挡。
 * 2. **弧线简史**（B2）：每写完一条弧记一行「第X–Y章·主题」（[appendArcHistoryLine]），注入下一条弧的大纲
 *    prompt，防连载久了反复写同一类冲突。上限 [ARC_HISTORY_MAX_LINES] 行，超出掐最老。
 *    卷三 C4 起该行**多一个读者**：[arcSections] 把它解析回章号区间给章节列表分组——**写读同源**，
 *    行格式的四个组件（`ARC_HISTORY_RANGE_*` / [ARC_HISTORY_THEME_SEPARATOR]）两端共用常量。
 * 3. **终章弧倒数**（J1）：[finaleCountdown] 判「本章是不是收尾弧的最后一章」，是则由生成服务把预约的收尾计划
 *    转正成正式结局请求，其后完全复用既有结局章管线。
 *
 * ⚠️ **强耦合对（图纸 §6 登记）**：[ARC_PLANNED_LENGTH_PREFIX] 同时被
 * [buildArcOutlinePrompt] 的输出指令与 [parseArcPlannedLength] 的正则消费——**改一侧必须改另一侧**，
 * 两侧共用本常量 + `StoryArcPlanningTest` 的单源锁测试钉住同步。破坏该耦合的最坏后果是解析恒失败 →
 * 退回固定 [ARC_LENGTH_FALLBACK] 章换挡（非静默坏数据）。
 */
internal object StoryArcPlanning {

    /** 弧线自报章数的行首标记（**单源**：prompt 输出指令与解析正则共用，见类 KDoc 的强耦合说明）。 */
    const val ARC_PLANNED_LENGTH_PREFIX = "本弧预计章数："

    /** 普通弧线长度下限（自报值低于此值按此钳位）。 */
    const val ARC_LENGTH_MIN = 8

    /** 普通弧线长度上限。 */
    const val ARC_LENGTH_MAX = 15

    /** 普通弧线长度回退值（大纲里没有自报行 / 解析失败时用；= 卷二之前写死的换挡间隔）。 */
    const val ARC_LENGTH_FALLBACK = 12

    /** 终章弧长度下限。 */
    const val FINALE_LENGTH_MIN = 3

    /** 终章弧长度上限。 */
    const val FINALE_LENGTH_MAX = 5

    /** 终章弧长度回退值。 */
    const val FINALE_LENGTH_FALLBACK = 4

    /** 弧线简史保留的最大行数（≈240 章的历史，超出掐最老，防无限膨胀撑爆 prompt）。 */
    const val ARC_HISTORY_MAX_LINES = 20

    // MARK: - 弧线简史行格式（**单源**：[appendArcHistoryLine] 拼装与 [arcSections] 解析共用这四个组件·卷三 J6）
    //  行长这样：第1–8章·归乡与和解 ／ 无主题时只留区间：第1–8章
    //  两端由 `StoryArcPlanningTest` 的单源锁测试钉住（append 的任意产物必能被 arcSections 逐字读回）。

    /** 简史行区间的行首标记。 */
    const val ARC_HISTORY_RANGE_PREFIX = "第"

    /** 简史行区间的起止分隔符（U+2013 en dash，非 ASCII 连字符）。 */
    const val ARC_HISTORY_RANGE_DASH = "–"

    /** 简史行区间的结尾量词。 */
    const val ARC_HISTORY_RANGE_SUFFIX = "章"

    /** 简史行「区间 · 主题」的分隔符（主题自身可能含同一符号，故解析只切第一处）。 */
    const val ARC_HISTORY_THEME_SEPARATOR = "·"

    /** 旧弧线主题在大纲里的行首标记（[buildArcOutlinePrompt] 的「弧线结构」块要求 LLM 输出这一行）。 */
    const val ARC_THEME_PREFIX = "弧线主题："

    /** 主题回退到 `currentArc` 时的截取长度（J5）。 */
    const val ARC_THEME_FALLBACK_CHARS = 40

    /** 弧末收束令（普通弧·距本弧末 ≤1 章时追加在进度行之后）。 */
    const val ARC_WRAP_UP_DIRECTIVE =
        "本弧接近收束：请把本弧的主线冲突收拢出一个阶段性落点，并为下一段剧情留一个钩子；不要在本弧结尾开新的大冲突。"

    /** 全书收束令（终章弧·每章恒追加在进度行之后）。 */
    const val FINALE_WRAP_UP_DIRECTIVE =
        "全书收束期：本章继续回收伏笔、沉淀感情线，靠近大结局一步；不开新冲突、不引入新重要角色。"

    /** 自报章数行的匹配正则（前缀 + 可选 ASCII 空白 + 带号整数）。取**第一个**匹配。 */
    private val plannedLengthRegex = Regex("$ARC_PLANNED_LENGTH_PREFIX\\s*(-?\\d+)")

    /**
     * 从弧线大纲文本里取出自报章数 N（B1）。
     *
     * @return 原始 N（**不钳位**——普通弧与终章弧的合法区间不同，钳位交 [effectiveArcLength]）；
     *   没有该行 / 数字畸形 / 溢出 Int → null（调用方按回退值处理）。
     */
    fun parseArcPlannedLength(outline: String?): Int? {
        if (outline.isNullOrEmpty()) return null
        val raw = plannedLengthRegex.find(outline)?.groupValues?.getOrNull(1) ?: return null
        return raw.toIntOrNull()
    }

    /**
     * 自报章数 → 有效弧长：按弧种钳位，null（未自报/解析失败）→ 回退值。
     *
     * @param isFinale true = 终章弧（[FINALE_LENGTH_MIN]..[FINALE_LENGTH_MAX]，回退 [FINALE_LENGTH_FALLBACK]）；
     *   false = 普通弧（[ARC_LENGTH_MIN]..[ARC_LENGTH_MAX]，回退 [ARC_LENGTH_FALLBACK]）。
     */
    fun effectiveArcLength(plannedLength: Int?, isFinale: Boolean = false): Int {
        val fallback = if (isFinale) FINALE_LENGTH_FALLBACK else ARC_LENGTH_FALLBACK
        val min = if (isFinale) FINALE_LENGTH_MIN else ARC_LENGTH_MIN
        val max = if (isFinale) FINALE_LENGTH_MAX else ARC_LENGTH_MAX
        return (plannedLength ?: fallback).coerceIn(min, max)
    }

    /** 本章在当前弧内是第几章（1 起）。arcStart 为 null（老数据/首弧）时视作第 1 章起。 */
    fun arcIndex(arcStart: Int?, chapterNumber: Int): Int = chapterNumber - (arcStart ?: 1) + 1

    /** [finaleCountdown] 的结果：终章弧还在走 / 本章就是全书最后一章。 */
    enum class FinaleCountdown { RUNNING, LAST }

    /**
     * 终章弧倒数（J1）：本章是不是收尾弧的**最后一章**（= 该转正成正式结局请求的那一章）。
     *
     * @param arcStart 终章弧起始章（由 ensureOutline 写入）；null = 弧还没真正起来 → 恒 [FinaleCountdown.RUNNING]
     *   （防大纲生成失败时拿着上一条普通弧的起点误判成末章，把「从容收尾」缩成一章）
     * @param plannedLength 终章弧自报章数（钳位到 3..5，null → 回退 4）
     */
    fun finaleCountdown(arcStart: Int?, plannedLength: Int?, chapterNumber: Int): FinaleCountdown {
        if (arcStart == null) return FinaleCountdown.RUNNING
        val length = effectiveArcLength(plannedLength, isFinale = true)
        return if (arcIndex(arcStart, chapterNumber) >= length) FinaleCountdown.LAST else FinaleCountdown.RUNNING
    }

    /**
     * 给刚写完的那条弧追加一行简史（B2），返回新的 arcHistory 文本。
     *
     * 行格式 `第X–Y章·主题`；主题三级回退（J5/E11）：旧大纲的「[ARC_THEME_PREFIX]」行 →
     * 旧 [previousArcSummary]（= `story.currentArc`）前 [ARC_THEME_FALLBACK_CHARS] 字 → 只留章号区间。
     * 超过 [ARC_HISTORY_MAX_LINES] 行时掐最老的。
     *
     * @param arcStart 刚结束那条弧的起始章（null → 视作第 1 章）
     * @param arcEnd 刚结束那条弧的最后一章（通常 = 即将生成的章号 − 1）
     */
    fun appendArcHistoryLine(
        existingHistory: String?,
        previousOutline: String?,
        previousArcSummary: String?,
        arcStart: Int?,
        arcEnd: Int,
    ): String {
        val start = arcStart ?: 1
        val range = "$ARC_HISTORY_RANGE_PREFIX$start$ARC_HISTORY_RANGE_DASH$arcEnd$ARC_HISTORY_RANGE_SUFFIX"
        val theme = extractArcTheme(previousOutline)
            ?: previousArcSummary?.trim()?.takeIf { it.isNotEmpty() }?.take(ARC_THEME_FALLBACK_CHARS)
        val line = if (theme.isNullOrEmpty()) range else "$range$ARC_HISTORY_THEME_SEPARATOR$theme"
        val lines = existingHistory?.lineSequence()?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        lines.add(line)
        return lines.takeLast(ARC_HISTORY_MAX_LINES).joinToString("\n")
    }

    // MARK: - 弧线简史的读端（卷三 C4·章节列表按弧分组）

    /**
     * 章节列表的一段弧（卷三 C4）。**纯展示数据，不做对账**——区间忠实取自简史行，
     * 与实际章号错峰（重写/取消收尾后的罕见情形）时照原样呈现（图纸 §5 E9）。
     *
     * @param endInclusive 该弧末章；null = [ongoing] 的进行中弧（还没写完，无末章）
     * @param theme 弧主题；null = 简史行只有区间没有主题（文案回退交 UI 层，逻辑层不产用户可见字面量）
     */
    data class ArcSection(
        val start: Int,
        val endInclusive: Int?,
        val theme: String?,
        val ongoing: Boolean,
    )

    /**
     * 把弧线简史 + 进行中弧解析成分组区间（卷三 C4），按章号**升序**返回（列表最新在上，渲染方自行倒序）。
     *
     * - 历史行逐行解析，畸形行**跳过不崩**（[parseArcHistoryLine]）。
     * - [currentArcStartChapter] 非 null 且已经写出章节（`≤ latestChapterNumber`）时，末尾追加一条进行中弧；
     *   起点还没有任何章节时不追加，避免渲染出一个底下空无一物的分组头。
     * - 简史空 + 无进行中弧 → 空列表（章节列表与分组前完全一致，图纸 §2.2 B5）。
     *
     * @param currentArcTheme 进行中弧的主题来源（= `story.currentArc`），取首个非空行前
     *   [ARC_THEME_FALLBACK_CHARS] 字；全空 → null
     */
    fun arcSections(
        arcHistory: String?,
        currentArcStartChapter: Int?,
        currentArcTheme: String?,
        latestChapterNumber: Int,
    ): List<ArcSection> {
        val sections = arcHistory
            ?.lineSequence()
            ?.mapNotNull { parseArcHistoryLine(it) }
            ?.toMutableList()
            ?: mutableListOf()
        if (currentArcStartChapter != null && currentArcStartChapter <= latestChapterNumber) {
            val theme = currentArcTheme
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(ARC_THEME_FALLBACK_CHARS)
                ?.ifEmpty { null }
            sections.add(ArcSection(currentArcStartChapter, endInclusive = null, theme = theme, ongoing = true))
        }
        return sections
    }

    /**
     * 解析一行弧线简史（[appendArcHistoryLine] 的产物），畸形 → null。
     *
     * 主题内可能含 [ARC_HISTORY_THEME_SEPARATOR]（如「重逢·雨夜」），故只切**第一处**（`limit = 2`）。
     */
    fun parseArcHistoryLine(line: String): ArcSection? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(ARC_HISTORY_RANGE_PREFIX)) return null
        val parts = trimmed.removePrefix(ARC_HISTORY_RANGE_PREFIX)
            .split(ARC_HISTORY_THEME_SEPARATOR, limit = 2)
        val rangePart = parts[0]
        if (!rangePart.endsWith(ARC_HISTORY_RANGE_SUFFIX)) return null
        val bounds = rangePart.removeSuffix(ARC_HISTORY_RANGE_SUFFIX).split(ARC_HISTORY_RANGE_DASH)
        if (bounds.size != 2) return null
        val start = bounds[0].toIntOrNull() ?: return null
        val end = bounds[1].toIntOrNull() ?: return null
        val theme = parts.getOrNull(1)?.trim()?.ifEmpty { null }
        return ArcSection(start, endInclusive = end, theme = theme, ongoing = false)
    }

    /** 主题标签（不含冒号）：模型常把冒号包进加粗里（`**弧线主题**：`），故按标签而非「标签+冒号」定位。 */
    private val arcThemeLabel = ARC_THEME_PREFIX.trimEnd('：')

    /**
     * 从旧大纲里抽「弧线主题：」那一行的内容；抽不到 → null。
     *
     * 模型排版千奇百怪：`弧线主题：X` / `**弧线主题：** X` / `- **弧线主题**：X`——一律先按标签定位，
     * 再把残留的星号、冒号、空白从首尾剥干净。剥完为空 → null（走下一级回退）。
     */
    private fun extractArcTheme(outline: String?): String? {
        if (outline.isNullOrEmpty()) return null
        val line = outline.lineSequence().firstOrNull { it.contains(arcThemeLabel) } ?: return null
        return line.substringAfter(arcThemeLabel)
            .trim()
            .trim('*', '：', ':', ' ', '　')
            .trim()
            .ifEmpty { null }
    }
}
