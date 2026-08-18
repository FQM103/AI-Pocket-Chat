package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 用户章末选择的分类单源（图纸 2026-07-13「故事走向遵循与题材锚定」§2.1）：判定上一章的用户选择是
 * 「预设点选」「哨兵」还是「用户亲笔自由输入」。逻辑层单源——判定器与 LLM 面向的选择标记常量都住 story 包，
 * UI 层（[com.situ.aichat.ui.story.StoryReaderViewModel] / StoryChoiceSection）改引本对象，编译期钉一致
 * （不再各自持 private 副本，分层铁律 J8）。
 *
 * 纯函数、零副作用、不写库（J1 不加存储标记：判定 = userChoice 与解码选项/哨兵比对，老章节回溯生效）。
 */
internal object StoryChoiceClassifier {

    /** LLM 面向的「让故事自然发展」标记（值逐字迁自 StoryReaderViewModel·= iOS 硬编码·生成 prompt 读取故保持中文，不随 UI locale）。 */
    const val NATURAL_FLOW_CHOICE = "（让故事自然发展）"

    /** LLM 面向的「跳过选择，直接进入结局」标记（同上）。 */
    const val SKIP_FOR_ENDING_CHOICE = "（跳过选择，直接进入结局）"

    /** 是否两哨兵之一（「（让故事自然发展）」/「（跳过选择，直接进入结局）」）。null/其余 → false。 */
    fun isSentinel(choice: String?): Boolean =
        choice == NATURAL_FLOW_CHOICE || choice == SKIP_FOR_ENDING_CHOICE

    private val choiceOptionsJson = Json { ignoreUnknownKeys = true }

    /** 解码 choiceOptions JSON 字符串数组（失败返回空，= iOS choiceOptions(for:) 容错·逐字迁自 StoryChoiceSection）。 */
    fun decodeChoiceOptions(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { choiceOptionsJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    /**
     * 上一章的用户选择若为「自由输入」则返回其原文，否则 null。
     *
     * null 条件：latestChapter 为 null / userChoice 为 null 或 blank / userChoice == 两哨兵之一 /
     * userChoice ∈ [decodeChoiceOptions]([StoryChapterEntity.choiceOptions])。其余（含 choiceOptions 解码失败时，J2）
     * → 返回 userChoice 原文。全等匹配（`==`·不 trim 不模糊）：userChoice 落库前已 trim（StoryCustomChoiceSheet），
     * 选项文本按 METADATA 解析原样，两侧一致。
     *
     * **2026-08-05 删掉 `!hasChoice → null` 那道门**（图纸 `2026-08-05-弧线大纲导演手记重构.md` J2 缺陷修正）：
     * 关掉章末选项的书（D3）新章恒 `hasChoice = false`，用户亲笔写下的走向被这道门判成 null，三明治全套
     * （system 任务书段 + user message 强指令）静默不触发，只剩末句「上一章我选择了「X」」的弱形态。
     * 判定完全由「非空 + 非哨兵 + 不在选项列表」承担即可：关选项书 `choiceOptions` 为空 → 解码空列表 →
     * 非哨兵输入必判 freeform；结局章/追更未选 `userChoice` 为 null → 照旧 null。
     */
    fun freeformDirective(latestChapter: StoryChapterEntity?): String? {
        val chapter = latestChapter ?: return null
        val choice = chapter.userChoice
        if (choice.isNullOrBlank()) return null
        if (choice == NATURAL_FLOW_CHOICE || choice == SKIP_FOR_ENDING_CHOICE) return null
        if (choice in decodeChoiceOptions(chapter.choiceOptions)) return null
        return choice
    }

    /**
     * **方向账本**（图纸 `2026-08-05-弧线大纲导演手记重构.md` §3.1）：当前弧内（[arcStartChapter] 起）用户亲笔
     * 写过的走向清单，按章号升序，一条一行。让模型看见「用户这一路要往哪走」的历史，而不只是最新一条。
     *
     * @param chapters 传 `StoryDao.getChapterMetas` 的轻投影（含 userChoice/choiceOptions，正文位是占位空串）——
     *   **禁传整本正文**（`getChapters` 会把全书拉进内存）。
     * @param arcStartChapter 本弧起始章号；null 视为 1（老书/首弧）。弧起点之前的走向由换弧时的简史吸收，不入账本。
     * @param excludeChapterNumber 排除的章号（= 最新一章，它有三明治专座，不重复登记）。
     * @return 账本多行文本；**无条目返回 null**（= prompt 整段零注入）。条数不设上限：弧长 ≤15 天然有界。
     */
    fun buildDirectiveLedger(
        chapters: List<StoryChapterEntity>,
        arcStartChapter: Int?,
        excludeChapterNumber: Int?,
    ): String? {
        val start = arcStartChapter ?: 1
        val entries = chapters
            .filter { it.chapterNumber >= start && it.chapterNumber != excludeChapterNumber }
            .mapNotNull { chapter -> freeformDirective(chapter)?.let { chapter.chapterNumber to it } }
        if (entries.isEmpty()) return null
        return entries.joinToString("\n") { (number, text) -> "- 第${number}章时指定：「$text」" }
    }

    /**
     * 上一章的用户选择若为**预设点选**则返回选项原文，否则 null——供末句要点复述
     * （[buildCreationUserMessage]）重申方向用。是 [freeformDirective] 的补集：同一个 userChoice
     * 至多命中两者之一，故末句绝不会既给最高优先级走向、又重复一遍「我选择了…」。
     *
     * 额外挡掉三种不值得复述的情形：没有选择节点 / 空选择 / 两个哨兵值（顺其自然、跳过收尾）——
     * 哨兵不是方向，复述成「我选择了「（让故事自然发展）」」只会占注意力不给信息。
     * choiceOptions 解码失败时返回 null（该情形归 [freeformDirective] 处理，J2 口径）。
     */
    fun presetChoiceForRecap(latestChapter: StoryChapterEntity?): String? {
        val chapter = latestChapter ?: return null
        if (!chapter.hasChoice) return null
        val choice = chapter.userChoice
        if (choice.isNullOrBlank()) return null
        if (choice == NATURAL_FLOW_CHOICE || choice == SKIP_FOR_ENDING_CHOICE) return null
        return choice.takeIf { it in decodeChoiceOptions(chapter.choiceOptions) }
    }
}
