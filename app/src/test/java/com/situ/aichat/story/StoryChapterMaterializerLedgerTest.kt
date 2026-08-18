package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 账本族的落库编排行为测试（图纸 §7 T1-5 / T2-4·E1/E3·MockK T2 档）。
 *
 * 手法同 [StoryChapterMaterializerRewriteDraftTest]：MockK 假掉 StoryRepository，用命名参数 slot 捕获
 * `updateNarrativeState` / `updateRewriteState` 真正写进去的三个账本列。断言从图纸 §3.4 的降级矩阵独立反推：
 * - 关系史 / 台账：字段缺失与「无」**同义**（本章没有）→ 账本原样不动；
 * - 场景状态：**缺失 = 沿用上一章**、显式「无」= 清列（J5 两分，最容易被「统一成一种」改坏）；
 * - 重写：两账本按章号回滚、场景状态清空。
 */
class StoryChapterMaterializerLedgerTest {

    private lateinit var storyRepository: StoryRepository
    private lateinit var materializer: StoryChapterMaterializer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        storyRepository = mockk(relaxed = true)
        materializer = StoryChapterMaterializer(storyRepository)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private val existingIntimacy =
        "${StoryLedgers.MILESTONE_HEADER}\n第1章·初遇\n\n${StoryLedgers.RECENT_HEADER}\n第2章·一起散步"

    private fun story(
        intimacyLedger: String? = existingIntimacy,
        sceneState: String? = "咖啡馆｜两人对坐",
        sceneLedger: String? = "第2章·雨夜·车里",
    ) = StoryEntity(
        id = "s1",
        title = "故事",
        storySummary = "旧摘要",
        storyBible = "旧圣经",
        intimacyLedger = intimacyLedger,
        sceneState = sceneState,
        sceneLedger = sceneLedger,
    )

    private fun payload(
        intimacyUpdates: String? = null,
        sceneEndState: String? = null,
        sceneTag: String? = null,
    ) = StoryChapterPayload(
        title = "第三章", mood = "warm", content = "正文内容", hasChoice = true,
        choicePrompt = "你决定", choiceOptions = listOf("A", "B"), summary = "新摘要",
        intimacyUpdates = intimacyUpdates, sceneEndState = sceneEndState, sceneTag = sceneTag,
    )

