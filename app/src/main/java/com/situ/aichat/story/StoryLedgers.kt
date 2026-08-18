package com.situ.aichat.story

/**
 * 账本族三件的纯函数（故事二期卷一·契约 `故事二期提案（内部）` §4.2–§4.4）。
 *
 * 三个账本由每章 METADATA 的三个可选字段喂养，落库编排在 [StoryChapterMaterializer]，算法全在这里：
 * - **关系史**（[appendIntimacy]）两段制：`【里程碑】`永不机器裁剪 + `【相处近况】`滚动 [RECENT_MAX_LINES] 行；
 * - **场景台账**（[appendScene]）单段滚动 [SCENE_MAX_LINES] 行；
 * - 重写/换版时按章号回滚（[rollbackChapter]），纯函数 ⇒ 重跑幂等。
 *
 * 设计取向：**宁可不裁剪，绝不误裁剪用户内容**——账本是用户可手改的资产（书页·档案可编辑），
 * 手改后格式可能不规整（丢标题行、条目没前缀），一律按「保住内容」的方向兜底（图纸 J4）。
 * 零依赖、零副作用，100% 可单测。
 */
internal object StoryLedgers {

    /** 关系史「里程碑」段标题行（永不机器裁剪的一段）。 */
    const val MILESTONE_HEADER = "【里程碑】"

    /** 关系史「相处近况」段标题行（滚动裁剪的一段）。 */
    const val RECENT_HEADER = "【相处近况】"

    /** METADATA `intimacyUpdates` 条目的里程碑前缀（模型按提示词输出）。 */
    const val MILESTONE_PREFIX = "[里程碑]"

    /** METADATA `intimacyUpdates` 条目的近况前缀；**无前缀的条目也按近况处理**（宁可多滚动，不误留）。 */
    const val RECENT_PREFIX = "[近况]"

    /** 「相处近况」段保留的最大行数（超出掐最老）。 */
    const val RECENT_MAX_LINES = 30

    /** 场景台账保留的最大行数（超出掐最老）。 */
    const val SCENE_MAX_LINES = 40

    /** 模型表示「本章没有」时输出的值（提示词逐字要求，见 `StoryFormatRules` 物料 D）。 */
    const val NONE_VALUE = "无"

    /** 条目分隔符：全角「；」与半角「;」都认（模型两种都会写）。 */
    private val ITEM_SEPARATORS = Regex("[；;]")

