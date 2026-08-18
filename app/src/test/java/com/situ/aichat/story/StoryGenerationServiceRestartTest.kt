package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「重新开始新故事」（`restartStory`）行为测试（T2·MockK）。
 *
 * 修复背景：旧实现照已退役的 iOS 1:1 用白名单只复制部分字段，静默丢了 **人称 / 追更模式与解锁时间 / 世界书开关 /
 * 自定义提示词** 四类用户创作设定（重开后退回默认值）。改为 copy-then-reset 后，重开应=**保留全部创作设定、只清叙事进度**。
 *
 * 这两条测试从「规格反推」断言（非照搬实现）：
 * - 例① 所有创作设定字段随新书保留（尤其修复的四类给非默认值以证明确实带过去），叙事进度/缓存/一次性字段全部清零。
 * - 例② 角色随**新** storyId 复制，isUserRole / characterId 等保真。
 */
class StoryGenerationServiceRestartTest {

    private lateinit var storyRepository: StoryRepository
    private lateinit var service: StoryGenerationService

    @Before
    fun setUp() {
        storyRepository = mockk()
        service = StoryGenerationService(
            llmClient = mockk(),
            contextLog = mockk(relaxed = true),
            storyRepository = storyRepository,
            apiConfigRepository = mockk(),
            apiFunctionRouter = mockk(),
            storyChatInfluenceBuilder = mockk(),
            storyCharacterDataCollector = mockk(),
            storyChapterMaterializer = mockk(),
            storyPayloadResolver = mockk(),
            storyWorldInfoService = mockk(),
            settingsRepository = mockk(),
            // 大纲编排外搬（卷二 C4 文件瘦身）：本组用例不碰大纲面，桩成**恒等直通**——
            // 与外搬前「无需生成大纲时 ensureOutline 原样返回入参」的行为一致，后续步骤照旧拿到同一个故事。
            storyOutlineOrchestrator = mockk<StoryOutlineOrchestrator>().also { orchestrator ->
                coEvery { orchestrator.ensureOutline(any(), any(), any(), any()) } answers { firstArg() }
            },
            storyCompressionCoordinator = mockk(relaxed = true),
        )
        coEvery { storyRepository.insertStory(any()) } just Runs
        coEvery { storyRepository.insertRoles(any()) } just Runs
    }

