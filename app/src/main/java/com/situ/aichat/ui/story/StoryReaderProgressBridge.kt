package com.situ.aichat.ui.story

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import com.situ.aichat.story.StoryReaderRenderItem
import com.situ.aichat.story.StoryReadingProgress

/**
 * 阅读进度的 layoutInfo 取参层（视口底边模型·2026-08-03「滚到底仍 20%」修复）。
 *
 * 纯计算在 [StoryReadingProgress]；本层只回答两问：视口底边现在压在第几项的百分之几？完全越过了哪一项？
 * 无状态纯几何——[LazyListLayoutInfo] / LazyListItemInfo 均为接口，fake 即可纯 JVM 单测
 * （StoryReaderProgressBridgeTest），不吃模拟器随机性。
 */
internal object StoryReaderProgressBridge {

    /**
     * 底部胶囊百分比：视口底边扫过的项占总项数之比。
     * 滚到底时最后项完整可见（fraction 精确 = 1）→ 恒 100；布局前空列表 → 0。
     */
    fun percent(li: LazyListLayoutInfo): Int {
        val last = li.visibleItemsInfo.lastOrNull() ?: return 0
        val fraction = if (last.size <= 0) 1f else (readableBottom(li) - last.offset).toFloat() / last.size
        return StoryReadingProgress.percent(last.index, fraction, li.totalItemsCount)
    }

    /**
     * 底部胶囊剩余分钟：视口底边**完全越过**的正文块算已读，剩余块按字数估时。
     * [hasRecap] =「上回说到」回顾条是否在列——在则正文块从 index 2 起，否则 1（封面恒 0）。
     */
    fun remainingMinutes(li: LazyListLayoutInfo, hasRecap: Boolean, renderItems: List<StoryReaderRenderItem>): Int {
        val bottom = readableBottom(li)
        // 可见项无一「底边不低于视口底边」（独占整屏的超长文本块）→ 已读完的是首个可见项之前的那些。
        val lastPassed = li.visibleItemsInfo.lastOrNull { it.offset + it.size <= bottom }?.index
            ?: ((li.visibleItemsInfo.firstOrNull()?.index ?: 0) - 1)
        val bodyStart = if (hasRecap) 2 else 1
        val consumed = StoryReadingProgress.consumedBodyBlocks(lastPassed, bodyStart, renderItems.size)
        val remainingChars = renderItems.drop(consumed).sumOf { renderItemTextLength(it) }
        return StoryReadingProgress.remainingMinutes(remainingChars)
    }

    /** 可读区底边（内容坐标系）：视口末端刨掉给悬浮胶囊留的底部 contentPadding。 */
    private fun readableBottom(li: LazyListLayoutInfo): Int = li.viewportEndOffset - li.afterContentPadding

    /** 渲染项的可读字数（供剩余阅读时间估算·章末装饰无字数）。 */
    private fun renderItemTextLength(item: StoryReaderRenderItem): Int = when (val k = item.kind) {
        is StoryReaderRenderItem.Kind.Text -> k.text.length
        is StoryReaderRenderItem.Kind.Scene -> k.text.length
        StoryReaderRenderItem.Kind.ChapterEnd -> 0
    }
}