    /**
     * METADATA 值归一：trim 后为空、或就是 [NONE_VALUE] → null（= 本章没有这件事）。
     *
     * **解析端不做这件事**（`StoryMetadataParser` 如实带回原值），因为 `sceneEndState` 的
     * 「字段缺失 = 沿用上一章」与「显式写无 = 清空」两分正靠这个区别（图纸 J5）。
     */
    fun normalizeMeta(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == NONE_VALUE) return null
        return trimmed
    }

    /** 账本行的章号前缀（每行自带，供按章号回滚与「上一场是哪章」定位）。 */
    fun chapterLinePrefix(chapterNumber: Int): String = "第${chapterNumber}章·"

    /**
     * 关系史追加（提案 §4.2）：把本章的 `intimacyUpdates` 按 `[里程碑]`/`[近况]` 前缀分流，各追加到对应段。
     *
     * - [updates] 缺失 / 「无」/ 条目全空 → **原样返回 [existing]**（不重排用户已有的账本）；
     * - 里程碑段永不裁剪；近况段只留最后 [RECENT_MAX_LINES] 行；
     * - 解析 [existing] 时：标题行切段，**第一个标题行之前的行一律归里程碑侧**——用户手改丢了标题行时
     *   宁可全部当里程碑（永不裁剪侧）也绝不误删（图纸 J4）。
     *
     * @return 重组后的账本（两段标题恒各出现一次）；两段都没有内容时 null
     */
    fun appendIntimacy(existing: String?, updates: String?, chapterNumber: Int): String? {
        val items = normalizeMeta(updates) ?: return existing

        val prefix = chapterLinePrefix(chapterNumber)
        val newMilestones = mutableListOf<String>()
        val newRecents = mutableListOf<String>()
        for (rawItem in items.split(ITEM_SEPARATORS)) {
            val item = rawItem.trim()
            if (item.isEmpty()) continue
            val isMilestone = item.startsWith(MILESTONE_PREFIX)
            val text = item.removePrefix(MILESTONE_PREFIX).removePrefix(RECENT_PREFIX).trim()
            if (text.isEmpty()) continue
            (if (isMilestone) newMilestones else newRecents).add(prefix + text)
        }
        if (newMilestones.isEmpty() && newRecents.isEmpty()) return existing

        val (milestones, recents) = splitSections(existing)
        milestones.addAll(newMilestones)
        recents.addAll(newRecents)
        return composeSections(milestones, recents.takeLast(RECENT_MAX_LINES))
    }

    /**
     * 场景台账追加（提案 §4.4）：`sceneTag` 非「无」时追加一行「第N章·标签」，只留最后 [SCENE_MAX_LINES] 行。
     * 缺失 / 「无」→ 原样返回 [existing]。
     */
    fun appendScene(existing: String?, tag: String?, chapterNumber: Int): String? {
        val text = normalizeMeta(tag) ?: return existing
        val lines = existing.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        return (lines + (chapterLinePrefix(chapterNumber) + text))
            .takeLast(SCENE_MAX_LINES)
            .joinToString("\n")
    }

    /**
     * 按章号回滚（重写 / 换版）：删掉本章写进去的行，**标题行保留**；删空即整列归 null。
     *
     * 对两种账本通用（关系史有标题行、台账没有），故不重排格式、只做行级删除 ⇒ 重跑幂等。
     */
    fun rollbackChapter(ledger: String?, chapterNumber: Int): String? {
        if (ledger.isNullOrBlank()) return null
        val prefix = chapterLinePrefix(chapterNumber)
        val kept = ledger.lines().filterNot { it.trim().startsWith(prefix) }
        val hasContent = kept.any {
            val line = it.trim()
            line.isNotEmpty() && line != MILESTONE_HEADER && line != RECENT_HEADER
        }
        if (!hasContent) return null
        return kept.joinToString("\n").trim().ifEmpty { null }
    }

    /**
     * 台账里最新的一条（原样含「第N章·」前缀），供主节拍段尾的「别与上一场重样」提醒。
     * 空账本 → null。
     */
    fun latestSceneLine(ledger: String?): String? =
        ledger?.lines()?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }

    /**
     * 把已有账本切成（里程碑行, 近况行）两段。标题行本身不进内容；**首个标题行之前的行归里程碑侧**
     * （含「整篇没有任何标题行」的手改账本 → 全归里程碑，永不被裁剪）。
     */
    private fun splitSections(existing: String?): Pair<MutableList<String>, MutableList<String>> {
        val milestones = mutableListOf<String>()
        val recents = mutableListOf<String>()
        if (existing.isNullOrBlank()) return milestones to recents
        var target = milestones
        for (rawLine in existing.lines()) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line == MILESTONE_HEADER -> target = milestones
                line == RECENT_HEADER -> target = recents
                else -> target.add(line)
            }
        }
        return milestones to recents
    }

    /** 两段重组成账本文本：标题恒各出现一次，中间空一行；两段都空 → null。 */
    private fun composeSections(milestones: List<String>, recents: List<String>): String? {
        if (milestones.isEmpty() && recents.isEmpty()) return null
        val parts = mutableListOf<String>()
        parts.add((listOf(MILESTONE_HEADER) + milestones).joinToString("\n"))
        parts.add((listOf(RECENT_HEADER) + recents).joinToString("\n"))
        return parts.joinToString("\n\n")
    }
}
