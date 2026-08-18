package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
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
 * 「上一版」单槽的落库编排行为测试（阅读器掌控力 C3·图纸三 §7 T2-1）：E1 非重写零消费 / E2 先存后删写序与挂槽 /
 * E4 重写失败后改手动推进仍挂槽 / E8 连续两次重写槽恒存「最近一次重写前」。
 *
 * 手法同 [StoryChapterMaterializerTest]：MockK 假掉 StoryRepository（slot 捕获写入实体 + coVerify/coVerifyOrder
 * 钉死调用与顺序），纯逻辑真跑。断言从图纸 §3.1 写序规格与 §0.2-2 消费守卫独立反推——
 * **先写快照后删章**是进程死亡不丢稿的唯一依据，顺序反了测试必须红。
 */
class StoryChapterMaterializerRewriteDraftTest {

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

    private fun story(
        rewriteInstruction: String? = null,
        pendingRewriteDraftJson: String? = null,
    ) = StoryEntity(
        id = "s1",
        title = "故事",
        storySummary = "旧摘要",
        storyBible = "旧圣经",
        rewriteInstruction = rewriteInstruction,
        pendingRewriteDraftJson = pendingRewriteDraftJson,
    )

    private fun payload() = StoryChapterPayload(
        title = "第一章", teaser = "引子", mood = "warm", content = "正文内容", hasChoice = true,
        choicePrompt = "你决定", choiceOptions = listOf("A", "B"), summary = "新摘要", currentArc = "新弧线",
        isEnding = false, characterStates = "新状态", openThreads = "新伏笔", nextChapterBeats = "下章方向",
    )

    private fun chapterRow(id: String, number: Int, content: String = "第${number}章正文") =
        StoryChapterEntity(
            id = id, storyId = "s1", chapterNumber = number, title = "第${number}章",
            content = content, chapterSummary = "第${number}章摘要", userChoice = "选项A",
        )

    // ── E1：非重写路零消费 ──

    @Test
    fun E1_正常续章_新章槽为null且不清中转位() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs

        materializer.materializeChapter(payload(), chapterNumber = 2, story = story(), nowMillis = 1L)

