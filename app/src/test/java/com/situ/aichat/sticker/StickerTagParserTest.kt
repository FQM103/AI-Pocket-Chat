package com.situ.aichat.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with iOS `StickerTagParser` (Models/StickerTypes.swift). Assertions reverse-derived from the
 * iOS regex `\[sticker:([^\]\s]+)\]` and its strip/replace/isStickerOnly semantics.
 */
class StickerTagParserTest {

    @Test fun `extract returns all ids in order`() {
        assertEquals(listOf("a", "b"), StickerTagParser.extractStickerIds("[sticker:a][sticker:b]"))
        assertEquals(listOf("开心_1"), StickerTagParser.extractStickerIds("你好[sticker:开心_1]世界"))
    }

    @Test fun `extract empty when no tags`() {
        assertTrue(StickerTagParser.extractStickerIds("普通文本").isEmpty())
    }

    @Test fun `empty id tag is not a valid tag`() {
        // 正则 [^\]\s]+ 要求至少 1 字符 → [sticker:] 不算标签
        assertTrue(StickerTagParser.extractStickerIds("[sticker:]").isEmpty())
    }

    @Test fun `isStickerOnly true for pure sticker`() {
        assertTrue(StickerTagParser.isStickerOnly("[sticker:开心_1]"))
        assertTrue(StickerTagParser.isStickerOnly("[sticker:a][sticker:b]"))
        assertTrue(StickerTagParser.isStickerOnly("  [sticker:a]  ")) // trim 后纯标签
    }

    @Test fun `isStickerOnly false for mixed or empty`() {
        assertFalse(StickerTagParser.isStickerOnly("你好[sticker:a]"))
        assertFalse(StickerTagParser.isStickerOnly(""))
        assertFalse(StickerTagParser.isStickerOnly("没有标签"))
    }

    @Test fun `stripStickerTags removes tags and trims`() {
        assertEquals("你好", StickerTagParser.stripStickerTags("你好 [sticker:开心_1]"))
        // 正则不含尾随 \s（与 iOS 一致），故标签两侧的空格各保留 → 中间两空格；仅两端 trim
        assertEquals("开头  结尾", StickerTagParser.stripStickerTags("开头 [sticker:x] 结尾"))
    }

    @Test fun `replaceForDisplay maps to 表情包`() {
        assertEquals("[表情包]", StickerTagParser.replaceStickerTagsForDisplay("[sticker:x]"))
        assertEquals("你好[表情包]结尾", StickerTagParser.replaceStickerTagsForDisplay("你好[sticker:x]结尾"))
    }

    @Test fun `containsStickerTag`() {
        assertTrue(StickerTagParser.containsStickerTag("a [sticker:x] b"))
        assertFalse(StickerTagParser.containsStickerTag("没有"))
    }
}
