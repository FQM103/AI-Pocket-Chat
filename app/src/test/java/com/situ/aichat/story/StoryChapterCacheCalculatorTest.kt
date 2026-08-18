package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterCacheRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryChapterCacheCalculator` tests (P11.1a), reverse-derived from iOS
 * `Models/Story.swift` `refreshChapterCaches` :225-245:
 * count = chapters.count; latest = max(chapterNumber) tie-broken by max(createdAt);
 * hasPendingChoice = latest.hasChoice && latest.userChoice == null; empty → nulls;
 * `explicitLatest` override (rewrite-delete case, spec §4#4).
 */
class StoryChapterCacheCalculatorTest {

    private fun row(
        number: Int,
        title: String = "第${number}章",
        createdAt: Long = number.toLong(),
        hasChoice: Boolean = false,
        userChoice: String? = null,
    ) = StoryChapterCacheRow(number, title, createdAt, hasChoice, userChoice)

    @Test fun empty_yields_zero_count_and_nulls() {
        val c = StoryChapterCacheCalculator.compute(emptyList())
        assertEquals(0, c.count)
        assertNull(c.latestNumber)
        assertNull(c.latestTitle)
        assertNull(c.latestCreatedAt)
        assertFalse(c.hasPendingChoice)
    }

    @Test fun pending_choice_when_latest_has_choice_and_no_user_choice() {
        val c = StoryChapterCacheCalculator.compute(listOf(row(1, hasChoice = true, userChoice = null)))
        assertTrue(c.hasPendingChoice)
        assertEquals(1, c.latestNumber)
    }

    @Test fun no_pending_choice_once_user_chose() {
        val c = StoryChapterCacheCalculator.compute(listOf(row(1, hasChoice = true, userChoice = "A")))
        assertFalse(c.hasPendingChoice)
    }

    @Test fun no_pending_choice_when_chapter_has_no_choice() {
        val c = StoryChapterCacheCalculator.compute(listOf(row(1, hasChoice = false)))
        assertFalse(c.hasPendingChoice)
    }

    @Test fun latest_is_highest_chapter_number_regardless_of_input_order() {
        val c = StoryChapterCacheCalculator.compute(
            listOf(row(3, title = "第三章"), row(1), row(2)),
        )
        assertEquals(3, c.count)
        assertEquals(3, c.latestNumber)
        assertEquals("第三章", c.latestTitle)
    }

    @Test fun same_chapter_number_breaks_tie_by_later_createdAt() {
        // 同章号 2：createdAt 较晚者胜（iOS `.max` 比较器先比 number 再比 createdAt）。
        val c = StoryChapterCacheCalculator.compute(
            listOf(
                row(2, title = "旧版", createdAt = 1000L),
                row(2, title = "新版", createdAt = 2000L),
            ),
        )
        assertEquals(2, c.count)
        assertEquals(2, c.latestNumber)
        assertEquals("新版", c.latestTitle)
        assertEquals(2000L, c.latestCreatedAt)
    }

    @Test fun explicit_latest_overrides_resolution_but_count_still_from_list() {
        // 重写删章后传「删除前的上一章」当 latest；count 仍取当前列表大小（spec §4#4）。
        val remaining = listOf(row(1, title = "第一章"), row(2, title = "第二章"))
        val explicit = row(1, title = "第一章", hasChoice = true, userChoice = null)
        val c = StoryChapterCacheCalculator.compute(remaining, explicitLatest = explicit)
        assertEquals(2, c.count)
        assertEquals(1, c.latestNumber)
        assertEquals("第一章", c.latestTitle)
        assertTrue(c.hasPendingChoice)
    }
}