    /** 捕获 `updateNarrativeState` 实际写入的三个账本列。 */
    private suspend fun captureNarrative(
        story: StoryEntity,
        payload: StoryChapterPayload,
        chapterNumber: Int = 3,
    ): Triple<String?, String?, String?> {
        val intimacy = slot<String?>()
        val sceneState = slot<String?>()
        val sceneLedger = slot<String?>()
        materializer.materializeChapter(payload, chapterNumber, story, nowMillis = 1_000L)
        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = any(), currentArc = any(), characterStates = any(),
                openThreads = any(), pendingChapterBeats = any(), storyBible = any(), status = any(),
                maxChapters = any(), autoExtendCount = any(), requestedEndingType = any(),
                requestedEndingDetail = any(), rewriteInstruction = any(), finalEndingType = any(),
                intimacyLedger = captureNullable(intimacy), sceneState = captureNullable(sceneState),
                sceneLedger = captureNullable(sceneLedger), updatedAt = any(),
            )
        }
        return Triple(intimacy.captured, sceneState.captured, sceneLedger.captured)
    }

    // ── T1-5：落库降级矩阵 ──

    @Test
    fun 三字段齐全_两账本追加_场景状态整列替换() = runBlocking {
        val (intimacy, sceneState, sceneLedger) = captureNarrative(
            story(),
            payload(
                intimacyUpdates = "[里程碑]第一次接吻；[近况]她开始喊他的小名",
                sceneEndState = "她的公寓客厅｜两人并肩坐着",
                sceneTag = "初吻·公寓·沙发上",
            ),
        )

        assertEquals(
            "关系史两段各追加一行（带第3章·前缀），旧行原样保留",
            "${StoryLedgers.MILESTONE_HEADER}\n第1章·初遇\n第3章·第一次接吻\n\n" +
                "${StoryLedgers.RECENT_HEADER}\n第2章·一起散步\n第3章·她开始喊他的小名",
            intimacy,
        )
        assertEquals("场景状态是整列替换，不是追加", "她的公寓客厅｜两人并肩坐着", sceneState)
        assertEquals("台账追加一行", "第2章·雨夜·车里\n第3章·初吻·公寓·沙发上", sceneLedger)
    }

    @Test
    fun 三字段全缺失_两账本原样_场景状态沿用上一章() = runBlocking {
        val s = story()
        val (intimacy, sceneState, sceneLedger) = captureNarrative(s, payload())

        assertEquals("关系史一个字都不许动", s.intimacyLedger, intimacy)
        assertEquals("台账一个字都不许动", s.sceneLedger, sceneLedger)
        assertEquals("**缺失 = 沿用上一章**（同 characterStates 惯例）", "咖啡馆｜两人对坐", sceneState)
    }

    @Test
    fun 显式无_账本不追加_但场景状态被清空() = runBlocking {
        // J5 的分水岭：对两个账本「无」= 不追加；对场景状态「无」= 人已离开该场景 → 清列。
        val s = story()
        val (intimacy, sceneState, sceneLedger) = captureNarrative(
            s,
            payload(intimacyUpdates = "无", sceneEndState = "无", sceneTag = "无"),
        )

        assertEquals(s.intimacyLedger, intimacy)
        assertEquals(s.sceneLedger, sceneLedger)
        assertNull("显式「无」必须清空场景状态，否则下一章会照着一个早已散场的场景写", sceneState)
    }

    @Test
    fun 空书首章_账本从零建起() = runBlocking {
        val (intimacy, sceneState, sceneLedger) = captureNarrative(
            story(intimacyLedger = null, sceneState = null, sceneLedger = null),
            payload(intimacyUpdates = "[近况]第一次并肩走路", sceneEndState = "校门口｜各自回家", sceneTag = "初遇·校门口·并肩"),
            chapterNumber = 1,
        )

        assertEquals(
            "${StoryLedgers.MILESTONE_HEADER}\n\n${StoryLedgers.RECENT_HEADER}\n第1章·第一次并肩走路",
            intimacy,
        )
        assertEquals("校门口｜各自回家", sceneState)
        assertEquals("第1章·初遇·校门口·并肩", sceneLedger)
    }

    @Test
    fun 空书首章_三字段也缺失_三列全为null() = runBlocking {
        val (intimacy, sceneState, sceneLedger) = captureNarrative(
            story(intimacyLedger = null, sceneState = null, sceneLedger = null),
            payload(),
            chapterNumber = 1,
        )
        assertNull(intimacy)
        assertNull(sceneState)
        assertNull(sceneLedger)
    }

    // ── T2-4：重写回滚（E3）──

    @Test
    fun 重写准备_两账本删本章行_场景状态清空() = runBlocking {
        val s = story(
            intimacyLedger = "${StoryLedgers.MILESTONE_HEADER}\n第1章·初遇\n第4章·同居\n\n" +
                "${StoryLedgers.RECENT_HEADER}\n第2章·一起散步\n第4章·一起做饭",
            sceneLedger = "第2章·雨夜·车里\n第4章·浴室·热气",
        )
        val latest = StoryChapterEntity(id = "c4", storyId = "s1", chapterNumber = 4, title = "第四章", content = "正文")
        coEvery { storyRepository.getChapterMetaBefore("s1", 4) } returns
            StoryChapterEntity(id = "c3", storyId = "s1", chapterNumber = 3, title = "第三章", chapterSummary = "第三章摘要")

        materializer.prepareRewrite(s, latestChapter = latest, instruction = "重来", nowMillis = 5L)

        val intimacy = slot<String?>()
        val sceneState = slot<String?>()
        val sceneLedger = slot<String?>()
        coVerify {
            storyRepository.updateRewriteState(
                id = "s1", storyBible = any(), storySummary = any(), rewriteInstruction = "重来",
                status = any(), pendingChapterBeats = null,
                intimacyLedger = captureNullable(intimacy), sceneState = captureNullable(sceneState),
                sceneLedger = captureNullable(sceneLedger), updatedAt = 5L,
            )
        }

        assertEquals(
            "第4章两行被删，第1/2章原样留下",
            "${StoryLedgers.MILESTONE_HEADER}\n第1章·初遇\n\n${StoryLedgers.RECENT_HEADER}\n第2章·一起散步",
            intimacy.captured,
        )
        assertEquals("第2章·雨夜·车里", sceneLedger.captured)
        assertNull("场景状态没有历史可回滚，直接清空由新版重写", sceneState.captured)
    }

    @Test
    fun 重写准备_账本本就为空_回滚后仍为null() = runBlocking {
        val s = story(intimacyLedger = null, sceneState = null, sceneLedger = null)
        val latest = StoryChapterEntity(id = "c2", storyId = "s1", chapterNumber = 2, title = "第二章", content = "正文")
        coEvery { storyRepository.getChapterMetaBefore("s1", 2) } returns null

        materializer.prepareRewrite(s, latestChapter = latest, instruction = null, nowMillis = 5L)

        val intimacy = slot<String?>()
        val sceneLedger = slot<String?>()
        coVerify {
            storyRepository.updateRewriteState(
                id = "s1", storyBible = any(), storySummary = any(), rewriteInstruction = any(),
                status = any(), pendingChapterBeats = null,
                intimacyLedger = captureNullable(intimacy), sceneState = any(),
                sceneLedger = captureNullable(sceneLedger), updatedAt = any(),
            )
        }
        assertNull(intimacy.captured)
        assertNull(sceneLedger.captured)
    }
}
