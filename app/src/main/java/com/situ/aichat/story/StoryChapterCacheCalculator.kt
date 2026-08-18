package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterCacheRow

/** [StoryChapterCacheCalculator.compute] 的 5 个缓存产物（1:1 iOS `Story.cached*`）。 */
data class StoryChapterCaches(
    val count: Int,
    val latestNumber: Int?,
    val latestTitle: String?,
    val latestCreatedAt: Long?,
    val hasPendingChoice: Boolean,
)

/**
 * 故事章节缓存计算（1:1 iOS `Models/Story.swift` `refreshChapterCaches` :225-245）——纯函数，便于单测。
 *
 * - `count` 始终取当前章节列表大小（即便给了 [explicitLatest]）。
 * - `latest` = 章号最大者，**同章号取 createdAt 最晚**（iOS `.max{…}` 比较器 :232-237 = 先比 chapterNumber 再比 createdAt）。
 * - `hasPendingChoice` = `latest.hasChoice && latest.userChoice == null`。
 * - 空列表 → count=0、其余 null/false。
 *
 * [explicitLatest] 对齐 iOS `refreshChapterCaches(using:)`：重写删章后传「删除前的上一章」算 latest
 * （spec §4#4 / `+Materialization.swift:172-194`）；给定时直接用它，不从 [chapters] 推 latest。
 */
internal object StoryChapterCacheCalculator {
    fun compute(
        chapters: List<StoryChapterCacheRow>,
        explicitLatest: StoryChapterCacheRow? = null,
    ): StoryChapterCaches {
        val latest = explicitLatest ?: chapters.maxWithOrNull(
            compareBy<StoryChapterCacheRow> { it.chapterNumber }.thenBy { it.createdAt },
        )
        return StoryChapterCaches(
            count = chapters.size,
            latestNumber = latest?.chapterNumber,
            latestTitle = latest?.title,
            latestCreatedAt = latest?.createdAt,
            hasPendingChoice = latest != null && latest.hasChoice && latest.userChoice == null,
        )
    }
}
