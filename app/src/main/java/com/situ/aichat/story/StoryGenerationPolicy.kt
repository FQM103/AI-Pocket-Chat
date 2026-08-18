package com.situ.aichat.story

import java.time.Instant
import java.time.ZoneId

/**
 * `StoryGenerationService` 的纯决策/派生逻辑（无 LLM/DB，100% 可单测）：把服务里与剧情/状态判定相关的策略
 * 抽出来单独测，编排只剩薄薄一层。
 *
 * - 11.1e-3 大纲生成触发决策 [decideOutlineAction]。
 * - 11.1e-5 materialize 落库的纯逻辑：章节状态机 [decideStatus]（completed/waitingChoice/serializing）、
 *   追更解锁时间 [computeUnlockAt]、故事圣经追加 [buildBibleAppendix] 与重写回滚 [rollbackBible]。
 *   （DB 落库编排留在 [StoryGenerationService.materializeChapter]/[StoryGenerationService.prepareRewrite] 薄层。）
 *
 * **2026-07-26 卷二·单模式化**：有限连载模式（30/60/100/自定义章数）已按用户拍板整体退役——满章自动完结、
 * 自动扩展 +10 章 ≤3 次、里程碑大纲、四段节奏曲线一并删除。全库只剩**无限连载**一种模式，收尾由终章弧承担
 * （见 [StoryArcPlanning]）。`maxChapters`/`autoExtendCount` 两列保留但恒 null/0（J3），别再给它们写值。
 */
internal object StoryGenerationPolicy {

    /** 大纲生成动作（[decideOutlineAction] 的结果）。 */
    sealed interface OutlineAction {
        /** 首次生成（无大纲）：起第一条剧情弧线。 */
        data object GenerateInitialArc : OutlineAction

        /** 当前弧线已够长，续接新弧线。 */
        data object GenerateNewArc : OutlineAction

        /**
         * 生成**终章弧**大纲（卷二 J1）：用户已定下「从容收尾」计划
         * （[com.situ.aichat.data.local.entity.StoryEntity.finaleEndingType] 非 null）时取代上面两支。
         */
        data object GenerateFinaleArc : OutlineAction

        /** 无需生成。 */
        data object None : OutlineAction
    }

    /**
     * 决定是否/如何生成大纲（无限单模式·纯函数，不含 LLM/持久化副作用）。
     *
     * - 无大纲 → [OutlineAction.GenerateInitialArc]
     * - 有大纲 + 本弧已写满有效弧长 → [OutlineAction.GenerateNewArc]
     * - 否则 → [OutlineAction.None]
     * - **[finalePlanned] 为真时上面两支各自换成 [OutlineAction.GenerateFinaleArc]**，且弧长按终章弧区间钳位。
     *
     * **卷二 B1 变速箱**：有效弧长由弧线自己申报——大纲首行的
     * 「[StoryArcPlanning.ARC_PLANNED_LENGTH_PREFIX]N」经 [StoryArcPlanning.parseArcPlannedLength] 解析后传入
     * [plannedLength]，此处按 [StoryArcPlanning.effectiveArcLength] 钳位/回退。该长则长、该短则短，
     * 取代原来写死的 12 章换挡（回退值仍是 12，故解析失败 = 退回旧节律，非坏数据）。
     *
     * **卷二 J1「终章弧激活」判据免加列**：定收尾计划那一刻同时把 storyOutline 置 null
     * （[com.situ.aichat.data.local.dao.StoryDao.updateFinalePlanStartingNewArc]），于是下一次判定必然是
     * 「大纲空 + finalePlanned」→ 生成终章弧大纲。自此「终章弧进行中」== finaleEndingType != null，单一真理源。
     *
     * （原「有限模式里程碑大纲 / 自动扩展后补续篇弧线」两条分支随有限模式整体退役删除。）
     *
     * @param plannedLength 本弧自报章数（null = 大纲里没有/解析失败 → 回退 [StoryArcPlanning.ARC_LENGTH_FALLBACK]）
     * @param finalePlanned 是否已定下「从容收尾」计划
     */
    fun decideOutlineAction(
        storyOutline: String?,
        currentArcStartChapter: Int?,
        chapterNumber: Int,
        plannedLength: Int? = null,
        finalePlanned: Boolean = false,
    ): OutlineAction {
        if (storyOutline.isNullOrEmpty()) {
            return if (finalePlanned) OutlineAction.GenerateFinaleArc else OutlineAction.GenerateInitialArc
        }
        val arcLength = StoryArcPlanning.effectiveArcLength(plannedLength, isFinale = finalePlanned)
        if (StoryArcPlanning.arcIndex(currentArcStartChapter, chapterNumber) > arcLength) {
            return if (finalePlanned) OutlineAction.GenerateFinaleArc else OutlineAction.GenerateNewArc
        }
        return OutlineAction.None
    }

    // MARK: - 章节状态机（materialize 落库）

    /**
     * 落库一章后故事状态的决策结果（[decideStatus] 的产物）。
     *
     * @param status 新状态 raw（[StoryStatus]）
     * @param clearRequestedEnding 是否清空一次性结局请求字段（requestedEndingType/Detail）
     */
    data class StatusDecision(
        val status: String,
        val clearRequestedEnding: Boolean,
    )