    @Test
    fun `重开_保留全部创作设定_清空叙事进度`() = runBlocking {
        coEvery { storyRepository.getRoles("orig") } returns emptyList()
        // 一部已完结、创作设定全为「非默认」、叙事进度写满的原作
        val original = StoryEntity(
            id = "orig",
            title = "原作",
            status = StoryStatus.COMPLETED,
            // ── 创作设定（应全部随新书保留）──
            genre = "武侠",
            coverColorScheme = "ink",
            worldSetting = "架空江湖",
            plotDirection = "复仇",
            writingStyle = "古风",
            chapterLengthPreference = 3000,
            maxChapters = 20,
            chatInfluenceWeight = StoryChatInfluenceWeight.HEAVY,
            // ↓ 四类此前被静默丢掉、本次修复的字段（给非默认值才能证明确实带过去）
            narrativePerson = StoryNarrativePerson.FIRST,        // 默认 SECOND
            updateMode = StoryUpdateMode.CHASE,                  // 默认 FREE
            unlockHour = 8,                                      // 默认 20
            unlockMinute = 30,                                  // 默认 0
            worldInfoEnabled = false,                            // 默认 true
            customPromptsJson = """{"genre":"自定义题材钉基调"}""", // 默认 null
            // ── 叙事进度 / 运行时状态（应全部清零）──
            autoExtendCount = 2,
            storySummary = "前情摘要",
            currentArc = "第三弧",
            characterStates = "主角=重伤",
            openThreads = "神秘玉佩未回收",
            storyBible = "第1章…第20章逐章流水账",
            lastCompressedAtChapter = 10,
            lastBibleCompressedAtChapter = 12,
            storyOutline = "里程碑大纲",
            pendingChapterBeats = "下一章方向",
            pendingBeatsUserEdited = true,
            currentArcStartChapter = 15,
            // ↓ 卷二 B2/J1 三列此前漏列（故事二期卷一 J1 顺手修）：弧线简史 + 预约的收尾计划
            arcHistory = "第1–12章·初入江湖\n第13–20章·血仇",
            finaleEndingType = StoryEndingType.CUSTOM,
            finaleEndingDetail = "归隐山林",
            // ↓ 故事二期卷一账本族三件
            intimacyLedger = "【里程碑】第3章·定情\n\n【相处近况】第19章·并肩夜行",
            sceneState = "客栈厢房｜两人相拥",
            sceneLedger = "第7章·雨夜·马车\n第19章·客栈·厢房",
            // ── 一次性字段 / 结局快照（应清零）──
            requestedEndingType = StoryEndingType.CUSTOM,
            requestedEndingDetail = "主角归隐",
            rewriteInstruction = "重写末章",
            finalEndingType = StoryEndingType.CUSTOM,
            // ── 缓存字段（应清零）──
            cachedChapterCount = 20,
            cachedLatestChapterNumber = 20,
            cachedLatestChapterTitle = "第二十章",
            cachedLatestChapterCreatedAt = 999L,
            cachedHasPendingChoice = true,
        )

        val copy = service.restartStory(original, nowMillis = 7_000L)

        // ── 修复核心：四类此前被丢的创作设定，随新书原样保留 ──
        assertEquals(StoryNarrativePerson.FIRST, copy.narrativePerson)
        assertEquals(StoryUpdateMode.CHASE, copy.updateMode)
        assertEquals(8, copy.unlockHour)
        assertEquals(30, copy.unlockMinute)
        assertFalse(copy.worldInfoEnabled)
        assertEquals("""{"genre":"自定义题材钉基调"}""", copy.customPromptsJson)

        // ── 其余创作设定同样保留 ──
        assertEquals("武侠", copy.genre)
        assertEquals("ink", copy.coverColorScheme)
        assertEquals("架空江湖", copy.worldSetting)
        assertEquals("复仇", copy.plotDirection)
        assertEquals("古风", copy.writingStyle)
        assertEquals(3000, copy.chapterLengthPreference)
        assertEquals(20, copy.maxChapters)
        assertEquals(StoryChatInfluenceWeight.HEAVY, copy.chatInfluenceWeight)

        // ── 新书身份：新 id、标题加后缀、状态归连载中、时间戳取注入值 ──
        assertNotEquals("orig", copy.id)
        assertTrue(copy.id.isNotBlank())
        assertEquals("原作·重开", copy.title)
        assertEquals(StoryStatus.SERIALIZING, copy.status)
        assertEquals(7_000L, copy.createdAt)
        assertEquals(7_000L, copy.updatedAt)

        // ── 叙事进度 / 运行时状态：全部清零 ──
        assertEquals(0, copy.autoExtendCount)
        assertNull(copy.storySummary)
        assertNull(copy.currentArc)
        assertNull(copy.characterStates)
        assertNull(copy.openThreads)
        assertNull(copy.storyBible)
        assertNull(copy.lastCompressedAtChapter)
        assertNull(copy.lastBibleCompressedAtChapter)
        assertNull(copy.storyOutline)
        assertNull(copy.pendingChapterBeats)
        assertFalse("节拍「用户改过」标志随 beats 一起清零", copy.pendingBeatsUserEdited)
        assertNull(copy.currentArcStartChapter)

        // ── 卷二 B2/J1 三列（此前漏列的真 bug）：弧线简史属上一本书的进度；
        //    残留的收尾计划更致命——新书第一章就会被判成终章弧、直接写收尾 ──
        assertNull("重开的书不该带着上一本的弧线简史", copy.arcHistory)
        assertNull("重开的书不该带着上一本预约的收尾计划", copy.finaleEndingType)
        assertNull("收尾方向随收尾计划一起清", copy.finaleEndingDetail)

        // ── 故事二期卷一账本族三件：写过什么/停在哪都属上一本书 ──
        assertNull("关系史账本不跨书", copy.intimacyLedger)
        assertNull("场景状态快照不跨书", copy.sceneState)
        assertNull("场景台账不跨书", copy.sceneLedger)

        // ── 一次性字段 / 结局快照：清零 ──
        assertNull(copy.requestedEndingType)
        assertNull(copy.requestedEndingDetail)
        assertNull(copy.rewriteInstruction)
        assertNull(copy.finalEndingType)

        // ── 缓存字段：清零 ──
        assertEquals(0, copy.cachedChapterCount)
        assertNull(copy.cachedLatestChapterNumber)
        assertNull(copy.cachedLatestChapterTitle)
        assertNull(copy.cachedLatestChapterCreatedAt)
        assertFalse(copy.cachedHasPendingChoice)

        // 返回值即落库那一行（钉死持久化的就是这份新书）
        val persisted = slot<StoryEntity>()
        coVerify { storyRepository.insertStory(capture(persisted)) }
        assertEquals(copy, persisted.captured)
    }

