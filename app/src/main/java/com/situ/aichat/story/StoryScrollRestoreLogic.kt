package com.situ.aichat.story

/**
 * 章节内滚动位置恢复的纯阈值逻辑（11.1i，对应 iOS `StoryReadingProgressStore` 滚动偏移部分）。
 *
 * **iOS↔Compose 滚动模型映射**：iOS 用 `UIScrollView.contentOffset.y` 单一像素偏移（`scrollTo(y:)` 精确恢复）；
 * Compose 的 `LazyColumn` 没有等价的绝对像素偏移，只有 `firstVisibleItemIndex` + `firstVisibleItemScrollOffset`
 * （首个可见项的下标 + 该项内的像素偏移）。故安卓存「下标 + 项内偏移」二元组，用 `scrollToItem(index, offset)` 恢复。
 *
 * iOS 的「偏移 < 200 视为在顶部、不保存」阈值（`StoryReadingProgressStore.swift:12,90,108`）在此映射为：
 * 仍停在封面项（index==0）且项内偏移 < 200px 视为「在顶部」不保存。封面项极高（屏高×0.55），
 * 故 index>0 必然已翻过封面、值得保存。阈值单位 iOS 是 point、安卓是 px，对「接近顶部」启发式无实质影响。
 */
internal object StoryScrollRestoreLogic {
    /** 低于此项内偏移（且仍在首项）视为接近顶部，不保存（1:1 iOS minimumOffsetToSave=200）。 */
    const val MIN_OFFSET_TO_SAVE = 200

    /** 是否值得保存当前滚动位置：翻过首项，或首项内已滚过阈值。 */
    fun shouldSave(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
        firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset >= MIN_OFFSET_TO_SAVE
}

/** 章节内滚动位置：LazyColumn 首个可见项下标 + 该项内像素偏移。 */
data class StoryScrollPosition(val index: Int, val offset: Int)
