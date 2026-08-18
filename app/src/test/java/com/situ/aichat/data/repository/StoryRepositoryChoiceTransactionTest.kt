package com.situ.aichat.data.repository

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.StoryDao
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 查询瘦身卷二 T2-4（图纸 §7·E6·Robolectric 真 Room in-memory）：落选择三步写包进一个事务后，
 * ① 正常路的落库效果与改造前逐字段一致；② 中途抛异常时**一步都不留**（回滚实证）。
 *
 * 注错手法用 Kotlin 接口委托（`by delegate` + 只覆写 `updateStatus`）而非 mock 框架：
 * 事务真跑在真 Room 上，回滚是数据库真回滚，不是桩演的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryRepositoryChoiceTransactionTest {

    /** 第二步「写状态」注错：前一步已写的 userChoice 必须随事务一起回滚。 */
    private class FailingStatusDao(private val delegate: StoryDao) : StoryDao by delegate {
        override suspend fun updateStatus(id: String, status: String, updatedAt: Long): Unit =
            throw IllegalStateException("注错：状态写炸了")
    }

    private lateinit var db: AppDatabase
    private lateinit var dao: StoryDao

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.storyDao()
        dao.insertStory(
            StoryEntity(id = STORY_ID, title = "落选择的书", status = StoryStatus.WAITING_CHOICE, cachedHasPendingChoice = true),
        )
        dao.insertChapter(
            StoryChapterEntity(
                id = CHAPTER_ID, storyId = STORY_ID, chapterNumber = 1, title = "第一章",
                createdAt = 1_000L, content = "正文", hasChoice = true, choiceOptions = """["A","B"]""",
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `正常路_三步落库效果与改造前一致`() = runBlocking {
        val repo = StoryRepository(dao, db)

        repo.commitUserChoice(STORY_ID, CHAPTER_ID, "选 A", nowMillis = 9_000L, fromStatus = StoryStatus.WAITING_CHOICE)

        val chapter = checkNotNull(dao.getChapter(CHAPTER_ID))
        assertEquals("选 A", chapter.userChoice)
        assertEquals(9_000L, chapter.choiceMadeAt)
        val story = checkNotNull(dao.getStory(STORY_ID))
        assertEquals(StoryStatus.SERIALIZING, story.status)      // 第二步：置连载中
        assertEquals(9_000L, story.updatedAt)
        assertEquals(1, story.cachedChapterCount)                // 第三步：缓存重算
        assertEquals(1, story.cachedLatestChapterNumber)
        assertEquals("第一章", story.cachedLatestChapterTitle)
        assertFalse("已答选择 → 待选标记清零", story.cachedHasPendingChoice)
    }

    @Test
    fun `不置连载中时_只写章不改状态`() = runBlocking {
        val repo = StoryRepository(dao, db)

        repo.commitUserChoice(
            STORY_ID, CHAPTER_ID, "跳过", nowMillis = 8_000L, setSerializing = false,
            fromStatus = StoryStatus.WAITING_CHOICE,
        )

        assertEquals("跳过", dao.getChapter(CHAPTER_ID)?.userChoice)
        val story = checkNotNull(dao.getStory(STORY_ID))
        assertEquals("状态由结局请求接着设，本步不动", StoryStatus.WAITING_CHOICE, story.status)
        assertFalse(story.cachedHasPendingChoice)                // 缓存重算照跑
    }

    /** E6：第二步炸 → 第一步写的 userChoice 也要没了（章仍待选，用户可重点）。 */
    @Test
    fun `E6_中途抛异常_三步全回滚`() = runBlocking {
        val repo = StoryRepository(FailingStatusDao(dao), db)

        val failure = runCatching {
            repo.commitUserChoice(STORY_ID, CHAPTER_ID, "选 A", nowMillis = 9_000L, fromStatus = StoryStatus.WAITING_CHOICE)
        }.exceptionOrNull()

        assertTrue("异常必须冒到调用方（VM 据此报错）", failure is IllegalStateException)
        val chapter = checkNotNull(dao.getChapter(CHAPTER_ID))
        assertNull("第一步的 userChoice 随事务回滚", chapter.userChoice)
        assertNull(chapter.choiceMadeAt)
        val story = checkNotNull(dao.getStory(STORY_ID))
        assertEquals("状态没变 → 章仍待选，用户可重点", StoryStatus.WAITING_CHOICE, story.status)
        assertTrue("缓存也没被改写", story.cachedHasPendingChoice)
    }

    private companion object {
        const val STORY_ID = "s-tx"
        const val CHAPTER_ID = "c-tx"
    }
}
