package com.situ.aichat.story

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 阅读进度存储 T2（卷三 C3·图纸 §3/§5 E4）：新增的「上次阅读时刻」增列键写读，
 * 且三个既有键（最近故事 / 章 id / 章号）语义一字不变（图纸 §2.2 B4 回归钉）。
 *
 * 真 SharedPreferences（Robolectric），时间戳从**真实 now 相对构造**（PITFALLS §1e：生产码内部直取
 * System.currentTimeMillis 时，测试绝不写绝对时刻）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryReadingProgressStoreTest {

    private lateinit var store: StoryReadingProgressStore

    @Before
    fun setUp() {
        store = StoryReadingProgressStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun 从未读过的故事_时间戳为null() {
        assertNull("老用户首次 → 不弹上回说到，本次进入才写入", store.lastReadAtMillis("never-read"))
    }

    @Test
    fun 保存进度即写入本次阅读时刻() {
        val now = System.currentTimeMillis()
        store.saveProgress("s1", "ch-3", chapterNumber = 3, nowMillis = now)
        assertEquals(now, store.lastReadAtMillis("s1"))
    }

    @Test
    fun 时间戳按故事分开记() {
        val now = System.currentTimeMillis()
        store.saveProgress("s1", "ch-1", chapterNumber = 1, nowMillis = now)
        store.saveProgress("s2", "ch-9", chapterNumber = 9, nowMillis = now - 90_000)
        assertEquals(now, store.lastReadAtMillis("s1"))
        assertEquals(now - 90_000, store.lastReadAtMillis("s2"))
    }

    @Test
    fun 再次保存覆盖为最新时刻() {
        val now = System.currentTimeMillis()
        store.saveProgress("s1", "ch-1", chapterNumber = 1, nowMillis = now - 100_000)
        store.saveProgress("s1", "ch-2", chapterNumber = 2, nowMillis = now)
        assertEquals(now, store.lastReadAtMillis("s1"))
    }

    @Test
    fun 三个既有键语义不变() {
        val now = System.currentTimeMillis()
        store.saveProgress("s1", "ch-7", chapterNumber = 7, nowMillis = now)
        assertEquals("ch-7", store.lastReadChapterId("s1"))
        assertEquals(7, store.lastReadChapterNumber("s1"))
        assertEquals("s1", store.lastOpenedStoryId())
    }

    @Test
    fun 不传章号时仍写时间戳且不写章号() {
        val now = System.currentTimeMillis()
        store.saveProgress("s3", "ch-x", nowMillis = now)
        assertNull("章号可选参未传 → 不落章号（既有语义）", store.lastReadChapterNumber("s3"))
        assertEquals(now, store.lastReadAtMillis("s3"))
    }

    @Test
    fun 默认时刻取系统时钟() {
        val before = System.currentTimeMillis()
        store.saveProgress("s4", "ch-1", chapterNumber = 1)
        val after = System.currentTimeMillis()
        val recorded = store.lastReadAtMillis("s4")!!
        assertEquals("落在调用前后的真实区间内", true, recorded in before..after)
    }

    // ── 「推进起点」标记（2026-08-06 续读落点修复）──

    @Test
    fun 未记过推进起点_返回null() {
        assertNull(store.advancedFromChapterNumber("never-pushed"))
    }

    @Test
    fun 推进起点按故事分开记() {
        store.markAdvancedFrom("s1", 2)
        store.markAdvancedFrom("s2", 9)
        assertEquals(2, store.advancedFromChapterNumber("s1"))
        assertEquals(9, store.advancedFromChapterNumber("s2"))
    }

    @Test
    fun 仍停在推进那一章_标记保留() {
        // 阅读器重建 / 用户退出再进来落回原章：这次推进还没兑现，标记必须留着。
        store.markAdvancedFrom("s1", 2)
        store.saveProgress("s1", "ch-2", chapterNumber = 2)
        assertEquals(2, store.advancedFromChapterNumber("s1"))
    }

    @Test
    fun 打开别的章_标记自消费清除() {
        // 跳到新写好的第 3 章（或用户自己回头翻旧章）= 这次推进已翻篇 → 清，免得下次续读又把落点往前推。
        store.markAdvancedFrom("s1", 2)
        store.saveProgress("s1", "ch-3", chapterNumber = 3)
        assertNull(store.advancedFromChapterNumber("s1"))
    }

    @Test
    fun 清标记不碰同故事的其它三个键() {
        val now = System.currentTimeMillis()
        store.markAdvancedFrom("s1", 2)
        store.saveProgress("s1", "ch-3", chapterNumber = 3, nowMillis = now)
        assertEquals("ch-3", store.lastReadChapterId("s1"))
        assertEquals(3, store.lastReadChapterNumber("s1"))
        assertEquals(now, store.lastReadAtMillis("s1"))
    }
}