    @Test
    fun `重开_角色随新storyId复制且保真`() = runBlocking {
        coEvery { storyRepository.getRoles("orig") } returns listOf(
            StoryCharacterRoleEntity(
                id = "roleA", storyId = "orig", roleName = "我", roleType = StoryRoleType.PROTAGONIST,
                roleDescription = "少年侠客", isUserRole = true, characterId = "char-1",
                intimatePersona = "人前正经，私下极黏人",
            ),
            StoryCharacterRoleEntity(
                id = "roleB", storyId = "orig", roleName = "师父", roleType = StoryRoleType.SUPPORTING,
                roleDescription = null, isUserRole = false, characterId = null,
            ),
        )
        val original = StoryEntity(id = "orig", title = "原作", status = StoryStatus.COMPLETED)

        val copy = service.restartStory(original, nowMillis = 8_000L)

        val roles = slot<List<StoryCharacterRoleEntity>>()
        coVerify { storyRepository.insertRoles(capture(roles)) }
        val copied = roles.captured
        assertEquals(2, copied.size)
        // 全部挂到新书 id 下（非原 "orig"）
        assertTrue(copied.all { it.storyId == copy.id })
        assertTrue(copied.none { it.storyId == "orig" })
        // 角色内容保真（含用户角色标记 / 关联 AI 角色 / 空描述）
        val me = copied.first { it.roleName == "我" }
        assertEquals(StoryRoleType.PROTAGONIST, me.roleType)
        assertEquals("少年侠客", me.roleDescription)
        assertTrue(me.isUserRole)
        assertEquals("char-1", me.characterId)
        // 私下反差属「作者创作设定」而非叙事进度 → 随角色带进新书（漏拷 = 用户手写的人设凭空蒸发）
        assertEquals("人前正经，私下极黏人", me.intimatePersona)
        val master = copied.first { it.roleName == "师父" }
        assertFalse(master.isUserRole)
        assertNull(master.characterId)
        assertNull(master.roleDescription)
        assertNull("原角色没填反差 → 复制后仍为 null", master.intimatePersona)
    }

    @Test
    fun `重开_标题幂等_已带后缀不叠加`() = runBlocking {
        coEvery { storyRepository.getRoles(any()) } returns emptyList()

        // 首次重开：无后缀 → 追加一层
        val first = service.restartStory(
            StoryEntity(id = "s1", title = "原作", status = StoryStatus.COMPLETED),
            nowMillis = 1_000L,
        )
        assertEquals("原作·重开", first.title)

        // 重开「重开的书」：已带后缀 → 不再叠加（避免 原作·重开·重开）
        val again = service.restartStory(
            StoryEntity(id = "s2", title = "原作·重开", status = StoryStatus.COMPLETED),
            nowMillis = 2_000L,
        )
        assertEquals("原作·重开", again.title)

        // endsWith 精确匹配：仅「·重开」结尾才幂等，「…·重开的续集」非后缀结尾仍照常追加
        val sequel = service.restartStory(
            StoryEntity(id = "s3", title = "原作·重开的续集", status = StoryStatus.COMPLETED),
            nowMillis = 3_000L,
        )
        assertEquals("原作·重开的续集·重开", sequel.title)
    }
}
