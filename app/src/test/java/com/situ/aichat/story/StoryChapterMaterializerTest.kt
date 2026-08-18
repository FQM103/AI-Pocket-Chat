package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.dao.StoryChapterCacheRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * StoryChapterMaterializer 行为测试——验证刀2 章节落库器协作者「真的能用」（不止编译过·DB 写路径）。
 *
 * 手法：MockK 假掉 StoryRepository（slot 捕获写入实体 + 命名实参 coVerify 钉死定向写参数）；
 * StoryGenerationPolicy/Parsing/TextCleaning 真跑（纯逻辑·不 mock）→ 端到端验落库编排正确。
 * Log.d 经 mockkStatic 假掉（纯 JVM 无 android.util.Log）。
 * 覆盖 materializeChapter（插章字段/chase 解锁/字段降级沿用/已压缩摘要保留/状态机+圣经追加接线）
 * 与 prepareRewrite（删最新章+恢复上一章摘要/无上一章回落故事摘要/null 指令→空串）。
 */
class StoryChapterMaterializerTest {

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
        id: String = "s1",
        updateMode: String = StoryUpdateMode.FREE,
        storySummary: String? = "旧摘要",
        currentArc: String? = "旧弧线",
        characterStates: String? = "旧状态",
        openThreads: String? = "旧伏笔",
        storyBible: String? = "旧圣经",
        lastCompressedAtChapter: Int? = null,
        maxChapters: Int? = null,
        autoExtendCount: Int = 0,
    ) = StoryEntity(
        id = id, title = "故事", updateMode = updateMode,
        storySummary = storySummary, currentArc = currentArc, characterStates = characterStates,
        openThreads = openThreads, storyBible = storyBible, lastCompressedAtChapter = lastCompressedAtChapter,
        maxChapters = maxChapters, autoExtendCount = autoExtendCount,
    )

    private fun payload(
        title: String = "第一章",
        mood: String = "warm",
        content: String = "正文内容",
        hasChoice: Boolean = true,
        choiceOptions: List<String>? = listOf("A", "B"),
        summary: String? = "新摘要",
        currentArc: String? = "新弧线",
        characterStates: String? = "新状态",
        openThreads: String? = "新伏笔",
        nextChapterBeats: String? = "下章方向",
        isEnding: Boolean? = false,
    ) = StoryChapterPayload(
        title = title, teaser = "引子", mood = mood, content = content, hasChoice = hasChoice,
        choicePrompt = "你决定", choiceOptions = choiceOptions, summary = summary, currentArc = currentArc,
        isEnding = isEnding, characterStates = characterStates, openThreads = openThreads, nextChapterBeats = nextChapterBeats,
    )

    private fun chapterRow(id: String, number: Int, summary: String) =
        StoryChapterEntity(id = id, storyId = "s1", chapterNumber = number, title = "第${number}章", chapterSummary = summary)

    // ---- materializeChapter ----

    @Test
    fun 剥净后正文为空_抛EmptyResponse不落空章() = runBlocking {
        // 落库口空稿守卫（2026-07-11）：纯思考/思考中途截断的 content 剥净后为空——抛给生成失败路重试，绝不插空章。
        var threw = false
        try {
            materializer.materializeChapter(payload(content = "<think>纯思考被截断没有正文"), 1, story(), 1_000L)
        } catch (e: StoryGenerationError) {
            threw = e === StoryGenerationError.EmptyResponse
        }
        assertEquals(true, threw)
        coVerify(exactly = 0) { storyRepository.insertChapter(any()) }
    }

    @Test
    fun 落库_插章字段逐项映射_非chase无解锁时间() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        val s = story(updateMode = StoryUpdateMode.FREE)
        val p = payload()

        materializer.materializeChapter(p, chapterNumber = 1, story = s, nowMillis = 1_000L)

        val ch = slot.captured
        assertEquals("s1", ch.storyId)
        assertEquals(1, ch.chapterNumber)
        assertEquals("第一章", ch.title)
        assertEquals("引子", ch.teaser)
        assertEquals("正文内容", ch.content)
        assertEquals("warm", ch.mood)
        assertEquals(true, ch.hasChoice)
        assertEquals("你决定", ch.choicePrompt)
        assertEquals(StoryGenerationParsing.encodeChoiceOptions(listOf("A", "B")), ch.choiceOptions)
        assertEquals("新摘要", ch.chapterSummary)        // payload.summary 非空 → 用之
        assertEquals(1_000L, ch.createdAt)               // 注入 nowMillis
        assertNull(ch.unlockAt)                          // 非 chase → 无解锁时间
        coVerify { storyRepository.refreshChapterCaches("s1", any()) }
    }

    @Test
    fun 落库_chase模式算解锁时间() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        val s = story(updateMode = StoryUpdateMode.CHASE)

        materializer.materializeChapter(payload(), chapterNumber = 1, story = s, nowMillis = 1_000L)

        // chase 分支：解锁时间 = Policy.computeUnlockAt(now, 默认 unlockHour=20/Minute=0)
        assertEquals(StoryGenerationPolicy.computeUnlockAt(1_000L, s.unlockHour, s.unlockMinute), slot.captured.unlockAt)
        assertNotNull(slot.captured.unlockAt)
    }

    @Test
    fun 落库_叙事字段写入_状态机与圣经追加接线正确() = runBlocking {
        val s = story()
        val p = payload()
        val expected = StoryGenerationPolicy.decideStatus(
            requestedEndingType = s.requestedEndingType, hasChoice = p.hasChoice,
        )
        val expectedBible = (s.storyBible ?: "") +
            StoryGenerationPolicy.buildBibleAppendix(1, p.characterStates, p.openThreads)

        materializer.materializeChapter(p, chapterNumber = 1, story = s, nowMillis = 1_000L)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = "新摘要", currentArc = "新弧线", characterStates = "新状态",
                openThreads = "新伏笔", pendingChapterBeats = "下章方向", storyBible = expectedBible,
                // 卷二·单模式化：两列不再由状态机改动，materialize 对它们等值重写。
                status = expected.status, maxChapters = s.maxChapters, autoExtendCount = s.autoExtendCount,
                requestedEndingType = any(), requestedEndingDetail = any(),
                rewriteInstruction = null,           // 本章生成完成即清空重写指令
                finalEndingType = any(),
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 1_000L,
            )
        }
    }

    @Test
    fun 落库_payload字段缺失则沿用上一章_beats清空() = runBlocking {
        val s = story()  // 旧摘要/旧弧线/旧状态/旧伏笔
        val p = payload(summary = null, currentArc = null, characterStates = null, openThreads = null, nextChapterBeats = null)

        materializer.materializeChapter(p, chapterNumber = 2, story = s, nowMillis = 2_000L)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = "旧摘要", currentArc = "旧弧线", characterStates = "旧状态",
                openThreads = "旧伏笔",
                pendingChapterBeats = null,          // nextChapterBeats 缺失 → 清空
                storyBible = any(), status = any(), maxChapters = any(), autoExtendCount = any(),
                requestedEndingType = any(), requestedEndingDetail = any(), rewriteInstruction = null,
                finalEndingType = any(), intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 2_000L,
            )
        }
    }

    @Test
    fun 落库_已压缩过的故事保留压缩摘要不被本章覆盖() = runBlocking {
        val s = story(lastCompressedAtChapter = 3, storySummary = "压缩摘要")
        val p = payload(summary = "本章摘要")  // 即便 payload 有 summary

        materializer.materializeChapter(p, chapterNumber = 4, story = s, nowMillis = 1L)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = "压缩摘要",   // lastCompressed!=null → 沿用故事压缩摘要，不取 payload.summary
                currentArc = any(), characterStates = any(), openThreads = any(), pendingChapterBeats = any(),
                storyBible = any(), status = any(), maxChapters = any(), autoExtendCount = any(),
                requestedEndingType = any(), requestedEndingDetail = any(), rewriteInstruction = any(),
                finalEndingType = any(), intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = any(),
            )
        }
    }

    @Test
    fun 落库_用户请求结局完结_finalEndingType定格用户类型() = runBlocking {
        // 用户在阅读器请求「自定义结局」→ requestedEndingType 非空 → decideStatus 判 COMPLETED 且清空一次性字段。
        val s = story().copy(requestedEndingType = StoryEndingType.CUSTOM, requestedEndingDetail = "圆满")
        materializer.materializeChapter(payload(), chapterNumber = 5, story = s, nowMillis = 1L)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = any(), currentArc = any(), characterStates = any(),
                openThreads = any(), pendingChapterBeats = any(), storyBible = any(),
                status = StoryStatus.COMPLETED,
                maxChapters = any(), autoExtendCount = any(),
                requestedEndingType = null, requestedEndingDetail = null,  // 一次性字段完结即清空
                rewriteInstruction = null,
                finalEndingType = StoryEndingType.CUSTOM,                  // ← 持久列定格用户所选类型
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 1L,
            )
        }
    }

    /**
     * **卷二·单模式化的看门狗**（原例 `落库_满章封顶自然完结无用户请求_finalEndingType保持null` 的替身——
     * 它钉的「满章封顶 → COMPLETED」这条路已随有限模式整体退役）。
     *
     * 期望从图纸 J9 独立反推：落库状态机里 **COMPLETED 只剩「用户请求结局」一条路**。即便故事上残留着
     * 老的 maxChapters/autoExtendCount 脏数据（迁移会归一化，但脏数据防御照做），也一律不许完结。
     */
    @Test
    fun 落库_残留满章脏数据不再触发完结() = runBlocking {
        val s = story(maxChapters = 10, autoExtendCount = 3)  // requestedEndingType 为 null
        val p = payload(hasChoice = false)

        materializer.materializeChapter(p, chapterNumber = 10, story = s, nowMillis = 1L)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = any(), currentArc = any(), characterStates = any(),
                openThreads = any(), pendingChapterBeats = any(), storyBible = any(),
                status = StoryStatus.SERIALIZING,  // ← 不再完结：书照常连载，收尾交给终章弧/归档
                // 两列由 materialize 等值重写（不再由状态机改动）——脏值原样带过、不被「+10 扩容」放大。
                maxChapters = 10, autoExtendCount = 3,
                requestedEndingType = any(), requestedEndingDetail = any(), rewriteInstruction = any(),
                finalEndingType = null,
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 1L,
            )
        }
    }

    // ── ST11 拍板②：AI 自标结局降级为「建议」（isEnding 不再决定状态，只落章印） ──
    // 期望独立反推自图纸 §3.1 判定链表 + §3.2 印的生命周期。

    @Test
    fun ST11_AI自标结局无选项_书照常连载不完结() = runBlocking {
        val s = story()  // 无用户请求结局、无满章封顶
        val p = payload(hasChoice = false, isEnding = true)  // AI 说「写完了」

        materializer.materializeChapter(p, chapterNumber = 3, story = s, nowMillis = 1L)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = any(), currentArc = any(), characterStates = any(),
                openThreads = any(), pendingChapterBeats = any(), storyBible = any(),
                status = StoryStatus.SERIALIZING,  // ← 拍板②：AI 说了不算，书还连载着等用户盖章
                maxChapters = any(), autoExtendCount = any(),
                requestedEndingType = any(), requestedEndingDetail = any(), rewriteInstruction = any(),
                finalEndingType = null, intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 1L,
            )
        }
    }

    @Test
    fun ST11_AI自标结局_章上盖印() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs

        materializer.materializeChapter(payload(isEnding = true), chapterNumber = 3, story = story(), nowMillis = 1L)

        assertEquals("payload.isEnding=true → 章上盖 aiSuggestedEnding 印（供阅读器建议卡）", true, slot.captured.aiSuggestedEnding)
    }

    @Test
    fun ST11_AI未标结局_章上无印() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs

        materializer.materializeChapter(payload(isEnding = false), chapterNumber = 3, story = story(), nowMillis = 1L)

        assertEquals("payload.isEnding=false → 无印", false, slot.captured.aiSuggestedEnding)
    }

    @Test
    fun ST11_isEnding缺失_章上无印() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        // LLM 没输出 isEnding 字段（解析得 null）→ `payload.isEnding == true` 为假 → 无印（不许把 null 当真）。
        materializer.materializeChapter(payload(isEnding = null), chapterNumber = 3, story = story(), nowMillis = 1L)

        assertEquals("payload.isEnding=null → 无印", false, slot.captured.aiSuggestedEnding)
    }

    // ── 幽灵选择清洗（ST10-4·微图纸 2026-07-17）：完结路落库的章不许携带未答选择 ──
    // 期望独立反推自缺陷现象：完结书重读末章，幽灵选择可点 → commitUserChoice 把书拉回连载中。

    @Test
    fun 清洗_用户请求结局_LLM违规带选择_章选择字段全清() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        val s = story().copy(requestedEndingType = StoryEndingType.AI)
        // LLM 违抗「结局章 hasChoice 必须 false」的提示词要求，仍给出选择
        materializer.materializeChapter(payload(hasChoice = true, isEnding = false), chapterNumber = 5, story = s, nowMillis = 1L)

        val ch = slot.captured
        assertEquals(false, ch.hasChoice)
        assertNull(ch.choicePrompt)
        assertNull(ch.choiceOptions)
    }

    @Test
    fun ST11_矛盾输出_AI自标结局却带选择_选项保留且印在() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        // 图纸 §3.1 矛盾输出新口径（**取代** ST10-4 的「信 isEnding → 完结 → 清选择」）：
        // isEnding=true + hasChoice=true → 书 WAITING_CHOICE、选项保留、章上仍盖印
        // （建议卡与选择区并存·§3.4）。清洗条件挂 decision.status==COMPLETED，此路不完结故不清洗。
        materializer.materializeChapter(payload(hasChoice = true, isEnding = true), chapterNumber = 3, story = story(), nowMillis = 1L)

        val ch = slot.captured
        assertEquals("选项必须保留（用户还能选）", true, ch.hasChoice)
        assertEquals("选择提示原样落库", "你决定", ch.choicePrompt)
        assertNotNull("选项 JSON 原样落库", ch.choiceOptions)
        assertEquals("章上仍盖 AI 建议结局的印", true, ch.aiSuggestedEnding)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = any(), currentArc = any(), characterStates = any(),
                openThreads = any(), pendingChapterBeats = any(), storyBible = any(),
                status = StoryStatus.WAITING_CHOICE,  // ← 不完结，等用户选
                maxChapters = any(), autoExtendCount = any(),
                requestedEndingType = any(), requestedEndingDetail = any(), rewriteInstruction = any(),
                finalEndingType = any(), intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = any(),
            )
        }
    }

    /**
     * 原例 `清洗_满章封顶自动扩展用尽完结_章选择字段全清` 的替身：那条完结路已随有限模式退役，
     * 幽灵选择清洗的**唯一**完结路（用户请求结局）已由上面的 `清洗_用户请求结局_LLM违规带选择_章选择字段全清` 覆盖。
     * 此处补反向钉：残留满章脏数据不再触发清洗——因为它压根不再是完结路（选择字段原样保留）。
     */
    @Test
    fun 清洗_残留满章脏数据不再当作完结路_选择字段原样保留() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        val s = story(maxChapters = 10, autoExtendCount = 3)
        materializer.materializeChapter(payload(hasChoice = true), chapterNumber = 10, story = s, nowMillis = 1L)

        val ch = slot.captured
        assertEquals(true, ch.hasChoice)
        assertEquals("你决定", ch.choicePrompt)
        assertEquals(StoryGenerationParsing.encodeChoiceOptions(listOf("A", "B")), ch.choiceOptions)
    }

    @Test
    fun 清洗_未完结章_选择字段原样落库回归钉() = runBlocking {
        val slot = slot<StoryChapterEntity>()
        coEvery { storyRepository.insertChapter(capture(slot)) } just Runs
        // 普通续章（waitingChoice 路）：清洗绝不误伤，三字段与 payload 逐项一致
        materializer.materializeChapter(payload(hasChoice = true), chapterNumber = 2, story = story(), nowMillis = 1L)

        val ch = slot.captured
        assertEquals(true, ch.hasChoice)
        assertEquals("你决定", ch.choicePrompt)
        assertEquals(StoryGenerationParsing.encodeChoiceOptions(listOf("A", "B")), ch.choiceOptions)
    }

    @Test
    fun 落库_普通章未完结_finalEndingType保持原值() = runBlocking {
        val s = story()  // hasChoice=true → waitingChoice（非 COMPLETED）
        materializer.materializeChapter(payload(), chapterNumber = 1, story = s, nowMillis = 1L)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = any(), currentArc = any(), characterStates = any(),
                openThreads = any(), pendingChapterBeats = any(), storyBible = any(),
                status = any(), maxChapters = any(), autoExtendCount = any(),
                requestedEndingType = any(), requestedEndingDetail = any(), rewriteInstruction = any(),
                finalEndingType = null,  // 未完结 → 不定格、保持原值
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 1L,
            )
        }
    }

    @Test
    fun 续篇后再次请求结局_新类型快照覆盖旧finalEndingType() = runBlocking {
        // R1 🟡-2 例②：续篇里用户再次请求结局 → 即便残留 stale finalEndingType，本次请求类型也应快照覆盖
        val s = story().copy(
            requestedEndingType = StoryEndingType.CUSTOM, requestedEndingDetail = "圆满",
            finalEndingType = StoryEndingType.AI,  // 上一次遗留的旧徽章
        )
        materializer.materializeChapter(payload(), chapterNumber = 6, story = s, nowMillis = 2L)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = any(), currentArc = any(), characterStates = any(),
                openThreads = any(), pendingChapterBeats = any(), storyBible = any(),
                status = StoryStatus.COMPLETED, maxChapters = any(), autoExtendCount = any(),
                requestedEndingType = null, requestedEndingDetail = null, rewriteInstruction = null,
                finalEndingType = StoryEndingType.CUSTOM,  // ← 新请求类型覆盖旧 ai
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 2L,
            )
        }
    }

    @Test
    fun 重写末章_未完结保留非空finalEndingType_徽章回归锁() = runBlocking {
        // R1 🟡-2 例③（回归锁）：重写末章路 updateRewriteState 不碰 finalEndingType（保留 custom），
        // 重写出的新章**没有**新结局请求 → else-保留必须留住徽章（此路正确、不可被 🟡-2 续篇清列误伤）。
        // 卷二只换载具：原用「满章封顶自然完结」当无请求场景，该完结路已随有限模式退役 → 改用普通续章；
        // 被钉的性质（else-保留留住徽章）与断言一字未动。
        val s = story().copy(finalEndingType = StoryEndingType.CUSTOM)  // requestedEndingType 仍 null
        val p = payload(hasChoice = false)                              // 无用户新请求 → 不完结

        materializer.materializeChapter(p, chapterNumber = 10, story = s, nowMillis = 3L)

        coVerify {
            storyRepository.updateNarrativeState(
                id = "s1", storySummary = any(), currentArc = any(), characterStates = any(),
                openThreads = any(), pendingChapterBeats = any(), storyBible = any(),
                status = StoryStatus.SERIALIZING, maxChapters = any(), autoExtendCount = any(),
                requestedEndingType = any(), requestedEndingDetail = any(), rewriteInstruction = any(),
                finalEndingType = StoryEndingType.CUSTOM,  // ← 保留（else-preserve 支撑重写末章徽章）
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 3L,
            )
        }
    }

    // ---- prepareRewrite ----

    @Test
    fun 重写准备_删最新章并以上一章摘要恢复() = runBlocking {
        val s = story(storyBible = "圣经")
        val ch1 = chapterRow("c1", 1, "第一章摘要")
        val ch2 = chapterRow("c2", 2, "第二章摘要")
        coEvery { storyRepository.getChapterMetaBefore("s1", 2) } returns ch1

        materializer.prepareRewrite(s, latestChapter = ch2, instruction = "改温柔点", nowMillis = 5_000L)

        coVerify { storyRepository.deleteChapter("c2") }                  // 删最新章
        coVerify {
            storyRepository.updateRewriteState(
                id = "s1", storyBible = any(),
                storySummary = "第一章摘要",        // 用上一章摘要恢复
                rewriteInstruction = "改温柔点",
                status = StoryStatus.SERIALIZING,   // 复位 serializing
                pendingChapterBeats = null,         // 清空
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 5_000L,
            )
        }
        coVerify { storyRepository.refreshChapterCaches("s1", any()) }
    }

    @Test
    fun 重写准备_删首章时回落故事自身摘要() = runBlocking {
        val s = story(storySummary = "故事摘要")
        val only = chapterRow("c1", 1, "第一章摘要")
        coEvery { storyRepository.getChapterMetaBefore("s1", 1) } returns null

        materializer.prepareRewrite(s, latestChapter = only, instruction = "重来", nowMillis = 1L)

        coVerify { storyRepository.deleteChapter("c1") }
        coVerify {
            storyRepository.updateRewriteState(
                id = "s1", storyBible = any(),
                storySummary = "故事摘要",          // 无上一章 → 回落 story.storySummary
                rewriteInstruction = "重来", status = StoryStatus.SERIALIZING, pendingChapterBeats = null, intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 1L,
            )
        }
    }

    /**
     * 卷二 T2-2（图纸 §5 E2）：章号有洞（1,3,7）时重写第 7 章——上一章必须是**升序列表的前一项**（章 3），
     * 不是「章号-1」（章 6 根本不存在）。既验取法参数、也验取回来的那一章真的驱动了摘要恢复与缓存刷新。
     */
    @Test
    fun 重写准备_章号有洞_上一章按升序前一项取() = runBlocking {
        val s = story(storySummary = "故事级旧摘要")
        val third = StoryChapterEntity(
            id = "c3", storyId = "s1", chapterNumber = 3, title = "第三章",
            createdAt = 3_333L, hasChoice = true, userChoice = "选了 B", chapterSummary = "第三章摘要",
        )
        val seventh = chapterRow("c7", 7, "第七章摘要")
        coEvery { storyRepository.getChapterMetaBefore("s1", 7) } returns third

        materializer.prepareRewrite(s, latestChapter = seventh, instruction = "重来", nowMillis = 7_000L)

        // 取法：以「本章章号」为界往前取一条（不是 6、不是 0）。
        coVerify(exactly = 1) { storyRepository.getChapterMetaBefore("s1", 7) }
        coVerify(exactly = 0) { storyRepository.getChapterMetaBefore("s1", 6) }
        coVerify { storyRepository.deleteChapter("c7") }
        coVerify {
            storyRepository.updateRewriteState(
                id = "s1", storyBible = any(),
                storySummary = "第三章摘要",        // 上一章（章 3）的摘要，而非故事级旧摘要
                rewriteInstruction = "重来", status = StoryStatus.SERIALIZING, pendingChapterBeats = null,
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(), updatedAt = 7_000L,
            )
        }
        // explicitLatest = 章 3 的五字段缓存投影（书架卡片的「最新章」回退到章 3）。
        coVerify {
            storyRepository.refreshChapterCaches(
                "s1",
                StoryChapterCacheRow(
                    chapterNumber = 3, title = "第三章", createdAt = 3_333L,
                    hasChoice = true, userChoice = "选了 B",
                ),
            )
        }
    }

    /** 卷二 T2-2（E3）：重写首章——查询返回 null → previous=null → 摘要回落故事级 + 缓存无 explicitLatest。 */
    @Test
    fun 重写准备_首章无上一章_走既有null分支() = runBlocking {
        val s = story(storySummary = "故事级旧摘要")
        val first = chapterRow("c1", 1, "第一章摘要")
        coEvery { storyRepository.getChapterMetaBefore("s1", 1) } returns null

        materializer.prepareRewrite(s, latestChapter = first, instruction = null, nowMillis = 1_000L)

        coVerify {
            storyRepository.updateRewriteState(
                id = "s1", storyBible = any(),
                storySummary = "故事级旧摘要",
                rewriteInstruction = "", status = StoryStatus.SERIALIZING, pendingChapterBeats = null,
                intimacyLedger = any(), sceneState = any(), sceneLedger = any(), updatedAt = 1_000L,
            )
        }
        coVerify { storyRepository.refreshChapterCaches("s1", null) }
    }

    @Test
    fun 重写准备_null指令落空串() = runBlocking {
        val s = story()
        val only = chapterRow("c1", 1, "摘要")
        coEvery { storyRepository.getChapterMetaBefore("s1", 1) } returns null

        materializer.prepareRewrite(s, latestChapter = only, instruction = null, nowMillis = 1L)

        coVerify {
            storyRepository.updateRewriteState(
                id = "s1", storyBible = any(), storySummary = any(),
                rewriteInstruction = "",            // null → 空串（有重写无附加文）
                status = StoryStatus.SERIALIZING, pendingChapterBeats = null, intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = 1L,
            )
        }
    }
}
