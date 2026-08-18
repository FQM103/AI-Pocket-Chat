package com.situ.aichat.ui.story

import com.situ.aichat.story.StoryStatus

/**
 * 阅读器「章末推进区 / 建议完结卡」的显示条件（ST11·图纸 §3.4 锁定布尔式·纯函数便于 T1 全矩阵）。
 *
 * 为什么单拎出来：这三条件互相咬合（推进区要避让未答选择、建议卡要能与选择区并存），塞在 Compose 里
 * 既测不动也读不清。此处只做判断、不碰渲染。
 *
 * 三条件的并列口径（[showChoiceSection] 是既有行为的镜像，写在这里是为了三者能一眼对照）：
 * - 选择区：有选项 且（已答 或 书没完结）——ST10-4 完结门原样；
 * - 推进区：非完结末章 且 无待答选择；
 * - 建议卡：非完结末章 且 章上有 AI 的印。
 */
/** 推进区主胶囊/卡槽的三模式（态 A=NATURAL_FLOW；选项/哨兵已存=NEXT_CHAPTER；亲笔走向已存=BY_DIRECTION）。 */
internal enum class ContinueZoneMode { NATURAL_FLOW, NEXT_CHAPTER, BY_DIRECTION }

internal object StoryReaderEndgameLogic {

    /**
     * 末章推进区该摆哪一套（图纸 2026-08-06「已存走向」§3.2）。
     *
     * 治的是「走向已落库、屏上却哪儿都看不见」：末章 `userChoice` 非空时主胶囊仍叫「让故事自然发展」，
     * 与用户刚写下的走向直接说反话。三模式按「已存的是什么」分：
     * - 亲笔走向（[com.situ.aichat.story.StoryChoiceClassifier.freeformDirective] 非空）→ [ContinueZoneMode.BY_DIRECTION]；
     * - 选项点选 / 哨兵残留（userChoice 非空但不是自由输入）→ [ContinueZoneMode.NEXT_CHAPTER]；
     * - 什么都没答 → [ContinueZoneMode.NATURAL_FLOW]（态 A 现状，逐字节不变）。
     *
     * 显示门仍是 [showContinueZone]，本函数只决定「显示成什么样」，不决定「显不显示」。
     */
    fun continueZoneMode(userChoice: String?, freeformDirective: String?): ContinueZoneMode = when {
        freeformDirective != null -> ContinueZoneMode.BY_DIRECTION
        !userChoice.isNullOrBlank() -> ContinueZoneMode.NEXT_CHAPTER
        else -> ContinueZoneMode.NATURAL_FLOW
    }

    /**
     * 选择区是否渲染（**既有行为的镜像·ST10-4 完结门**，本卷不改其口径，仅在此并列写明便于与另两条对照）。
     *
     * 已完结的书不渲染未答选择：历史脏数据里的幽灵选择可点，一点会把书从已完结拉回连载中。
     */
    fun showChoiceSection(
        hasChoice: Boolean,
        userChoice: String?,
        storyStatus: String,
    ): Boolean = hasChoice && (userChoice != null || storyStatus != StoryStatus.COMPLETED)

    /**
     * 章末推进区（自由输入 + 「让故事自然发展」）是否渲染。
     *
     * `isLatestChapter && storyStatus != COMPLETED && !(hasChoice && userChoice == null)`
     *
     * - 只在**末章**出现：翻回旧章不该给方向盘（那些章后面已经有下文了）。
     * - 已完结的书没有「接下来」。
     * - **有未答选择时避让**：此刻方向盘在选择区手里，两个入口并排会让用户懵。已答 / 本就无选项 → 显示
     *   （无选项末章的一片空白正是本卷要治的断头路）。
     * - PAUSED / GENERATION_FAILED 照显——书没死就该有方向盘（推进动作会自动复活连载·E1）。
     * - 生成中不加条件：既有 GenerationOverlay 遮罩自然盖住（E3）。
     */
    fun showContinueZone(
        isLatestChapter: Boolean,
        storyStatus: String,
        hasChoice: Boolean,
        userChoice: String?,
    ): Boolean = isLatestChapter &&
        storyStatus != StoryStatus.COMPLETED &&
        !(hasChoice && userChoice == null)

    /**
     * 建议完结卡是否渲染。
     *
     * `isLatestChapter && storyStatus != COMPLETED && aiSuggestedEnding`
     *
     * - AI 自标结局只是**建议**（拍板②），等用户盖章；
     * - 与选择区**可并存**（矛盾输出场景：AI 既说完结又给选项 → 建议卡 + 选择区同屏，各自独立工作）；
     * - 不做 dismiss 存储：用户继续写出新章后旧章即非末章，卡自然不见（§3.2）。
     */
    fun showEndingSuggestCard(
        isLatestChapter: Boolean,
        storyStatus: String,
        aiSuggestedEnding: Boolean,
    ): Boolean = isLatestChapter &&
        storyStatus != StoryStatus.COMPLETED &&
        aiSuggestedEnding

    /**
     * 推进区里的金调「准备收尾」胶囊是否渲染（卷二 §4.5）。
     *
     * **不另起炉灶**：可见性完全跟随 [showContinueZone]——金胶囊与「收尾中」chip 是推进区里**同一个槽位**的
     * 两种内容，只按「有没有收尾计划」二选一。于是「已完结的书没有收尾入口」（E12）由推进区的 COMPLETED 门
     * 天然兜住，不必再写一遍。
     */
    fun showFinalePill(
        isLatestChapter: Boolean,
        storyStatus: String,
        hasChoice: Boolean,
        userChoice: String?,
        finalePlanned: Boolean,
    ): Boolean = showContinueZone(isLatestChapter, storyStatus, hasChoice, userChoice) && !finalePlanned

    /** 「收尾中 · 本弧第 K/L 章」状态 chip + 「取消收尾」是否渲染（= 金胶囊的另一面·同槽位互斥）。 */
    fun showFinaleChip(
        isLatestChapter: Boolean,
        storyStatus: String,
        hasChoice: Boolean,
        userChoice: String?,
        finalePlanned: Boolean,
    ): Boolean = showContinueZone(isLatestChapter, storyStatus, hasChoice, userChoice) && finalePlanned

    // ── 卷三 §3.3：章末「快评行 / 本章操作行」两条显示门 ──

    /**
     * 三档快评行是否渲染（卷三 §3.3）。
     *
     * `isLatestChapter && chapterExists`
     *
     * - **只在末章**：评的是「刚读完的这一章」，历史章不显示、不回填（§0.③ 有意不做）；
     * - 章不存在（生成中占位 / 空书）时没有可评对象；
     * - **已完结的书照常可评**：完结不影响「这一章好不好看」这个观感，评分只是喂给下一次生成的元数据。
     */
    fun showChapterRating(isLatestChapter: Boolean, chapterExists: Boolean): Boolean =
        isLatestChapter && chapterExists

    /**
     * 「本章操作」行（饰线 + 右端 ⋯）是否渲染（卷三 §3.3）。
     *
     * 三个动作至少有一个可用才出现——一个都不可用时整行不出（E4：不留空壳发丝线）。
     * 三个入参沿用屏侧既有算法（与 ⋮ 菜单同名实参同源），本函数不重算。
     */
    fun showChapterActions(
        canRewrite: Boolean,
        canViewPreviousDraft: Boolean,
        canEditSummary: Boolean,
    ): Boolean = canRewrite || canViewPreviousDraft || canEditSummary
}
