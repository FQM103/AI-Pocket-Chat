package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryReaderRenderItem.make` 测试，反推 iOS `StoryReaderRenderItem.make`
 * （`StoryReaderAnimatedBlocks.swift:19-75`）：首个 normal 文本首段下沉、空文本跳过、id 递增、
 * scene/chapter_end 成项。
 *
 * **2026-08-03 格式块精简**：mood/weather 折叠、pause 累加延迟、effect 附着四组用例随对应块类整族删除
 * （摊平现在只做「可见块过滤 + 首段标记」）。
 */
class StoryReaderRenderItemTest {

    private fun text(s: String, style: StoryTextStyle = StoryTextStyle.NORMAL) = StoryContentBlock.Text(s, style)

    @Test fun empty_blocks_make_empty() {
        assertTrue(StoryReaderRenderItem.make(emptyList()).isEmpty())
    }

    @Test fun single_normal_text_is_first_paragraph() {
        val items = StoryReaderRenderItem.make(listOf(text("你好")))
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(0, item.id)
        assertEquals(StoryReaderRenderItem.Kind.Text("你好", StoryTextStyle.NORMAL), item.kind)
        assertTrue(item.isFirstParagraph)
    }

    @Test fun blank_text_skipped_without_consuming_id_or_first_flag() {
        val items = StoryReaderRenderItem.make(listOf(text("   \n  "), text("正文")))
        assertEquals(1, items.size)
        assertEquals(0, items[0].id)
        assertEquals(StoryReaderRenderItem.Kind.Text("正文", StoryTextStyle.NORMAL), items[0].kind)
        assertTrue(items[0].isFirstParagraph)
    }

    @Test fun first_paragraph_is_first_normal_even_after_whisper() {
        val items = StoryReaderRenderItem.make(
            listOf(text("悄悄话", StoryTextStyle.WHISPER), text("正文")),
        )
        assertEquals(2, items.size)
        assertFalse(items[0].isFirstParagraph) // whisper 不算首段
        assertTrue(items[1].isFirstParagraph) // 第一个 normal 才是
    }

    @Test fun only_one_first_paragraph() {
        val items = StoryReaderRenderItem.make(listOf(text("一"), text("二")))
        assertTrue(items[0].isFirstParagraph)
        assertFalse(items[1].isFirstParagraph)
    }

    @Test fun scene_and_chapter_end_become_items_with_incrementing_ids() {
        val items = StoryReaderRenderItem.make(
            listOf(text("甲"), StoryContentBlock.SceneTransition("夜幕"), StoryContentBlock.ChapterEnd),
        )
        assertEquals(3, items.size)
        assertEquals(StoryReaderRenderItem.Kind.Text("甲", StoryTextStyle.NORMAL), items[0].kind)
        assertEquals(StoryReaderRenderItem.Kind.Scene("夜幕"), items[1].kind)
        assertEquals(StoryReaderRenderItem.Kind.ChapterEnd, items[2].kind)
        assertEquals(listOf(0, 1, 2), items.map { it.id })
    }

    @Test fun scene_and_chapter_end_are_not_first_paragraph() {
        val items = StoryReaderRenderItem.make(
            listOf(StoryContentBlock.SceneTransition("夜幕"), StoryContentBlock.ChapterEnd),
        )
        assertTrue("非文字块一律不算首段", items.none { it.isFirstParagraph })
    }

    @Test fun trimmed_text_stored_without_surrounding_whitespace() {
        val items = StoryReaderRenderItem.make(listOf(text("  中间内容  ")))
        assertEquals(StoryReaderRenderItem.Kind.Text("中间内容", StoryTextStyle.NORMAL), items[0].kind)
    }
}
