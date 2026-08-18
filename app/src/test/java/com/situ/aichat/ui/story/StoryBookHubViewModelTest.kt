package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryDeleter
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.story.StoryFieldKind
import com.situ.aichat.story.StoryFieldValueStyle
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryGlobalCraftValues
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryUnlockNotificationScheduler
import com.situ.aichat.story.StoryWorldInfoService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 书页 T2（卷二 §7 T2-2）：头部「继续阅读」现查最新章、空书态（E1）、节拍卡三态（E12）、
 * 老书首开纯读零副作用（E13）、档案八节读值直接来自 story 快照。
 *
 * 断言从图纸 §4.1/§4.2 独立反推；书页 VM = 原地改造的 [StorySettingsViewModel]，
 * MockK 假仓库、Robolectric 主循环驱动 viewModelScope（照 [StorySettingsViewModelTest] 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryBookHubViewModelTest {

    private val repo = mockk<StoryRepository>(relaxed = true)
    private val generationService = mockk<StoryGenerationService>()
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val worldInfoService = mockk<StoryWorldInfoService>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)
    private val unlockScheduler = mockk<StoryUnlockNotificationScheduler>(relaxed = true)
    private val functionRouter = mockk<ApiFunctionRouter>()
    private val apiConfigs = mockk<ApiConfigRepository>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    private fun chapter(number: Int, id: String) =
        StoryChapterEntity(id = id, storyId = "s1", chapterNumber = number, title = "第$number 章", content = "正文")

    private val scope = CoroutineScope(Dispatchers.Main)

    @After fun tearDown() = scope.cancel()

    /** 订阅一次性提示事件（SharedFlow 无重放，必须在触发动作之前订阅）。 */
    private fun collectToasts(vm: StorySettingsViewModel): List<Int> {
        val out = mutableListOf<Int>()
        scope.launch { vm.toastEvents.collect { out += it } }
        idle()
        return out
    }

    /** 订阅「这本书没了」事件（同上）。 */
    private fun collectExits(vm: StorySettingsViewModel): List<Unit> {
        val out = mutableListOf<Unit>()
        scope.launch { vm.exitEvents.collect { out += it } }
        idle()
        return out
    }

    private fun vm(entity: StoryEntity?, archiver: StoryArchiver = mockk(relaxed = true)): StorySettingsViewModel {
        every { repo.observeStory(any()) } returns flowOf(entity)
        coEvery { repo.getRoles(any()) } returns emptyList()
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
        every { functionRouter.assignments } returns flowOf(emptyMap())
        every { apiConfigs.observeAll() } returns flowOf(emptyList())
        every { apiConfigs.observeActive() } returns flowOf(null)
        return StorySettingsViewModel(
            SavedStateHandle(mapOf("storyId" to "s1")),
            repo,
            generationService,
            taskManager,
            worldInfoService,
            readingProgressStore,
            archiver,
            StoryDeleter(repo, unlockScheduler), // 真删除件吃同一批 mock（e8 两例仍断言 repo 真删 + 撤闹钟联动）
            unlockScheduler,
            mockk(relaxed = true), // StoryPersonaDrafter
            mockk(relaxed = true), // StoryOutlineOrchestrator（本组用例不碰重排）
            functionRouter,
            apiConfigs,
            settingsRepository,
            mockk(relaxed = true),
        )
    }

    private fun idle() = repeat(20) { shadowOf(Looper.getMainLooper()).idle() }

    // ── 头部「继续阅读」：点的那一刻现查最新章 ──

    @Test fun 继续阅读_取章号最大的那一章() {
        // 卷二 §3.2：查询自身保证「章号最大的那一章」，桩直接给该章（原 maxByOrNull 随全表拉取一起删）。
        coEvery { repo.getLatestChapterMeta("s1") } returns chapter(3, "c3")
        assertEquals("c3", runBlocking { vm(StoryEntity(id = "s1")).latestChapterId() })
    }

    @Test fun 继续阅读_空书取不到章_返回null不导航() {
        coEvery { repo.getLatestChapterMeta("s1") } returns null
        assertNull(runBlocking { vm(StoryEntity(id = "s1")).latestChapterId() })
    }

    @Test fun 继续阅读_读库抛异常_吞掉返回null不崩() {
        coEvery { repo.getLatestChapterMeta("s1") } throws IllegalStateException("库炸了")
        assertNull(runBlocking { vm(StoryEntity(id = "s1")).latestChapterId() })
    }

    // ── E1：空书态 ──

    @Test fun e1_空书_不给继续阅读钮_副行只剩状态文案() {
        val empty = StoryEntity(id = "s1", cachedChapterCount = 0, cachedLatestChapterNumber = null)
        assertFalse(storyHubShowContinue(empty))
        assertEquals(StoryHubProgress(null, null), storyHubProgress(empty))
    }

    @Test fun e1_空书_八节全落空态_值标不谎报已自定义() {
        val empty = StoryEntity(id = "s1")
        for (field in StoryEditableField.entries.filter { it.kind == StoryFieldKind.ARCHIVE }) {
            assertNull("$field 空书应无内容", field.currentValue(empty))
            assertEquals(
                "$field 空态不该显示成用户改过",
                StoryFieldValueStyle.NEUTRAL,
                field.valueLabel(empty, StoryGlobalCraftValues()).style,
            )
        }
    }

    @Test fun 头部副行_有章报章号_有弧起点再报本弧第几章() {
        val story = StoryEntity(id = "s1", cachedChapterCount = 23, cachedLatestChapterNumber = 23, currentArcStartChapter = 18)
        // 第 23 章、弧从 18 起 → 本弧第 6 章（23 − 18 + 1）
        assertEquals(StoryHubProgress(23, 6), storyHubProgress(story))
    }

    @Test fun 头部副行_没有弧起点时不报本弧() {
        val story = StoryEntity(id = "s1", cachedChapterCount = 4, cachedLatestChapterNumber = 4, currentArcStartChapter = null)
        assertEquals(StoryHubProgress(4, null), storyHubProgress(story))
    }

    @Test fun 头部副行_缓存章数为零时不信残留的章号() {
        // 删光章节但 cachedLatestChapterNumber 还留着旧值时，副行不许报一个已经不存在的章
        val story = StoryEntity(id = "s1", cachedChapterCount = 0, cachedLatestChapterNumber = 7)
        assertEquals(StoryHubProgress(null, null), storyHubProgress(story))
    }

    // ── E12：下一章节拍卡三态 ──

    @Test fun e12_节拍卡_AI预排与已由你修改两种标签() {
        assertEquals(
            R.string.story_hub_tag_ai_planned,
            storyHubBeatsTagRes(StoryEntity(id = "s1", pendingChapterBeats = "预排的节拍", pendingBeatsUserEdited = false)),
        )
        assertEquals(
            R.string.story_hub_tag_user_edited,
            storyHubBeatsTagRes(StoryEntity(id = "s1", pendingChapterBeats = "我改的节拍", pendingBeatsUserEdited = true)),
        )
    }

    @Test fun e12_节拍卡_空态不给点开_有内容才给() {
        assertNull(storyHubBeatsText(StoryEntity(id = "s1", pendingChapterBeats = null)))
        assertNull("纯空白视同没有", storyHubBeatsText(StoryEntity(id = "s1", pendingChapterBeats = "   ")))
        assertEquals("预排的节拍", storyHubBeatsText(StoryEntity(id = "s1", pendingChapterBeats = "预排的节拍")))
    }

    @Test fun e12_节拍卡_只读没有编辑入口_不接导演台写路() {
        // 本卷只显示，绝不调 updateChapterBeatsUserEdited（那是卷三导演台的写口·图纸 §0.③）
        val vm = vm(StoryEntity(id = "s1", pendingChapterBeats = "预排"))
        idle()
        coVerify(exactly = 0) { repo.updateChapterBeatsUserEdited(any(), any(), any()) }
    }

    // ── E13：老书首开书页，纯读零副作用 ──

    @Test fun e13_老书首开_不产生任何写() {
        val legacy = StoryEntity(id = "s1", genre = "都市", writingStyle = "网文爽文", cachedChapterCount = 5, cachedLatestChapterNumber = 5)
        val vm = vm(legacy)
        idle()
        assertTrue("草稿照常初始化（进屏只读快照）", vm.draft.value != null)
        coVerify(exactly = 0) { repo.updateCustomPrompts(any(), any()) }
        coVerify(exactly = 0) { repo.updateStoryBible(any(), any()) }
        coVerify(exactly = 0) { repo.updateStoryOutlineUserEdit(any(), any(), any()) }
        coVerify(exactly = 0) { repo.updateStorySummaryUserEdit(any(), any(), any()) }
        coVerify(exactly = 0) { repo.setWorldInfoEnabled(any(), any()) }
    }

    // ── E8 / T2-5：归档与删除 ──

    @Test fun e8_归档成功_发提示并弹回书架() {
        val archiver = mockk<StoryArchiver>()
        coEvery { archiver.archive("s1", any()) } returns StoryArchiver.Result.ARCHIVED
        val vm = vm(StoryEntity(id = "s1"), archiver)
        val toasts = collectToasts(vm)
        val exits = collectExits(vm)

        vm.archiveStory()
        idle()

        coVerify(exactly = 1) { archiver.archive("s1", any()) }
        assertEquals(listOf(R.string.story_archived_toast), toasts)
        assertEquals("归档后不该再停在这本书的书页上", 1, exits.size)
    }

    @Test fun e8_归档遇生成中_只提示不退出() {
        val archiver = mockk<StoryArchiver>()
        coEvery { archiver.archive("s1", any()) } returns StoryArchiver.Result.BUSY
        val vm = vm(StoryEntity(id = "s1"), archiver)
        val toasts = collectToasts(vm)
        val exits = collectExits(vm)

        vm.archiveStory()
        idle()

        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
        assertTrue("生成中拒绝归档 → 留在原地", exits.isEmpty())
    }

    @Test fun e8_归档幂等静默_已完结既不提示也不退出() {
        val archiver = mockk<StoryArchiver>()
        coEvery { archiver.archive("s1", any()) } returns StoryArchiver.Result.SKIPPED
        val vm = vm(StoryEntity(id = "s1"), archiver)
        val toasts = collectToasts(vm)
        val exits = collectExits(vm)

        vm.archiveStory()
        idle()

        assertTrue(toasts.isEmpty())
        assertTrue(exits.isEmpty())
    }

    @Test fun e8_删除成功_删库并弹回书架() {
        coEvery { repo.deleteStory("s1") } returns Unit
        val vm = vm(StoryEntity(id = "s1"))
        val exits = collectExits(vm)

        vm.deleteStory()
        idle()

        coVerify(exactly = 1) { repo.deleteStory("s1") }
        verify(exactly = 1) { unlockScheduler.cancelUnlocks("s1", any()) }
        assertEquals(1, exits.size)
    }

    @Test fun e8_删除失败_置错误且不退出() {
        coEvery { repo.deleteStory("s1") } throws IllegalStateException("库锁住了")
        val vm = vm(StoryEntity(id = "s1"))
        val exits = collectExits(vm)

        vm.deleteStory()
        idle()

        assertEquals("库锁住了", vm.error.value)
        assertTrue("删失败还留在书页上，别把用户弹走还以为删掉了", exits.isEmpty())
        verify(exactly = 0) { unlockScheduler.cancelUnlocks(any(), any()) }
    }

    // ── 档案读值：直接来自 story 热流快照，编辑页保存后自动回流 ──

    @Test fun 档案读值_直接来自story快照_八节各取各列() {
        val story = StoryEntity(
            id = "s1",
            storyOutline = "大纲",
            intimacyLedger = "关系史",
            sceneLedger = "台账",
            sceneState = "状态",
            characterStates = "现状",
            openThreads = "伏笔",
            storySummary = "摘要",
            storyBible = "圣经",
            customPromptsJson = CustomStoryPrompts.encode(CustomStoryPrompts(sceneBeats = "本书节拍")),
        )
        val vm = vm(story)
        idle()
        val observed = vm.story.value!!
        assertEquals("大纲", StoryEditableField.OUTLINE.currentValue(observed))
        assertEquals("圣经", StoryEditableField.BIBLE.currentValue(observed))
        assertEquals("本书节拍", StoryEditableField.SCENE_BEATS.currentValue(observed))
    }
}
