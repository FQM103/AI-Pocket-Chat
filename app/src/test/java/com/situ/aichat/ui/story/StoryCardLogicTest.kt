package com.situ.aichat.ui.story

import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryUpdateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `StoryCardLogic.quickAction`（11.1h-2）测试，断言反推 iOS `StoryCardView.quickActionTitle` 优先级：
 * 失败 > 生成中 > 待选择(且有最新章) > 上次阅读 > 阅读最新 > 无。
 */
class StoryCardLogicTest {

    private fun act(status: String, pending: Boolean = false, latest: Int? = null, lastRead: Int? = null) =
        StoryCardLogic.quickAction(status, pending, latest, lastRead)

    @Test fun failed_wins_over_everything() {
        assertEquals(
            StoryQuickAction.REGENERATE,
            act(StoryStatus.GENERATION_FAILED, pending = true, latest = 5, lastRead = 3),
        )
    }

    @Test fun generating_wins_over_choice_and_read() {
        assertEquals(StoryQuickAction.GENERATING, act(StoryStatus.GENERATING, pending = true, latest = 5, lastRead = 3))
    }

    @Test fun pending_choice_needs_latest_chapter() {
        assertEquals(StoryQuickAction.MAKE_CHOICE, act(StoryStatus.SERIALIZING, pending = true, latest = 5, lastRead = 3))
        // 待选标志但无最新章 → 落到下一优先级（lastRead）
        assertEquals(StoryQuickAction.CONTINUE_READING, act(StoryStatus.SERIALIZING, pending = true, latest = null, lastRead = 3))
    }

    @Test fun continue_reading_when_has_last_read() {
        assertEquals(StoryQuickAction.CONTINUE_READING, act(StoryStatus.SERIALIZING, latest = 5, lastRead = 2))
    }

    @Test fun read_latest_when_no_last_read_but_has_chapter() {
        assertEquals(StoryQuickAction.READ_LATEST, act(StoryStatus.SERIALIZING, latest = 5, lastRead = null))
    }

    @Test fun none_when_no_chapter_no_read() {
        assertNull(act(StoryStatus.SERIALIZING, latest = null, lastRead = null))
    }

    // ── menuActions（ST10-4 状态感知长按菜单）——期望从产品矩阵独立反推（微图纸 §4 锁定项）──

    private fun menu(status: String, mode: String = StoryUpdateMode.FREE) = StoryCardLogic.menuActions(status, mode)

    @Test fun 菜单_连载中追更_才有暂停() {
        assertEquals(
            listOf(StoryCardMenuAction.PAUSE, StoryCardMenuAction.ARCHIVE, StoryCardMenuAction.SETTINGS, StoryCardMenuAction.DELETE),
            menu(StoryStatus.SERIALIZING, StoryUpdateMode.CHASE),
        )
    }

    @Test fun 菜单_连载中自由_无暂停项() {
        // 自由模式从不自动更新，暂停无实际效果 → 不列（根治「点了没反应」）
        assertEquals(
            listOf(StoryCardMenuAction.ARCHIVE, StoryCardMenuAction.SETTINGS, StoryCardMenuAction.DELETE),
            menu(StoryStatus.SERIALIZING, StoryUpdateMode.FREE),
        )
    }

    @Test fun 菜单_已暂停_恢复加归档() {
        assertEquals(
            listOf(StoryCardMenuAction.RESUME, StoryCardMenuAction.ARCHIVE, StoryCardMenuAction.SETTINGS, StoryCardMenuAction.DELETE),
            menu(StoryStatus.PAUSED, StoryUpdateMode.CHASE),
        )
    }

    @Test fun 菜单_等待选择与生成失败_归档设定删除() {
        val expected = listOf(StoryCardMenuAction.ARCHIVE, StoryCardMenuAction.SETTINGS, StoryCardMenuAction.DELETE)
        assertEquals(expected, menu(StoryStatus.WAITING_CHOICE))
        assertEquals(expected, menu(StoryStatus.GENERATION_FAILED))
    }

    @Test fun 菜单_生成中与已完结_只留设定删除() {
        // 生成中不给归档（与落库赛跑）；完结书不在在读区，兜底组合防未来调用面扩大
        val expected = listOf(StoryCardMenuAction.SETTINGS, StoryCardMenuAction.DELETE)
        assertEquals(expected, menu(StoryStatus.GENERATING, StoryUpdateMode.CHASE))
        assertEquals(expected, menu(StoryStatus.COMPLETED))
        assertEquals(expected, menu("unknownRaw"))
    }
}
