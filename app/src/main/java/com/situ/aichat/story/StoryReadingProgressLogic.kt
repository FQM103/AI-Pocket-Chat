package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity

/**
 * 续读章节选择的纯逻辑（11.1h-1，1:1 iOS `StoryReadingProgressStore` 的 latestPendingChoiceChapter /
 * preferredResumeChapter :41-73）。
 *
 * 抽 internal object 便于单测反推 iOS 优先级。章节级进度的持久化（lastRead/lastOpened）在
 * [StoryReadingProgressStore]；章节内滚动偏移留 11.1i 阅读器（Compose 滚动模型与 iOS contentOffset 不同，到阅读器再定）。
 */
internal object StoryReadingProgressLogic {

    /** 最近一个「有选择且用户未选」的章节（= iOS `last { hasChoice && userChoice == nil }`）。[sortedChapters] 须按章号升序。 */
    fun latestPendingChoiceChapter(sortedChapters: List<StoryChapterEntity>): StoryChapterEntity? =
        sortedChapters.lastOrNull { it.hasChoice && it.userChoice == null }

    /**
     * 续读首选章节（原 1:1 iOS `preferredResumeChapter` :65-73）：待选择章 > 上次阅读章（按 id）> 最新章（升序列表末项），
     * **外加**「已推进则前移一章」这一档（见 [advancedFromChapterNumber]）。
     *
     * 为什么要加那一档：2026-08-05「章末选项默认关闭」拍板后新章恒 `hasChoice = false`，
     * [latestPendingChoiceChapter] 这条兜底恒 null ⇒ 只剩「上次阅读章」⇒ 用户明明看完第 2 章、给了下一章方向、
     * 第 3 章也写好了，从故事外面点进来还是回到第 2 章。阅读器**留在屏内**时有「生成完成跳最新章」兜着，
     * 退出去再进来就没人兜——这一档就是补那个缺口。
     *
     * @param advancedFromChapterNumber 用户按下推进时所在的章号（[StoryReadingProgressStore.advancedFromChapterNumber]）；
     *   只有它与上次阅读章**恰好同章**才前移，且只前移**一章**（不是直接跳最新章：追更可能已经攒了好几章，
     *   一次跳到最末等于替用户跳过中间没读的章）。打开新章后标记即自消费，故重读旧章不会被这条规则踢走。
     */
    fun preferredResumeChapter(
        sortedChapters: List<StoryChapterEntity>,
        lastReadChapterId: String?,
        advancedFromChapterNumber: Int? = null,
    ): StoryChapterEntity? {
        latestPendingChoiceChapter(sortedChapters)?.let { return it }
        if (lastReadChapterId != null) {
            val lastReadIndex = sortedChapters.indexOfFirst { it.id == lastReadChapterId }
            if (lastReadIndex >= 0) {
                val lastRead = sortedChapters[lastReadIndex]
                // 按下标取下一章（不按「章号 +1」）：重写/删章后章号可能不连续，下标才是真实的「再往后一章」。
                if (advancedFromChapterNumber != null && lastRead.chapterNumber == advancedFromChapterNumber) {
                    sortedChapters.getOrNull(lastReadIndex + 1)?.let { return it }
                }
                return lastRead
            }
        }
        return sortedChapters.lastOrNull()
    }
}