        assertNull("正常续章不该有上一版可回翻", slot.captured.previousDraftJson)
        coVerify(exactly = 0) { storyRepository.clearPendingRewriteDraft(any()) }
    }

    /**
     * 守卫的另一半：中转位有残留（比如上一次重写没清干净）但**没有重写在进行**（rewriteInstruction 为 null）
     * → 绝不挂槽。否则一本书会平白给某个普通续章挂上一版陌生的旧稿。
     */
    @Test
    fun E1_有残留中转位但非重写中_仍不挂槽() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        val s = story(rewriteInstruction = null, pendingRewriteDraftJson = """{"title":"陈年残留"}""")

        materializer.materializeChapter(payload(), chapterNumber = 3, story = s, nowMillis = 1L)

        assertNull("消费守卫要求 rewriteInstruction != null", slot.captured.previousDraftJson)
        coVerify(exactly = 0) { storyRepository.clearPendingRewriteDraft(any()) }
    }

    // ── E2：先存后删 + 挂槽 ──

    @Test
    fun E2_重写准备_先存快照后删章() = runBlocking {
        val ch2 = chapterRow("c2", 2)
        coEvery { storyRepository.getChapterMetaBefore("s1", 2) } returns chapterRow("c1", 1)

        materializer.prepareRewrite(story(), latestChapter = ch2, instruction = "改温柔点", nowMillis = 5L)

        // 顺序是承重件：快照必须先落库，否则进程在删章后死掉 = 旧稿永久蒸发。
        coVerifyOrder {
            storyRepository.setPendingRewriteDraft("s1", any())
            storyRepository.deleteChapter("c2")
        }
    }

    @Test
    fun E2_重写准备_存的快照就是被删那一章() = runBlocking {
        val ch2 = chapterRow("c2", 2, content = "被重写掉的正文")
        coEvery { storyRepository.getChapterMetaBefore("s1", 2) } returns chapterRow("c1", 1)
        val jsonSlot = slot<String>()
        coEvery { storyRepository.setPendingRewriteDraft("s1", capture(jsonSlot)) } just Runs

        materializer.prepareRewrite(story(), latestChapter = ch2, instruction = null, nowMillis = 5L)

        assertEquals(
            "接力棒里装的必须是被删那一章的 12 个内容字段",
            StoryChapterDraft.fromEntity(ch2),
            StoryChapterDraft.decode(jsonSlot.captured),
        )
    }

    @Test
    fun E2_重写产物_挂槽并清中转位() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        val pending = StoryChapterDraft.encode(StoryChapterDraft.fromEntity(chapterRow("c2", 2, content = "旧正文")))
        val s = story(rewriteInstruction = "改温柔点", pendingRewriteDraftJson = pending)

        materializer.materializeChapter(payload(), chapterNumber = 2, story = s, nowMillis = 1L)

        assertEquals("重写产物必须带着重写前那一版", pending, slot.captured.previousDraftJson)
        coVerify(exactly = 1) { storyRepository.clearPendingRewriteDraft("s1") }
    }

    /** 挂槽发生在插章的同一次写里（不是插完再补一条 UPDATE）——中途死掉不会留下「有章无槽」的半成品。 */
    @Test
    fun E2_挂槽随插章一次写入_清中转位在插章之后() = runBlocking {
        val pending = """{"title":"旧标题","content":"旧正文"}"""
        val s = story(rewriteInstruction = "", pendingRewriteDraftJson = pending)

        materializer.materializeChapter(payload(), chapterNumber = 2, story = s, nowMillis = 1L)

        coVerifyOrder {
            storyRepository.insertChapter(match { it.previousDraftJson == pending })
            storyRepository.clearPendingRewriteDraft("s1")
        }
    }

    /** 重写无附加指令时 rewriteInstruction 是**空串**（prepareRewrite 的 `?: ""`）——空串同样算「重写中」。 */
    @Test
    fun E2_空串重写指令也算重写中() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        val pending = """{"title":"旧标题"}"""

        materializer.materializeChapter(
            payload(), chapterNumber = 2,
            story = story(rewriteInstruction = "", pendingRewriteDraftJson = pending), nowMillis = 1L,
        )

        assertEquals(pending, slot.captured.previousDraftJson)
    }

    // ── E4 / E8 ──

    /**
     * E4：重写生成失败 → 用户改点「继续推进」同章号重生成。ST11「意图保留」使 rewriteInstruction 仍非 null，
     * 快照也还在中转位 → 新章照常挂槽（旧稿不因为一次失败而丢）。
     */
    @Test
    fun E4_重写失败后改手动推进_快照仍挂上() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        val pending = """{"title":"重写前的那一版"}"""
        // 失败路只写 status，不清 rewriteInstruction / 不清中转位（ST11 拍板①）。
        val s = story(rewriteInstruction = "改温柔点", pendingRewriteDraftJson = pending)

        materializer.materializeChapter(payload(), chapterNumber = 2, story = s, nowMillis = 9L)

        assertEquals("一次失败不该让旧稿丢失", pending, slot.captured.previousDraftJson)
    }

    /**
     * E8：连续重写两次（中间没换回）→ 中转位被第二次的「重写前稿」覆盖，槽恒存**最近一次重写前**的那一版。
     * 这是单槽语义的直接后果，用户已在过审时知悉（更早的那版丢弃属预期）。
     */
    @Test
    fun E8_连续两次重写_中转位存最近一次重写前的稿() = runBlocking {
        val first = chapterRow("c2", 2, content = "第一次重写前的正文")
        val second = chapterRow("c3", 2, content = "第二次重写前的正文")
        coEvery { storyRepository.getChapterMetaBefore("s1", 2) } returns chapterRow("c1", 1)
        val jsonSlot = mutableListOf<String?>()
        coEvery { storyRepository.setPendingRewriteDraft("s1", captureNullable(jsonSlot)) } just Runs

        materializer.prepareRewrite(story(), latestChapter = first, instruction = null, nowMillis = 1L)
        materializer.prepareRewrite(story(rewriteInstruction = ""), latestChapter = second, instruction = null, nowMillis = 2L)

        assertEquals("两次重写各存一次快照", 2, jsonSlot.size)
        assertEquals(
            "槽里最终是第二次重写前的那一版",
            StoryChapterDraft.fromEntity(second),
            StoryChapterDraft.decode(jsonSlot.last()),
        )
    }

    /** 回归钉：新增的两步写不许挤掉 prepareRewrite 既有六步语义（删章 / 恢复摘要 / 复位 serializing / 清 beats）。 */
    @Test
    fun 重写准备_既有语义零回归() = runBlocking {
        val ch2 = chapterRow("c2", 2)
        coEvery { storyRepository.getChapterMetaBefore("s1", 2) } returns chapterRow("c1", 1)

        materializer.prepareRewrite(story(), latestChapter = ch2, instruction = "改温柔点", nowMillis = 5L)

        coVerify { storyRepository.deleteChapter("c2") }
        coVerify {
            storyRepository.updateRewriteState(
                id = "s1", storyBible = any(),
                storySummary = "第1章摘要",
                rewriteInstruction = "改温柔点",
                status = StoryStatus.SERIALIZING,
                pendingChapterBeats = null,
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 5L,
            )
        }
        coVerify { storyRepository.refreshChapterCaches("s1", any()) }
    }
}
