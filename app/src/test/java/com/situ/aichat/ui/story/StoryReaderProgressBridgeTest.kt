package com.situ.aichat.ui.story

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import com.situ.aichat.story.StoryReaderRenderItem
import com.situ.aichat.story.StoryTextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2：进度取参桥的几何用例（视口底边模型·2026-08-03「滚到底仍 20%」修复）。
 * LazyListLayoutInfo / LazyListItemInfo 均为接口 → fake 纯 JVM 构造几何，断言从底边规格独立反推。
 *
 * 公共几何：viewportEndOffset=800、底部胶囊 contentPadding=96 → 可读底边 = 704（内容坐标系）。
 * 列表 12 项 = 封面(0) + 正文 9 块(1..9) + 章末快评(10) + 选择区(11)；正文每块 400 字（= 1 分钟/块）。
 */
class StoryReaderProgressBridgeTest {

    private val body = List(9) { i ->
        StoryReaderRenderItem(id = i, kind = StoryReaderRenderItem.Kind.Text("字".repeat(400), StoryTextStyle.NORMAL), isFirstParagraph = i == 0)
    }

    @Test fun `scrolled to bottom with short end-zone items reports full and zero minutes`() {
        // 用户截图实锤场景：章末矮项堆全在屏内，最后项底边恰压可读底边（520+184=704）。
        val li = fakeLayout(
            items = listOf(item(6, -50, 100), item(7, 50, 100), item(8, 150, 100), item(9, 250, 150), item(10, 400, 120), item(11, 520, 184)),
            total = 12,
        )
        assertEquals(100, StoryReaderProgressBridge.percent(li))
        assertEquals(0, StoryReaderProgressBridge.remainingMinutes(li, hasRecap = false, renderItems = body))
    }

    @Test fun `at top only the seen sliver counts and all body remains`() {
        // 首屏：封面(600 高)完整可见，第 1 块正文露出 104/200 → percent = floor((1+0.52)/12×100) = 12。
        val li = fakeLayout(items = listOf(item(0, 0, 600), item(1, 600, 200)), total = 12)
        assertEquals(12, StoryReaderProgressBridge.percent(li))
        // 完全越过的只有封面 → 正文 0 块已读 → 9×400 字 = 9 分钟。
        assertEquals(9, StoryReaderProgressBridge.remainingMinutes(li, hasRecap = false, renderItems = body))
    }

    @Test fun `recap strip shifts body start so consumed blocks do not overcount`() {
        // 「上回说到」在列（正文从 index 2 起）：底边越过 index 2、3 → 已读正文 = 2 块，剩 7 分钟。
        val li = fakeLayout(items = listOf(item(2, 0, 500), item(3, 500, 204)), total = 13)
        assertEquals(7, StoryReaderProgressBridge.remainingMinutes(li, hasRecap = true, renderItems = body))
        // 旧写死「正文从 1 起」会多算一块（6 分钟）——此断言锁住偏移修正。
    }

    @Test fun `empty layout degrades to zero percent and full estimate`() {
        val li = fakeLayout(items = emptyList(), total = 0)
        assertEquals(0, StoryReaderProgressBridge.percent(li))
        assertEquals(9, StoryReaderProgressBridge.remainingMinutes(li, hasRecap = false, renderItems = body))
    }

    @Test fun `single oversized block spanning viewport falls back to previous items`() {
        // 超长文本块独占整屏（底边 800 > 704 未读完）：percent 按已见比例 1904/2000，
        // 已读块回退到「首个可见项之前」= index 2 → 正文已读 2 块、剩 7 分钟。
        val li = fakeLayout(items = listOf(item(3, -1200, 2000)), total = 12)
        assertEquals(32, StoryReaderProgressBridge.percent(li))
        assertEquals(7, StoryReaderProgressBridge.remainingMinutes(li, hasRecap = false, renderItems = body))
    }

    @Test fun `zero height last item counts as fully read`() {
        val li = fakeLayout(items = listOf(item(10, 500, 200), item(11, 704, 0)), total = 12)
        assertEquals(100, StoryReaderProgressBridge.percent(li))
    }

    // ── fake 几何 ──

    private fun item(index: Int, offset: Int, size: Int): LazyListItemInfo = object : LazyListItemInfo {
        override val index = index
        override val offset = offset
        override val size = size
        override val key: Any = index
        override val contentType: Any? = null
    }

    private fun fakeLayout(items: List<LazyListItemInfo>, total: Int): LazyListLayoutInfo = object : LazyListLayoutInfo {
        override val visibleItemsInfo = items
        override val totalItemsCount = total
        override val viewportStartOffset = 0
        override val viewportEndOffset = 800
        override val afterContentPadding = 96
    }
}
