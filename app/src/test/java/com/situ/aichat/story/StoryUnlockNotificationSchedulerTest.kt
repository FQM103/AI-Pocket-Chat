package com.situ.aichat.story

import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 解锁通知调度 gate T2（ST7c·§6.5「更新提醒」总闸）：per-story 提醒关 → scheduleUnlock 不排闹钟；开 → 照排。
 * 2026-08-04 删除撤闹钟卷追加：[StoryUnlockNotificationScheduler.cancelUnlocks] 三钉——排↔撤同 key 不变式、
 * key 字面量兼容性契约（老版本排进系统的闹钟新版本必须撤得掉）、撤销不看总闸。
 * Robolectric（scheduleUnlock 内有 android.util.Log.d）；同步调用，无协程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryUnlockNotificationSchedulerTest {

    private val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
    private val repo = mockk<StoryRepository>(relaxed = true)
    private val store = mockk<StoryReadingProgressStore>()
    private val scheduler = StoryUnlockNotificationScheduler(alarm, repo, store)

    @Test
    fun 提醒关_不排解锁闹钟() {
        every { store.unlockReminderEnabled("s1") } returns false
        scheduler.scheduleUnlock("s1", "书名", 3, "第三章", 1_000L)
        verify(exactly = 0) { alarm.scheduleExact(any(), any(), any()) }
    }

    @Test
    fun 提醒开_照排解锁闹钟() {
        every { store.unlockReminderEnabled("s1") } returns true
        scheduler.scheduleUnlock("s1", "书名", 3, "第三章", 1_000L)
        verify(exactly = 1) { alarm.scheduleExact(any(), any(), any()) }
    }

    /** 排↔撤同 key 闭环（撤销拿排程时的同一把钥匙才撤得掉真闹钟）+ key 字面量钉（对老版本已排闹钟的兼容契约）。 */
    @Test
    fun 撤销_与排程同key_且钉字面量() {
        every { store.unlockReminderEnabled("s1") } returns true
        val scheduled = mutableListOf<String>()
        every { alarm.scheduleExact(capture(scheduled), any(), any()) } returns Unit
        scheduler.scheduleUnlock("s1", "书名", 3, "第三章", 1_000L)

        val cancelled = mutableListOf<String>()
        every { alarm.cancel(capture(cancelled)) } returns Unit
        scheduler.cancelUnlocks("s1", listOf(3))

        assertEquals(scheduled, cancelled)
        assertEquals(listOf("storyUnlock_s1_3"), cancelled)
    }

    /** 撤销恒执行、不问「更新提醒」总闸（闸关前排下的闹钟也得撤）；逐章号一撤一个。store 为严格 mock——被碰即失败。 */
    @Test
    fun 撤销_逐章号执行_不看更新提醒总闸() {
        scheduler.cancelUnlocks("s1", listOf(1, 2))
        verify(exactly = 2) { alarm.cancel(any()) }
        verify(exactly = 0) { store.unlockReminderEnabled(any()) }
    }

    /** 自查章号版（追更→自由清列场景·章行还在库里）：从仓库取全部章号、逐个撤同 key 闹钟。 */
    @Test
    fun 撤销ForStory_自查章号逐个撤() = runBlocking {
        coEvery { repo.getChapterNumbers("s1") } returns listOf(1, 3)
        val cancelled = mutableListOf<String>()
        every { alarm.cancel(capture(cancelled)) } returns Unit

        scheduler.cancelUnlocksForStory("s1")

        assertEquals(listOf("storyUnlock_s1_1", "storyUnlock_s1_3"), cancelled)
    }
}
