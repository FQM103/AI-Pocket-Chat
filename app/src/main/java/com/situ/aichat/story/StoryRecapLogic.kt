package com.situ.aichat.story

/**
 * 「上回说到」回访前情条的纯判定（卷三 C3·图纸 §3 数据流 + §5 E4–E7）。
 *
 * 语义：隔了一阵（≥ [RECAP_THRESHOLD_MS]）再回到阅读器时，在正文之前展开上一章的既有梗概，
 * 帮读者接回剧情。**零新生成**——只读 `StoryChapterEntity.chapterSummary`，绝不为它调 LLM。
 *
 * 判定取「进入阅读器那一刻」的快照（图纸 §5 E7）：进屏后停留跨过阈值不追弹。
 */
internal object StoryRecapLogic {

    /** 触发回访前情条的最短离开时长 = 12 小时（覆盖「昨晚读、今晚回」这一最高频回访节奏）。 */
    const val RECAP_THRESHOLD_MS = 43_200_000L

    /**
     * 本次进入阅读器是否该展开「上回说到」。
     *
     * @param chapterNumber 当前在读章号；首章（≤1）没有上一章 → 恒 false
     * @param lastReadAtMillis 上次阅读时刻；null（老用户首次/从未记过）→ false（本次写入、下次生效）
     * @param nowMillis 进入阅读器那一刻
     * @param previousSummaryBlank 上一章摘要是否空白；空 → false（没内容可说）
     */
    fun showRecap(
        chapterNumber: Int,
        lastReadAtMillis: Long?,
        nowMillis: Long,
        previousSummaryBlank: Boolean,
    ): Boolean {
        if (chapterNumber <= 1) return false
        if (previousSummaryBlank) return false
        val lastReadAt = lastReadAtMillis ?: return false
        return nowMillis - lastReadAt >= RECAP_THRESHOLD_MS
    }
}
