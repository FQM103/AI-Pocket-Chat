package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `storyCleanTitle` / `storyNumberToChinese` 测试，反推 iOS `StoryReaderView+Sections.swift:117-135`。
 */
class StoryTitleFormattingTest {

    @Test fun clean_title_strips_chapter_prefix() {
        assertEquals("初遇", storyCleanTitle("第一章：初遇"))
        assertEquals("初遇", storyCleanTitle("第1章 初遇"))
        assertEquals("初遇", storyCleanTitle("第十二章:初遇"))
    }

    @Test fun clean_title_keeps_when_no_prefix_or_empty_remainder() {
        assertEquals("没有前缀", storyCleanTitle("没有前缀"))
        assertEquals("第一章", storyCleanTitle("第一章")) // 去前缀后为空 → 保留原标题
    }

    @Test fun number_to_chinese_basic() {
        assertEquals("零", storyNumberToChinese(0))
        assertEquals("一", storyNumberToChinese(1))
        assertEquals("九", storyNumberToChinese(9))
    }

    @Test fun number_to_chinese_tens() {
        assertEquals("十", storyNumberToChinese(10))
        assertEquals("十一", storyNumberToChinese(11))
        assertEquals("十九", storyNumberToChinese(19))
        assertEquals("二十", storyNumberToChinese(20))
        assertEquals("二十一", storyNumberToChinese(21))
        assertEquals("九十九", storyNumberToChinese(99))
    }

    @Test fun number_to_chinese_hundreds_with_zero() {
        assertEquals("一百", storyNumberToChinese(100))
        assertEquals("一百零一", storyNumberToChinese(101))
        assertEquals("一百一十", storyNumberToChinese(110))
        assertEquals("一百二十三", storyNumberToChinese(123))
        assertEquals("二百零五", storyNumberToChinese(205))
    }

    @Test fun number_to_chinese_thousands_and_fallback() {
        assertEquals("一千二百三十四", storyNumberToChinese(1234))
        assertEquals("10000", storyNumberToChinese(10000)) // 越界回退
        assertEquals("-3", storyNumberToChinese(-3))
    }
}
