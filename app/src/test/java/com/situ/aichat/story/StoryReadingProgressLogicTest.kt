package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `StoryReadingProgressLogic`（11.1h-1）测试，断言反推 iOS `StoryReadingProgressStore`
 * latestPendingChoiceChapter / preferredResumeChapter 优先级：待选 > 上次读 > 最新。
 */
class StoryReadingProgressLogicTest {

    private fun chapter(id: String, number: Int, hasChoice: Boolean = false, userChoice: String? = null) =
        StoryChapterEntity(id = id, storyId = "s", chapterNumber = number, hasChoice = hasChoice, userChoice = userChoice)

    // 升序章节列表（= getChapters 返回顺序）
    private val chapters = listOf(
        chapter("c1", 1),
        chapter("c2", 2, hasChoice = true, userChoice = "A"), // 有选择已答
        chapter("c3", 3, hasChoice = true, userChoice = null), // 有选择未答
        chapter("c4", 4),
    )

    @Test fun latest_pending_choice_is_last_unanswered() {
        assertEquals("c3", StoryReadingProgressLogic.latestPendingChoiceChapter(chapters)?.id)
    }

    @Test fun latest_pending_choice_null_when_all_answered_or_none() {
        val noPending = listOf(chapter("a", 1), chapter("b", 2, hasChoice = true, userChoice = "B"))
        assertNull(StoryReadingProgressLogic.latestPendingChoiceChapter(noPending))
    }

    @Test fun preferred_returns_pending_first_even_with_last_read() {
        // 有待选章 → 优先待选，忽略 lastRead
        assertEquals("c3", StoryReadingProgressLogic.preferredResumeChapter(chapters, lastReadChapterId = "c1")?.id)
    }

    @Test fun preferred_falls_to_last_read_when_no_pending() {
        val noPending = listOf(chapter("a", 1), chapter("b", 2), chapter("c", 3))
        assertEquals("b", StoryReadingProgressLogic.preferredResumeChapter(noPending, lastReadChapterId = "b")?.id)
    }

    @Test fun preferred_falls_to_latest_when_no_pending_no_last_read() {
        val noPending = listOf(chapter("a", 1), chapter("b", 2), chapter("c", 3))
        assertEquals("c", StoryReadingProgressLogic.preferredResumeChapter(noPending, lastReadChapterId = null)?.id)
    }

    @Test fun preferred_falls_to_latest_when_last_read_id_missing() {
        val noPending = listOf(chapter("a", 1), chapter("b", 2))
        assertEquals("b", StoryReadingProgressLogic.preferredResumeChapter(noPending, lastReadChapterId = "gone")?.id)
    }

    @Test fun preferred_null_on_empty() {
        assertNull(StoryReadingProgressLogic.preferredResumeChapter(emptyList(), lastReadChapterId = "x"))
    }

    // ── 「已推进则前移一章」档（2026-08-06 修「第 3 章写好了，进来还是第 2 章」）──
    // 用户场景反推：章末选项默认关（2026-08-05 拍板）后新章恒无待选，待选兜底恒 null，只剩「上次阅读章」；
    // 而「上次阅读章」正是用户按下推进的那一章 ⇒ 他已经看完并要了下一章 ⇒ 落点该落在下一章。

    /** 无选项的书（hasChoice 恒 false）：看完第 2 章按了推进、第 3 章已写好 → 续读落第 3 章。 */
    @Test fun preferred_advances_one_chapter_when_user_pushed_from_last_read() {
        val noChoice = listOf(chapter("c1", 1), chapter("c2", 2), chapter("c3", 3))
        assertEquals(
            "c3",
            StoryReadingProgressLogic.preferredResumeChapter(
                noChoice, lastReadChapterId = "c2", advancedFromChapterNumber = 2,
            )?.id,
        )
    }

    /** 只前移**一章**：追更攒了好几章时不许一次跳到最末，否则替用户跳过了中间没读的章。 */
    @Test fun preferred_advances_only_one_chapter_not_to_latest() {
        val noChoice = listOf(chapter("c1", 1), chapter("c2", 2), chapter("c3", 3), chapter("c4", 4))
        assertEquals(
            "c3",
            StoryReadingProgressLogic.preferredResumeChapter(
                noChoice, lastReadChapterId = "c2", advancedFromChapterNumber = 2,
            )?.id,
        )
    }

    /** 没按过推进（追更半夜自动更新的章）→ 原地不动，别把没读完的人推着走。 */
    @Test fun preferred_stays_when_no_advance_mark() {
        val noChoice = listOf(chapter("c1", 1), chapter("c2", 2), chapter("c3", 3))
        assertEquals(
            "c2",
            StoryReadingProgressLogic.preferredResumeChapter(
                noChoice, lastReadChapterId = "c2", advancedFromChapterNumber = null,
            )?.id,
        )
    }

    /** 标记指的不是这一章（用户回头重读旧章）→ 原地不动。 */
    @Test fun preferred_stays_when_advance_mark_points_elsewhere() {
        val noChoice = listOf(chapter("c1", 1), chapter("c2", 2), chapter("c3", 3))
        assertEquals(
            "c1",
            StoryReadingProgressLogic.preferredResumeChapter(
                noChoice, lastReadChapterId = "c1", advancedFromChapterNumber = 2,
            )?.id,
        )
    }

    /** 按了推进但下一章还没写出来（生成中/失败）→ 原地不动。 */
    @Test fun preferred_stays_when_next_chapter_not_written_yet() {
        val noChoice = listOf(chapter("c1", 1), chapter("c2", 2))
        assertEquals(
            "c2",
            StoryReadingProgressLogic.preferredResumeChapter(
                noChoice, lastReadChapterId = "c2", advancedFromChapterNumber = 2,
            )?.id,
        )
    }

    /** 待选章仍是第一优先：开着章末选项的书，行为与加这一档之前逐字节相同。 */
    @Test fun preferred_pending_choice_still_wins_over_advance_mark() {
        assertEquals(
            "c3",
            StoryReadingProgressLogic.preferredResumeChapter(
                chapters, lastReadChapterId = "c2", advancedFromChapterNumber = 2,
            )?.id,
        )
    }
}
