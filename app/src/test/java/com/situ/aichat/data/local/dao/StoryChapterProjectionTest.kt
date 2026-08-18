package com.situ.aichat.data.local.dao

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 查询瘦身卷二 T2-1（图纸 §7·E1/E2·Robolectric 真 Room in-memory）：投影查询的「该带的列一个不少、
 * 不该带的列落默认值」+ 末章全列含正文 + 章号有洞时「前一项」语义。断言从图纸 §3.1 列清单独立反推。
 *
 * 覆盖两组投影（章节 17 列 / stories 29 列）——列名写错编译期就炸，**漏列**只能靠这里逐字段比对抓出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryChapterProjectionTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: StoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.storyDao()
    }

    @After
    fun tearDown() = db.close()

    /** 章：17 元数据列逐字段给不同值（默认值撞车会让「漏列」假绿）。 */
    private fun chapter(id: String, number: Int) = StoryChapterEntity(
        id = id,
        storyId = STORY_ID,
        chapterNumber = number,
        title = "第 $number 章的标题",
        teaser = "引子 $number",
        createdAt = 1_700_000_000_000L + number,
        content = "正文正文正文 $number".repeat(50),
        mood = "tense",
        scenes = "场景 $number",
        hasChoice = true,
        choicePrompt = "选择提示 $number",
        choiceOptions = """["A$number","B$number"]""",
        userChoice = "选了 A$number",
        aiSuggestedEnding = true,
        choiceMadeAt = 1_700_000_100_000L + number,
        chapterSummary = "小结 $number",
        unlockAt = 1_700_000_200_000L + number,
        userRating = 3,
        previousDraftJson = """{"content":"上一版正文 $number"}""",
    )

    private fun insertStoryAndChapters(vararg numbers: Int) = runBlocking {
        dao.insertStory(StoryEntity(id = STORY_ID, title = "投影测试书"))
        numbers.forEach { dao.insertChapter(chapter("c$it", it)) }
    }

    @Test
    fun `getChapterMetas 带齐 17 列且正文与旧稿落默认值`() = runBlocking {
        insertStoryAndChapters(1, 2, 3)

        val metas = dao.getChapterMetas(STORY_ID)

        assertEquals(listOf(1, 2, 3), metas.map { it.chapterNumber })
        metas.forEach { row ->
            val expected = chapter("c${row.chapterNumber}", row.chapterNumber)
            // 排除列：正文落空串、旧稿槽落 null（图纸 §0.3-1 的「落 Kotlin 默认值」）。
            assertEquals("", row.content)
            assertNull(row.previousDraftJson)
            // 其余 17 列逐字段等于插入值。
            assertEquals(expected.id, row.id)
            assertEquals(expected.storyId, row.storyId)
            assertEquals(expected.chapterNumber, row.chapterNumber)
            assertEquals(expected.title, row.title)
            assertEquals(expected.teaser, row.teaser)
            assertEquals(expected.createdAt, row.createdAt)
            assertEquals(expected.mood, row.mood)
            assertEquals(expected.scenes, row.scenes)
            assertEquals(expected.hasChoice, row.hasChoice)
            assertEquals(expected.choicePrompt, row.choicePrompt)
            assertEquals(expected.choiceOptions, row.choiceOptions)
            assertEquals(expected.userChoice, row.userChoice)
            assertEquals(expected.aiSuggestedEnding, row.aiSuggestedEnding)
            assertEquals(expected.choiceMadeAt, row.choiceMadeAt)
            assertEquals(expected.chapterSummary, row.chapterSummary)
            assertEquals(expected.unlockAt, row.unlockAt)
            assertEquals(expected.userRating, row.userRating)
        }
    }

    @Test
    fun `getLatestChapterMeta 取末章且不带正文`() = runBlocking {
        insertStoryAndChapters(1, 2, 3)

        val latest = dao.getLatestChapterMeta(STORY_ID)

        assertEquals(3, latest?.chapterNumber)
        assertEquals("小结 3", latest?.chapterSummary)
        assertEquals("", latest?.content)
        assertNull(latest?.previousDraftJson)
    }

    @Test
    fun `getLatestChapter 全列取末章且带正文与旧稿`() = runBlocking {
        insertStoryAndChapters(1, 2, 3)

        val latest = dao.getLatestChapter(STORY_ID)

        assertEquals(3, latest?.chapterNumber)
        assertEquals(chapter("c3", 3).content, latest?.content)
        assertEquals(chapter("c3", 3).previousDraftJson, latest?.previousDraftJson)
    }

    /** E2：章号有洞（1,3,7）时「前一项」= 升序列表的前一项，不是 章号-1。 */
    @Test
    fun `getChapterMetaBefore 按升序前一项取而非章号减一`() = runBlocking {
        insertStoryAndChapters(1, 3, 7)

        assertEquals(3, dao.getChapterMetaBefore(STORY_ID, 7)?.chapterNumber)
        assertEquals(1, dao.getChapterMetaBefore(STORY_ID, 3)?.chapterNumber)
        // E3：首章之前没有上一章。
        assertNull(dao.getChapterMetaBefore(STORY_ID, 1))
    }

    @Test
    fun `observeStoriesLite 带齐 29 轻列且 18 个大文本列落 null`() = runBlocking {
        val story = StoryEntity(
            id = STORY_ID,
            title = "轻列投影书",
            genre = "悬疑",
            coverColorScheme = "ink",
            createdAt = 111L,
            updatedAt = 222L,
            worldSetting = "被排除的世界观",
            plotDirection = "被排除的剧情方向",
            writingStyle = "冷硬",
            chapterLengthPreference = 3000,
            maxChapters = 42,
            autoExtendCount = 2,
            chatInfluenceWeight = "high",
            narrativePerson = "first",
            updateMode = "chase",
            unlockHour = 7,
            unlockMinute = 30,
            worldInfoEnabled = false,
            status = "paused",
            storySummary = "被排除的摘要",
            currentArc = "被排除的弧",
            characterStates = "被排除的角色状态",
            openThreads = "被排除的伏笔",
            storyBible = "被排除的圣经",
            lastCompressedAtChapter = 8,
            lastBibleCompressedAtChapter = 6,
            storyOutline = "被排除的大纲",
            pendingChapterBeats = "被排除的节拍",
            pendingBeatsUserEdited = true,
            currentArcStartChapter = 5,
            arcHistory = "被排除的弧史",
            intimacyLedger = "被排除的关系史",
            sceneState = "被排除的场景状态",
            sceneLedger = "被排除的场景台账",
            customPromptsJson = """{"k":"被排除"}""",
            requestedEndingType = "custom",
            requestedEndingDetail = "被排除的结局细节",
            rewriteInstruction = "被排除的重写指令",
            pendingRewriteDraftJson = """{"content":"被排除的接力棒"}""",
            finaleEndingType = "ai",
            finaleEndingDetail = "被排除的收尾细节",
            finalEndingType = "open",
            cachedChapterCount = 9,
            cachedLatestChapterNumber = 9,
            cachedLatestChapterTitle = "第九章",
            cachedLatestChapterCreatedAt = 999L,
            cachedHasPendingChoice = true,
        )
        dao.insertStory(story)

        val lite = dao.observeStoriesLite().first().single()
        val latest = checkNotNull(dao.observeLatestStoryLite().first())

        listOf(lite, latest).forEach { row ->
            // 保留的 29 列逐字段等于插入值。
            assertEquals(story.id, row.id)
            assertEquals(story.title, row.title)
            assertEquals(story.genre, row.genre)
            assertEquals(story.coverColorScheme, row.coverColorScheme)
            assertEquals(story.createdAt, row.createdAt)
            assertEquals(story.updatedAt, row.updatedAt)
            assertEquals(story.writingStyle, row.writingStyle)
            assertEquals(story.chapterLengthPreference, row.chapterLengthPreference)
            assertEquals(story.maxChapters, row.maxChapters)
            assertEquals(story.autoExtendCount, row.autoExtendCount)
            assertEquals(story.chatInfluenceWeight, row.chatInfluenceWeight)
            assertEquals(story.narrativePerson, row.narrativePerson)
            assertEquals(story.updateMode, row.updateMode)
            assertEquals(story.unlockHour, row.unlockHour)
            assertEquals(story.unlockMinute, row.unlockMinute)
            assertEquals(story.worldInfoEnabled, row.worldInfoEnabled)
            assertEquals(story.status, row.status)
            assertEquals(story.lastCompressedAtChapter, row.lastCompressedAtChapter)
            assertEquals(story.lastBibleCompressedAtChapter, row.lastBibleCompressedAtChapter)
            assertEquals(story.pendingBeatsUserEdited, row.pendingBeatsUserEdited)
            assertEquals(story.currentArcStartChapter, row.currentArcStartChapter)
            assertEquals(story.requestedEndingType, row.requestedEndingType)
            assertEquals(story.finaleEndingType, row.finaleEndingType)
            assertEquals(story.finalEndingType, row.finalEndingType)
            assertEquals(story.cachedChapterCount, row.cachedChapterCount)
            assertEquals(story.cachedLatestChapterNumber, row.cachedLatestChapterNumber)
            assertEquals(story.cachedLatestChapterTitle, row.cachedLatestChapterTitle)
            assertEquals(story.cachedLatestChapterCreatedAt, row.cachedLatestChapterCreatedAt)
            assertEquals(story.cachedHasPendingChoice, row.cachedHasPendingChoice)
            // 18 个大文本列一律落实体默认 null（禁读·图纸 §9）。
            assertTrue(
                listOf(
                    row.worldSetting, row.plotDirection, row.storySummary, row.currentArc, row.characterStates,
                    row.openThreads, row.storyBible, row.storyOutline, row.pendingChapterBeats, row.arcHistory,
                    row.intimacyLedger, row.sceneState, row.sceneLedger, row.customPromptsJson,
                    row.requestedEndingDetail, row.rewriteInstruction, row.pendingRewriteDraftJson,
                    row.finaleEndingDetail,
                ).all { it == null },
            )
        }
    }

    private companion object {
        const val STORY_ID = "s-proj"
    }
}