    /**
     * 落库一章后决定故事状态（纯函数）。优先级：
     * 1. 用户请求结局（requestedEndingType≠null）→ completed 且清空结局请求字段；
     * 2. 本章有选择 → waitingChoice；
     * 3. 否则 → serializing。
     *
     * **ST11 拍板②（完结权归用户）**：原「LLM 标记结局（isEnding==true）→ completed」分支已整条删除——
     * AI 说「写完了」只算建议，不再决定故事状态。isEnding 改为只落章列
     * [com.situ.aichat.data.local.entity.StoryChapterEntity.aiSuggestedEnding]，由阅读器末章「建议完结卡」
     * 请用户盖章（[com.situ.aichat.story.StoryArchiver]）。故本函数**不接收 isEnding**：AI 标结局 + 无选项 →
     * serializing（书照常连载）；AI 标结局 + 有选项（矛盾输出）→ waitingChoice（选项保留）。
     *
     * **卷二·单模式化**：原优先级 2「到达连载上限 → 自动扩展 ≤3 次、超限 completed」随有限模式整体退役删除
     * （用户拍板①，同时作废 ST11 拍板④「满章自动完结」）。故本函数也不再接收 chapterNumber/maxChapters/
     * autoExtendCount——它们在删掉满章分支后失去全部消费，留着就是死参（J9）。**完结只剩两条路**：
     * 用户请求结局（含终章弧末章转正）与 [StoryArchiver] 归档。
     */
    fun decideStatus(
        requestedEndingType: String?,
        hasChoice: Boolean,
    ): StatusDecision {
        if (requestedEndingType != null) {
            return StatusDecision(StoryStatus.COMPLETED, clearRequestedEnding = true)
        }
        val status = if (hasChoice) StoryStatus.WAITING_CHOICE else StoryStatus.SERIALIZING
        return StatusDecision(status, clearRequestedEnding = false)
    }

    // MARK: - 追更解锁时间（1:1 iOS `materializeChapter` chase 分支 +Materialization:33-45）

    /**
     * 计算追更模式章节解锁时间（1:1 iOS chase 分支 :33-45，纯函数）：取「今天 [unlockHour]:[unlockMinute]:00」，
     * now 早于该时刻 → 该时刻，否则 → +1 天（按日历日加，跨 DST 保持本地时分，等价 iOS `Calendar.date(byAdding:.day)`）。
     *
     * @param nowMillis 注入的当前时刻（毫秒），不在内部取 now，保纯可测
     * @param zone 时区，默认设备本地（= iOS `Calendar.current`）；测试可注入固定时区
     * @return 解锁时刻毫秒；时分非法等极端情况 → null（unlockAt 保持立即可读，等价 iOS `if let` 失败）
     */
    fun computeUnlockAt(
        nowMillis: Long,
        unlockHour: Int,
        unlockMinute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = runCatching {
        val now = Instant.ofEpochMilli(nowMillis)
        val unlockToday = now.atZone(zone).toLocalDate().atTime(unlockHour, unlockMinute).atZone(zone)
        val unlockTime = if (now.isBefore(unlockToday.toInstant())) unlockToday else unlockToday.plusDays(1)
        unlockTime.toInstant().toEpochMilli()
    }.getOrNull()

    // MARK: - 故事圣经追加 / 回滚（1:1 iOS +Materialization:73-83 / :159-169）

    /**
     * 构造本章追加到故事圣经的文本（1:1 iOS `materializeChapter` :73-79，纯函数）：
     * characterStates 非空 → 「第N章角色：…\n」，openThreads 非空 → 「第N章伏笔：…\n」；两者皆空 → 空串。
     * 调用方在非空时拼到既有圣经尾部。
     */
    fun buildBibleAppendix(chapterNumber: Int, characterStates: String?, openThreads: String?): String {
        val sb = StringBuilder()
        if (!characterStates.isNullOrEmpty()) sb.append("第${chapterNumber}章角色：$characterStates\n")
        if (!openThreads.isNullOrEmpty()) sb.append("第${chapterNumber}章伏笔：$openThreads\n")
        return sb.toString()
    }

    /**
     * 重写最新章时回滚故事圣经里本章追加的内容（1:1 iOS `prepareRewrite` :159-169，纯函数）：
     * 删除以「第N章角色：」/「第N章伏笔：」开头的行 → 余行以 \n 重拼 → trim → 空则 null。
     * 入参 null（无圣经）→ 原样 null（不触碰，等价 iOS `if let bible`）。
     */
    fun rollbackBible(bible: String?, chapterNumber: Int): String? {
        if (bible == null) return null
        val rolePrefix = "第${chapterNumber}章角色："
        val threadPrefix = "第${chapterNumber}章伏笔："
        val cleaned = bible.split("\n")
            .filter { !it.startsWith(rolePrefix) && !it.startsWith(threadPrefix) }
            .joinToString("\n")
            .trim()
        return cleaned.ifEmpty { null }
    }

    // MARK: - 主编排派生逻辑（11.1e-8）

    /** 结局章字数加大 1.5 倍：请求结局时 ×1.5（向零截断），否则原值。 */
    fun effectiveChapterLength(chapterLengthPreference: Int, requestedEndingType: String?): Int =
        if (requestedEndingType != null) (chapterLengthPreference * 1.5).toInt() else chapterLengthPreference

    /**
     * 手动续写故事的新状态（纯函数）：
     * - completed → serializing（开启续篇）。
     * - paused → serializing。
     * - 其它状态 → 不变（调用方仍会刷新 updatedAt 并清 cachedHasPendingChoice）。
     *
     * **卷二·单模式化**：原「已到 maxChapters 则 +10 章、并重置 autoExtendCount 给新一轮扩展机会」的扩容分支
     * 随有限模式退役删除；随之 ContinueDecision 里的 maxChapters/autoExtendCount 两字段变成纯透传死字段
     * （同 J9 口径）→ 返回值收敛成新状态一项，调用方对那两列传等值重写。
     */
    fun decideContinue(status: String): String = when (status) {
        StoryStatus.COMPLETED -> StoryStatus.SERIALIZING
        StoryStatus.PAUSED -> StoryStatus.SERIALIZING
        else -> status
    }
}
