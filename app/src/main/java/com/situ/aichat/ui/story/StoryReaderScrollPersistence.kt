package com.situ.aichat.ui.story

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// ── 阅读器章节内滚动位置持久化（恢复 / 防抖保存 / 切章 flush 三效应）──
// 从 StoryReaderScreen 抽出（只搬不改·参数与原局部变量同名，效应体字节级不变）。
// 「接近顶部不保存」阈值与 iOS 映射见 story/StoryScrollRestoreLogic；存取经 StoryReaderViewModel 委托 store。

@Composable
internal fun StoryReaderScrollPersistence(
    chapterKey: String?,
    currentChapter: StoryChapterEntity?,
    listState: LazyListState,
    viewModel: StoryReaderViewModel,
) {
    // 滚动恢复（仅首个打开的章节）/ 切章滚到顶。
    var resumedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(chapterKey) {
        val ch = currentChapter ?: return@LaunchedEffect
        if (!resumedOnce) {
            resumedOnce = true
            val pos = viewModel.loadScroll(ch.id)
            if (pos != null) {
                delay(80)
                listState.scrollToItem(pos.index, pos.offset)
            }
        } else {
            listState.scrollToItem(0, 0)
        }
    }
    // 滚动防抖保存（停 1s 落盘，crash 安全）。
    LaunchedEffect(chapterKey) {
        val ch = currentChapter ?: return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                delay(1_000)
                viewModel.saveScroll(ch.id, index, offset)
            }
    }
    // 切章/离开立刻落盘当前章滚动（flush，= iOS flushScrollOffsetForChapter）。
    DisposableEffect(chapterKey) {
        onDispose {
            chapterKey?.let { viewModel.saveScroll(it, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
        }
    }
}
